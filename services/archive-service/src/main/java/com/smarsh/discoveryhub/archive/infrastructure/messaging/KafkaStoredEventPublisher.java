package com.smarsh.discoveryhub.archive.infrastructure.messaging;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.archive.domain.port.StoredEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@code comm.stored.v1} with {@code messageId} as both the Kafka key
 * and the envelope {@code entityId} (events.md §2).
 */
@Component
public class KafkaStoredEventPublisher implements StoredEventPublisher {

    private static final String EVENT_TYPE = "MESSAGE_STORED";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaStoredEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishStored(CommunicationEvent payload, String kafkaKey) {
        EventEnvelope<CommunicationEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                EVENT_TYPE,
                1,
                Instant.now(),
                payload.messageId(),
                CorrelationId.current(),
                Actor.current(),
                payload);
        kafkaTemplate.send(Topics.COMM_STORED_V1, kafkaKey, envelope);
    }
}
