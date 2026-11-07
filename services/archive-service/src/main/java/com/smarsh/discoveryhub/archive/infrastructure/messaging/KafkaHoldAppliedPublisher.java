package com.smarsh.discoveryhub.archive.infrastructure.messaging;

import com.smarsh.discoveryhub.archive.domain.port.HoldAppliedPublisher;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.HoldApplied;
import com.smarsh.discoveryhub.events.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@code hold.applied.v1} with the {@code holdId} as the Kafka key so
 * all progress events for a hold land on one partition and are seen in order by
 * the {@code hold-tracker} consumer. Matches the archive-service producer
 * convention (no JVM type headers; consumers deserialize with a
 * {@code TypeReference}).
 */
@Component
public class KafkaHoldAppliedPublisher implements HoldAppliedPublisher {

    private static final String EVENT_TYPE = "HOLD_APPLIED";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaHoldAppliedPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(HoldApplied payload, String kafkaKey) {
        EventEnvelope<HoldApplied> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                EVENT_TYPE,
                1,
                Instant.now(),
                payload.holdId(),
                null,
                "archive-hold-applier",
                payload);
        kafkaTemplate.send(Topics.HOLD_APPLIED_V1, kafkaKey, envelope);
    }
}
