package com.smarsh.discoveryhub.ingest.infrastructure.messaging;

import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.ingest.domain.port.AuditEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@code audit.events.v1} with {@code caseId} as the Kafka key so a
 * per-case hash chain is contiguous on one partition (events.md §6).
 */
@Component
public class KafkaAuditEventPublisher implements AuditEventPublisher {

    private static final String EVENT_TYPE = "AUDIT_EVENT";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaAuditEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(AuditEventPayload payload, String kafkaKey) {
        EventEnvelope<AuditEventPayload> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                EVENT_TYPE,
                1,
                Instant.now(),
                payload.eventId(),
                payload.correlationId(),
                payload.actor(),
                payload);
        kafkaTemplate.send(Topics.AUDIT_EVENTS_V1, kafkaKey, envelope);
    }
}
