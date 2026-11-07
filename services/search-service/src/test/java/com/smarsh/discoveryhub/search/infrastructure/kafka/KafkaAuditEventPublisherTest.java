package com.smarsh.discoveryhub.search.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaAuditEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final KafkaAuditEventPublisher publisher = new KafkaAuditEventPublisher(kafkaTemplate, mapper);

    @Test
    void publishesTheEnvelopeAsAJsonStringKeyedByCaseId() {
        AuditEventPayload payload = new AuditEventPayload(
                UUID.randomUUID().toString(), Instant.now(), "system", "SEARCH_REINDEXED",
                "SEARCH", "reindex-1", null, null, "{\"rebuilt\":5000}", "corr-1");

        publisher.publish(payload, "case-1");

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(Topics.AUDIT_EVENTS_V1), eq("case-1"), captor.capture());
        assertThat(captor.getValue()).contains("SEARCH_REINDEXED").contains(payload.eventId());
    }

    @Test
    void unserializablePayloadSurfacesAsIllegalStateException() throws Exception {
        // A broken ObjectMapper that can't write anything simulates a serialization failure.
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        org.mockito.Mockito.when(brokenMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonGenerationException("boom",
                        (com.fasterxml.jackson.core.JsonGenerator) null));
        KafkaAuditEventPublisher failingPublisher = new KafkaAuditEventPublisher(kafkaTemplate, brokenMapper);
        AuditEventPayload payload = new AuditEventPayload(
                "e1", Instant.now(), "system", "X", "Y", "z", null, null, null, "corr-1");

        assertThatThrownBy(() -> failingPublisher.publish(payload, "case-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to serialize audit.events.v1 payload");
    }
}
