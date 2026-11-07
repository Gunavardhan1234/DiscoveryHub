package com.smarsh.discoveryhub.caseapp.infrastructure.messaging;

import com.smarsh.discoveryhub.caseapp.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@code audit.events.v1} with {@code caseId} as the Kafka key so a
 * per-case hash chain is contiguous on one partition (events.md §6). Mirrors
 * hold-service/export-service/ingest-service's identical adapter.
 */
// Adapter implementing AuditEventPublisher (Dependency Inversion — CaseService depends on the
// port, this is the only class that knows KafkaTemplate exists).
// Asynchronous messaging (FR-7): publishing here decouples case-service from audit-service —
// case-service fires the event onto the audit.events.v1 topic and returns immediately, without
// waiting for audit-service to actually consume and hash-chain it. This is why the audit publish
// calls upstream in CaseService are wrapped in a broad try/catch: audit-service being slow or
// briefly down must never block or fail a case create/update/transition request.
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
        // kafkaKey = caseId: every audit event for the same case lands on the same partition, so
        // audit-service's per-case hash-chain consumer sees them in the order case-service
        // published them — Kafka only guarantees ordering within a partition, not across the topic.
        kafkaTemplate.send(Topics.AUDIT_EVENTS_V1, kafkaKey, envelope);
    }
}
