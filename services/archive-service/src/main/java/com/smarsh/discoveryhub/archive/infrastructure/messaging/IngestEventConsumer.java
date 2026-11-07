package com.smarsh.discoveryhub.archive.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.archive.application.IngestConsumeUseCase;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Thin Kafka adapter (architecture &sect;2.1, FR-1.4): consumes
 * {@code comm.ingest.v1} in the {@code archive-ingest} group, hands the payload
 * to {@link IngestConsumeUseCase}, and returns. No business logic here — the
 * use case does the Mongo upsert + {@code comm.stored.v1} publish.
 *
 * <p>Receives the raw JSON string and parses with a typed {@link TypeReference}
 * so the generic {@code EventEnvelope<CommunicationEvent>} payload deserializes
 * correctly (same pattern as search-service's {@code MessageIndexer}).
 */
@Component
public class IngestEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(IngestEventConsumer.class);
    private static final TypeReference<EventEnvelope<CommunicationEvent>> EVENT_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final IngestConsumeUseCase useCase;

    public IngestEventConsumer(ObjectMapper mapper, IngestConsumeUseCase useCase) {
        this.mapper = mapper;
        this.useCase = useCase;
    }

    @KafkaListener(topics = Topics.COMM_INGEST_V1, groupId = "${spring.kafka.consumer.group-id:archive-ingest}")
    public void onIngested(String value) {
        EventEnvelope<CommunicationEvent> envelope;
        try {
            envelope = mapper.readValue(value, EVENT_TYPE);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Cannot parse comm.ingest.v1 payload", e);
        }
        CorrelationId.set(envelope.correlationId());
        Actor.set(envelope.actor());
        try {
            CommunicationEvent event = envelope.payload();
            log.debug("Archiving message {} (key={})", event.messageId(), event.messageKey());
            useCase.accept(event);
        } finally {
            CorrelationId.clear();
            Actor.clear();
        }
    }
}
