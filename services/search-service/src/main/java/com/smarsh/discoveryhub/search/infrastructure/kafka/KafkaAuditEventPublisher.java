package com.smarsh.discoveryhub.search.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.search.domain.port.AuditEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@code audit.events.v1} with {@code caseId} (or the search's own
 * generated id, when the query isn't scoped to a case) as the Kafka key.
 *
 * <p>Unlike hold/export/ingest/case-service's identical adapter, search-service's
 * Kafka producer is configured with a plain {@code StringSerializer} for the
 * value (see {@code application.yml}, shared with the DLT recoverer), so the
 * envelope is serialized to a JSON string here rather than relying on a
 * {@code JsonSerializer}-backed producer — the wire format audit-service
 * consumes (a JSON string) is identical either way.
 *
 * <p><b>Design pattern — Adapter:</b> adapts {@code KafkaTemplate} to the
 * domain's {@link AuditEventPublisher} port. It is the only class in
 * search-service that knows audit events travel over Kafka at all; the domain
 * just says "publish this payload with this key".
 *
 * <p><b>SOLID — DIP + LSP:</b> implements a domain-owned interface, so it is
 * substitutable — {@code SearchServiceTest} asserts the
 * {@code SEARCH_EXECUTED} payload against a Mockito mock of the port with no
 * broker running.
 *
 * <p><b>SOLID — SRP:</b> envelope construction and serialization only. It does
 * not decide <em>what</em> is auditable or how the key is chosen — that policy
 * lives in {@code SearchService#publishAudit}, which keys by {@code caseId} so
 * a case's audit entries land on one partition and stay in order, keeping
 * audit-service's per-case hash chain contiguous.
 */
@Component
public class KafkaAuditEventPublisher implements AuditEventPublisher {

    private static final String EVENT_TYPE = "AUDIT_EVENT";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaAuditEventPublisher(KafkaTemplate<String, Object> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
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
        try {
            kafkaTemplate.send(Topics.AUDIT_EVENTS_V1, kafkaKey, objectMapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize audit.events.v1 payload", e);
        }
    }
}
