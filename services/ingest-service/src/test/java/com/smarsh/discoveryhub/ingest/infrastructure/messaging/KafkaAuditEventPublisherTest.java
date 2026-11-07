package com.smarsh.discoveryhub.ingest.infrastructure.messaging;

import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaAuditEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaAuditEventPublisher publisher = new KafkaAuditEventPublisher(kafkaTemplate);

    @Test
    void publishesAuditEventKeyedByCaseId() {
        AuditEventPayload payload = new AuditEventPayload("evt-1", Instant.now(), "auditor",
                "EXPORT_REQUESTED", "EXPORT", "exp-1", "case-9", null, null, "corr-7");

        publisher.publish(payload, "case-9");

        ArgumentCaptor<Object> envelopeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(Topics.AUDIT_EVENTS_V1), eq("case-9"), envelopeCaptor.capture());
        EventEnvelope<AuditEventPayload> envelope = (EventEnvelope<AuditEventPayload>) envelopeCaptor.getValue();
        assertThat(envelope.eventType()).isEqualTo("AUDIT_EVENT");
        assertThat(envelope.entityId()).isEqualTo("evt-1");
        assertThat(envelope.correlationId()).isEqualTo("corr-7");
        assertThat(envelope.actor()).isEqualTo("auditor");
        assertThat(envelope.payload()).isEqualTo(payload);
    }
}
