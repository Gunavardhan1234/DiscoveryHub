package com.smarsh.discoveryhub.search.infrastructure.kafka;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import com.smarsh.discoveryhub.search.domain.model.SearchResult;
import com.smarsh.discoveryhub.search.domain.service.SearchService;
import com.smarsh.discoveryhub.search.infrastructure.es.ElasticsearchSearchPort;
import com.smarsh.discoveryhub.search.infrastructure.es.EsQueryTranslator;
import com.smarsh.discoveryhub.search.infrastructure.es.IndexInitializer;
import org.apache.http.HttpHost;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsearch.client.RestClient;
import com.github.dockerjava.api.model.PortBinding;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.DefaultResourceLoader;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Resiliency proof for the search path (NFR-2, fullplan Day-1 Member 2 test list:
 * "backlog catch-up after ES/consumer restart").
 *
 * <p>Scenario, mirroring the demo script in day1.md §4:
 * <ol>
 *   <li>search consumer up → ingest message A → it is searchable.</li>
 *   <li><b>search consumer DOWN</b> → ingest B, C, D (Kafka retains them; consumer
 *       lag grows &gt; 0 because nothing is reading).</li>
 *   <li><b>restart the consumer in the SAME group</b> → it resumes from its committed
 *       offset and drains B, C, D (lag → 0).</li>
 *   <li>{@code search} returns A, B, C, D — <b>nothing was lost</b> because Kafka is
 *       the durable buffer and ES is a rebuildable projection, not the source of truth.</li>
 * </ol>
 *
 * <p>Uses an externally provided Kafka + ES when {@code TEST_KAFKA_URI} /
 * {@code {@code TEST_ES_URI} are set (e.g. when the Docker daemon isn't discoverable
 * by Testcontainers); otherwise starts Testcontainers Kafka + ES (the CI path).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchBacklogCatchUpIT {

    private static final String GROUP = "search-service-it";
    private static final String INDEX = "messages-backlog-it";

    private static final String KAFKA_URI =
            System.getProperty("test.kafka.uri", System.getenv().getOrDefault("TEST_KAFKA_URI", ""));
    private static final String ES_URI =
            System.getProperty("test.es.uri", System.getenv().getOrDefault("TEST_ES_URI", ""));

    private GenericContainer<?> kafkaContainer;
    private ElasticsearchContainer esContainer;
    private String bootstrap;
    private RestClient restClient;
    private ElasticsearchClient esClient;
    private ElasticsearchSearchPort port;
    private MessageIndexer indexer;
    private ObjectMapper mapper;

    @BeforeAll
    void setUp() {
        String kafka = KAFKA_URI;
        String es = ES_URI;
        if ((kafka == null || kafka.isBlank()) || (es == null || es.isBlank())) {
            try {
                kafkaContainer = new GenericContainer<>("apache/kafka:3.9.0")
                    .withExposedPorts(9094)
                    .withEnv("KAFKA_NODE_ID", "1")
                    .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                    .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9094,CONTROLLER://:9093")
                    .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:9094")
                    .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                    .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
                    .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
                    .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                    .withCreateContainerCmdModifier(cmd -> {
                        cmd.withHostName("localhost");
                        cmd.withPortBindings(PortBinding.parse("9094:9094"));
                    })
                    .waitingFor(Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1));

                kafkaContainer.start();
                bootstrap = "localhost:9094";
                esContainer = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.18.1")
                        .withEnv("xpack.security.enabled", "false")
                        .withEnv("discovery.type", "single-node")
                        .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m");
                esContainer.start();
                kafka = bootstrap;
                es = "http://" + esContainer.getHttpHostAddress();
            } catch (IllegalStateException noDocker) {
                // No Docker available and no external URIs — skip rather than fail.
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "Skipped: set TEST_KAFKA_URI + TEST_ES_URI (or run where Testcontainers can reach Docker)");
                return;
            }
        }
        bootstrap = kafka;
        restClient = RestClient.builder(HttpHost.create(es)).build();
        esClient = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
        createIndex();
        port = new ElasticsearchSearchPort(esClient, new EsQueryTranslator(INDEX), INDEX);
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SearchService searchService = new SearchService(port, () -> 0, (payload, key) -> { });
        indexer = new MessageIndexer(mapper, searchService);
        createTopic();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (restClient != null) {
            restClient.close();
        }
        if (esContainer != null) {
            esContainer.stop();
        }
        if (kafkaContainer != null) {
            kafkaContainer.stop();
        }
    }

    @Test
    void backlogCaughtUpAfterConsumerRestart() {
        // Phase 1: consumer up — ingest A, verify searchable.
        try (KafkaConsumer<String, String> c1 = consumer()) {
            produce("msg-A", "Project Phoenix alpha", "alpha body phoenix");
            drain(c1, 1);
            c1.commitSync();
        }
        awaitUntilSearchable("Project Phoenix", "msg-A");

        // Phase 2: consumer DOWN — ingest B, C, D. Kafka retains them; lag grows.
        produce("msg-B", "Project Phoenix beta", "beta body phoenix");
        produce("msg-C", "Project Phoenix gamma", "gamma body phoenix");
        produce("msg-D", "Project Phoenix delta", "delta body phoenix");
        // No consumer running — the events are buffered in the Kafka topic.

        // Phase 3: RESTART consumer in the SAME group — resumes from committed offset,
        // drains B, C, D (lag → 0).
        try (KafkaConsumer<String, String> c2 = consumer()) {
            drain(c2, 3);
            c2.commitSync();
        }

        // Phase 4: search returns all four — nothing lost.
        awaitUntilSearchable("Project Phoenix", "msg-A", "msg-B", "msg-C", "msg-D");
        SearchResult result = port.search(QuerySpec.builder().q("Project Phoenix").size(50).build());
        List<String> ids = result.hits().stream().map(h -> h.messageId()).toList();
        assertThat(ids).containsExactlyInAnyOrder("msg-A", "msg-B", "msg-C", "msg-D");
    }

    private void awaitUntilSearchable(String q, String... expectedIds) {
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            esClient.indices().refresh(r -> r.index(INDEX));
            SearchResult r = port.search(QuerySpec.builder().q(q).size(50).build());
            List<String> ids = r.hits().stream().map(h -> h.messageId()).toList();
            assertThat(ids).contains(expectedIds);
        });
    }

    /** Poll until exactly {@code expected} NEW records are processed and indexed. */
    private void drain(KafkaConsumer<String, String> consumer, int expected) {
        long deadline = System.currentTimeMillis() + 30_000;
        int processed = 0;
        while (processed < expected && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> r : records) {
                indexer.onMessage(r.value());
                processed++;
            }
            if (processed > 0) {
                consumer.commitSync();
            }
        }
        assertThat(processed).as("records drained by restarted consumer").isEqualTo(expected);
    }

    private KafkaConsumer<String, String> consumer() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        KafkaConsumer<String, String> c = new KafkaConsumer<>(p);
        c.subscribe(List.of(Topics.COMM_STORED_V1));
        return c;
    }

    private void produce(String messageId, String subject, String body) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            CommunicationEvent event = new CommunicationEvent(
                    messageId, "sha256:" + messageId, messageId, "EMAIL", subject, body,
                    null, null, "c-0007",
                    List.of(new CommunicationEvent.Participant("Ada Byron", "ada@fict.example", "FROM")),
                    Instant.parse("2026-04-01T10:00:00Z"), List.of(), false);
            EventEnvelope<CommunicationEvent> envelope = new EventEnvelope<>(
                    UUID.randomUUID(), "MESSAGE_INGESTED", 1, Instant.now(), messageId,
                    "corr-" + messageId, "corpus-generator", event);
            producer.send(new ProducerRecord<>(Topics.COMM_STORED_V1, messageId, mapper.writeValueAsString(envelope)));
            producer.flush();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to produce " + messageId, e);
        }
    }

    private void createTopic() {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            admin.createTopics(List.of(new NewTopic(Topics.COMM_STORED_V1, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Already exists is fine.
        }
    }

    private void createIndex() {
        try {
            esClient.indices().delete(d -> d.index(INDEX).ignoreUnavailable(true));
        } catch (Exception ignored) {
        }
        new IndexInitializer(esClient, new DefaultResourceLoader(), INDEX,
                "classpath:es/messages-index.json").run(null);
    }
}
