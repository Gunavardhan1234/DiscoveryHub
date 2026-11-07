package com.smarsh.discoveryhub.archive.infrastructure.messaging;

import com.smarsh.discoveryhub.archive.domain.port.MessageDeletionEventPublisher;
import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.MessageDeletedEvent;
import com.smarsh.discoveryhub.events.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@code message.deleted.v1} with {@code messageId} as both the
 * Kafka key and the envelope {@code entityId}, mirroring {@link
 * KafkaStoredEventPublisher}'s conventions for {@code comm.stored.v1}.
 */
@Component
public class KafkaMessageDeletedEventPublisher implements MessageDeletionEventPublisher {

    private static final String EVENT_TYPE = "MESSAGE_DELETED";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaMessageDeletedEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishDeleted(String messageId, String commType, String reason) {
        MessageDeletedEvent payload = new MessageDeletedEvent(messageId, commType, reason);
        EventEnvelope<MessageDeletedEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                EVENT_TYPE,
                1,
                Instant.now(),
                messageId,
                CorrelationId.current(),
                Actor.current(),
                payload);
        kafkaTemplate.send(Topics.MESSAGE_DELETED_V1, messageId, envelope);
    }
}
