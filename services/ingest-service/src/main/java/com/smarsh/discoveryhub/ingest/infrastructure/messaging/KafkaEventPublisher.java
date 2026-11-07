package com.smarsh.discoveryhub.ingest.infrastructure.messaging;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.ingest.domain.port.EventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes {@code comm.ingest.v1} with the {@code messageKey} as the Kafka
 * key and the {@code messageId} as the envelope {@code entityId}. Waits for
 * the broker ack so the caller's 202 means "acknowledged by Kafka" (events.md
 * &sect;1); a failed/timeout ack surfaces as a runtime exception the use case
 * maps to {@code BROKER_UNAVAILABLE}.
 */
@Component
public class KafkaEventPublisher implements EventPublisher {

    private static final long ACK_TIMEOUT_SECONDS = 10;
    public static final String EVENT_TYPE = "MESSAGE_INGESTED";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishIngested(CommunicationEvent payload, String kafkaKey) {
        EventEnvelope<CommunicationEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                EVENT_TYPE,
                1,
                Instant.now(),
                payload.messageId(),
                CorrelationId.current(),
                Actor.current(),
                payload);
        try {
            kafkaTemplate.send(Topics.COMM_INGEST_V1, kafkaKey, envelope)
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while awaiting Kafka ack", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException("Kafka ack failed for " + payload.messageId(), e);
        }
    }
}
