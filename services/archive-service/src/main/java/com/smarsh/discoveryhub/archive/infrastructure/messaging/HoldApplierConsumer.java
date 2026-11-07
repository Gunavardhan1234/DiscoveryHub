package com.smarsh.discoveryhub.archive.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.archive.application.HoldApplierUseCase;
import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.HoldCommand;
import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code hold.commands.v1} in the {@code archive-hold-applier} group
 * — independent of the {@code archive-ingest} group so hold application never
 * blocks or is blocked by the ingest/durability pipeline (architecture &sect;4).
 * Hands each command to {@link HoldApplierUseCase}; no business logic here.
 *
 * <p>Deserialization is manual (String + {@link TypeReference}) to match the
 * hold-service outbox publisher, which sends raw JSON without JVM type headers.
 */
@Component
public class HoldApplierConsumer {

    private static final Logger log = LoggerFactory.getLogger(HoldApplierConsumer.class);
    private static final TypeReference<EventEnvelope<HoldCommand>> TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final HoldApplierUseCase useCase;

    public HoldApplierConsumer(ObjectMapper mapper, HoldApplierUseCase useCase) {
        this.mapper = mapper;
        this.useCase = useCase;
    }

    @KafkaListener(topics = Topics.HOLD_COMMANDS_V1, groupId = "archive-hold-applier")
    public void onCommand(String value) {
        EventEnvelope<HoldCommand> envelope;
        try {
            envelope = mapper.readValue(value, TYPE);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Cannot parse hold.commands.v1 payload", e);
        }
        CorrelationId.set(envelope.correlationId());
        Actor.set(envelope.actor());
        try {
            HoldCommand command = envelope.payload();
            log.debug("Applying hold command {} action={}", command.holdId(), command.action());
            useCase.apply(command);
        } finally {
            CorrelationId.clear();
            Actor.clear();
        }
    }
}
