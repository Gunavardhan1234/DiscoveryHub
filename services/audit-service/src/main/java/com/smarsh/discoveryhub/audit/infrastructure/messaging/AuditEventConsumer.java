package com.smarsh.discoveryhub.audit.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.audit.application.AuditConsumeUseCase;
import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.events.EventEnvelope;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Thin Kafka adapter: consumes {@code audit.events.v1} in the {@code audit-sink}
 * group (the only consumer — events.md &sect;6) and hands the payload to
 * {@link AuditConsumeUseCase} which computes the hash chain and appends.
 */
@Component
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);
    private static final TypeReference<EventEnvelope<AuditEventPayload>> EVENT_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final AuditConsumeUseCase useCase;

    public AuditEventConsumer(ObjectMapper mapper, AuditConsumeUseCase useCase) {
        this.mapper = mapper;
        this.useCase = useCase;
    }

    @KafkaListener(topics = com.smarsh.discoveryhub.events.Topics.AUDIT_EVENTS_V1,
            groupId = "${spring.kafka.consumer.group-id:audit-sink}")
    public void onAuditEvent(String value) {
        EventEnvelope<AuditEventPayload> envelope;
        try {
            envelope = mapper.readValue(value, EVENT_TYPE);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Cannot parse audit.events.v1 payload", e);
        }
        CorrelationId.set(envelope.correlationId());
        Actor.set(envelope.actor());
        try {
            AuditEventPayload payload = envelope.payload();
            log.debug("Auditing action={} target={}/{}", payload.action(), payload.targetType(), payload.targetId());
            useCase.accept(payload);
        } finally {
            CorrelationId.clear();
            Actor.clear();
        }
    }
}
