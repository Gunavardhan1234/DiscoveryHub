package com.smarsh.discoveryhub.hold.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.hold.api.dto.HoldRequest;
import com.smarsh.discoveryhub.hold.api.dto.HoldResponse;
import com.smarsh.discoveryhub.hold.infrastructure.messaging.OutboxPoller;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.HoldRepository;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.OutboxRepository;
import com.smarsh.discoveryhub.testsupport.DockerChecks;
import com.smarsh.discoveryhub.testsupport.PostgresTestBase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Cross-boundary IT for FR-4.3 "hold propagation" (execution-plan Day 4 test
 * matrix). Proves hold-service's half of the transactional-outbox pattern
 * (D-008) end to end against real infrastructure: placing a hold durably
 * commits a hold row <em>and</em> an outbox row in the same Postgres
 * transaction, and the real {@link OutboxPoller} — not a mock — publishes
 * that row to a real Kafka broker as {@code hold.commands.v1}, in order,
 * exactly once per row.
 *
 * <p>The archive hold-applier that consumes this command and fans it out to
 * messages lives in archive-service and is covered by that module's own
 * tests (see {@code HoldApplierUseCaseTest}); a single Testcontainers IT
 * spanning two separate Spring Boot applications would not match how any
 * other service in this repo is tested, so this IT stops at hold-service's
 * own boundary — the command actually lands on the topic, correctly shaped.
 *
 * <p>Needs both Postgres and Kafka, and no shared base class extends two
 * Testcontainers bases at once, so this manages its own Kafka container
 * alongside {@link PostgresTestBase} (same image/skip behavior as
 * {@code KafkaTestBase}).
 */
@SpringBootTest(properties = {
        "discoveryhub.disposition.scheduled-enabled=false",
        "discoveryhub.hold.outbox.scheduled-enabled=false"
})
class HoldPropagationIT extends PostgresTestBase {

    private static KafkaContainer kafka;

    @BeforeAll
    static void startKafka() {
        Assumptions.assumeTrue(DockerChecks.dockerAvailable(),
                "Docker is unavailable; skipping Kafka integration test");
        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));
        kafka.start();
    }

    @AfterAll
    static void stopKafka() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::dbUsername);
        registry.add("spring.datasource.password", PostgresTestBase::dbPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> kafka.getBootstrapServers());
    }

    @Autowired
    private HoldService holdService;
    @Autowired
    private OutboxPoller outboxPoller;
    @Autowired
    private HoldRepository holds;
    @Autowired
    private OutboxRepository outbox;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void placingAHoldIsDurableThenPropagatesToKafka() {
        String caseId = UUID.randomUUID().toString();
        HoldRequest request = new HoldRequest(caseId, List.of("c-0007", "c-0012"),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-30T00:00:00Z"),
                "wire transfer", "investigator@demo");

        HoldResponse response = holdService.place(request);
        UUID holdId = UUID.fromString(response.holdId());

        // Durability first: the hold row and its outbox command are both
        // already committed by the time place() returns — the transactional
        // outbox guarantee — independent of whether Kafka ever comes up.
        assertThat(holds.findById(holdId)).as("hold row committed to Postgres").isPresent();
        assertThat(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc())
                .as("unpublished outbox row written in the same transaction as the hold")
                .isNotEmpty();

        // Propagation: call the real poller (not the @Scheduled cadence, for
        // a deterministic test) and prove the command really lands on Kafka.
        outboxPoller.poll();

        try (KafkaConsumer<String, String> consumer = consumer("hold-propagation-it-" + holdId)) {
            consumer.subscribe(List.of(Topics.HOLD_COMMANDS_V1));
            ConsumerRecord<String, String> record = awaitRecords(consumer, holdId.toString(), 1).get(0);

            assertThat(record.key()).isEqualTo(holdId.toString());
            JsonNode payload = readPayload(record.value());
            assertThat(payload.get("holdId").asText()).isEqualTo(holdId.toString());
            assertThat(payload.get("caseId").asText()).isEqualTo(caseId);
            assertThat(payload.get("action").asText()).isEqualTo("PLACE");
            assertThat(payload.get("custodians")).extracting(JsonNode::asText)
                    .containsExactly("c-0007", "c-0012");
            assertThat(payload.get("terms").asText()).isEqualTo("wire transfer");
        }

        // The outbox row is now marked published — a second poll() is a no-op,
        // not a duplicate publish.
        assertThat(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc())
                .as("outbox row stamped published_at after a successful send")
                .isEmpty();
    }

    @Test
    void releasingAHoldPropagatesReleaseAfterPlace() {
        String caseId = UUID.randomUUID().toString();
        HoldResponse placed = holdService.place(
                new HoldRequest(caseId, List.of("c-0099"), null, null, null, "investigator@demo"));
        UUID holdId = UUID.fromString(placed.holdId());
        outboxPoller.poll();

        holdService.release(holdId);
        outboxPoller.poll();

        // Both PLACE and RELEASE are already sitting on the topic (same key,
        // so the same partition, in send order) by the time this consumer
        // subscribes — a single poll() can return both in one batch. Collect
        // both before asserting instead of two independent await-one calls:
        // a naive "await one, then await another" drops the second record,
        // since the consumer's fetch position advances past the whole batch
        // the first time it is polled, whether or not the caller acted on
        // every record in it.
        try (KafkaConsumer<String, String> consumer = consumer("hold-release-it-" + holdId)) {
            consumer.subscribe(List.of(Topics.HOLD_COMMANDS_V1));
            List<ConsumerRecord<String, String>> records = awaitRecords(consumer, holdId.toString(), 2);
            assertThat(readPayload(records.get(0).value()).get("action").asText()).isEqualTo("PLACE");
            assertThat(readPayload(records.get(1).value()).get("action").asText()).isEqualTo("RELEASE");
        }
    }

    private JsonNode readPayload(String envelopeJson) {
        try {
            return mapper.readTree(envelopeJson).get("payload");
        } catch (Exception e) {
            throw new AssertionError("Unparsable envelope JSON: " + envelopeJson, e);
        }
    }

    /**
     * Polls until at least {@code expectedCount} records keyed by
     * {@code holdId} have arrived (in receive order), or fails after 20s.
     * Accumulates across polls rather than returning on the first match, so
     * a poll batch containing more than one matching record never silently
     * drops the rest.
     */
    private List<ConsumerRecord<String, String>> awaitRecords(
            KafkaConsumer<String, String> consumer, String holdId, int expectedCount) {
        List<ConsumerRecord<String, String>> matched = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (matched.size() < expectedCount && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (holdId.equals(record.key())) {
                    matched.add(record);
                }
            }
        }
        if (matched.size() < expectedCount) {
            fail("Expected " + expectedCount + " hold.commands.v1 record(s) for holdId=" + holdId
                    + " within 20s, got " + matched.size());
        }
        return matched;
    }

    private KafkaConsumer<String, String> consumer(String groupId) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(p);
    }
}
