package com.smarsh.discoveryhub.hold.infrastructure.messaging;

import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaAuditEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaAuditEventPublisher publisher = new KafkaAuditEventPublisher(kafkaTemplate);

    @Test
    void publishesToAuditEventsTopicKeyedByCaseId() {
        AuditEventPayload payload = new AuditEventPayload(
                UUID.randomUUID().toString(), Instant.now(), "alice", "HOLD_PLACED",
                "HOLD", "hold-1", "case-1", null, "{\"status\":\"PENDING\"}", "corr-1");

        publisher.publish(payload, "case-1");

        var captor = org.mockito.ArgumentCaptor.forClass(EventEnvelope.class);
        verify(kafkaTemplate).send(eq(Topics.AUDIT_EVENTS_V1), eq("case-1"), captor.capture());
        EventEnvelope<?> envelope = captor.getValue();
        assertThat(envelope.payload()).isEqualTo(payload);
        assertThat(envelope.actor()).isEqualTo("alice");
        assertThat(envelope.correlationId()).isEqualTo("corr-1");
    }
}
