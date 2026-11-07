package com.smarsh.discoveryhub.ingest.infrastructure.messaging;

import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaEventPublisher publisher = new KafkaEventPublisher(kafkaTemplate);

    private CommunicationEvent event() {
        return new CommunicationEvent("msg-1", "sha256:key", "ext-1", "EMAIL", "subject",
                "body", "th-1", null, "c-0001", java.util.List.of(), Instant.now(),
                java.util.List.of(), false);
    }

    @Test
    void publishesEnvelopeOnCommIngestTopicAndWaitsForAck() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null,
                        new RecordMetadata(null, 0L, 0L, 0L, 0L, 0, 0))));

        publisher.publishIngested(event(), "sha256:key");

        ArgumentCaptor<Object> envelopeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(Topics.COMM_INGEST_V1), eq("sha256:key"), envelopeCaptor.capture());
        EventEnvelope<CommunicationEvent> envelope = (EventEnvelope<CommunicationEvent>) envelopeCaptor.getValue();
        assertThat(envelope.eventType()).isEqualTo(KafkaEventPublisher.EVENT_TYPE);
        assertThat(envelope.entityId()).isEqualTo("msg-1");
        assertThat(envelope.payload().messageId()).isEqualTo("msg-1");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
    }

    @Test
    void failedAckSurfacesAsRuntimeException() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThatThrownBy(() -> publisher.publishIngested(event(), "sha256:key"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Kafka ack failed for msg-1");
    }

    @Test
    void interruptWhileAwaitingAckSurfacesAsRuntimeException() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(new CompletableFuture<>()); // never completes

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publishIngested(event(), "sha256:key"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Interrupted while awaiting Kafka ack");
        } finally {
            // Drain the interrupt flag the handler re-set so it cannot leak into other tests.
            assertThat(Thread.interrupted()).isTrue();
        }
    }
}
