package com.smarsh.discoveryhub.audit.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarsh.discoveryhub.audit.application.AuditConsumeUseCase;
import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.events.EventEnvelope;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit test for the {@code audit.events.v1} Kafka adapter: deserializes the
 * envelope, delegates to {@link AuditConsumeUseCase}, propagates {@link
 * CorrelationId}/{@link Actor} for the duration of the call, and always
 * clears them afterward — even if the use case throws.
 */
class AuditEventConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AuditConsumeUseCase useCase = mock(AuditConsumeUseCase.class);
    private final AuditEventConsumer consumer = new AuditEventConsumer(mapper, useCase);

    private String envelopeJson(AuditEventPayload payload, String correlationId, String actor) throws Exception {
        EventEnvelope<AuditEventPayload> envelope = new EventEnvelope<>(
                UUID.randomUUID(), "AUDIT_EVENT", 1, Instant.now(), payload.targetId(), correlationId, actor, payload);
        return mapper.writeValueAsString(envelope);
    }

    private AuditEventPayload samplePayload() {
        return new AuditEventPayload(
                UUID.randomUUID().toString(), Instant.now(), "alice", "CASE_CREATED",
                "CASE", "case-1", "case-1", null, "{\"status\":\"DRAFT\"}", "corr-1");
    }

    @Test
    void deserializesEnvelopeAndDelegatesToUseCase() throws Exception {
        AuditEventPayload payload = samplePayload();
        String json = envelopeJson(payload, "corr-42", "bob");

        consumer.onAuditEvent(json);

        verify(useCase).accept(eq(payload));
    }

    @Test
    void seedsCorrelationIdAndActorForTheDurationOfTheCall() throws Exception {
        AuditEventPayload payload = samplePayload();
        String json = envelopeJson(payload, "corr-99", "carol");

        String[] seenCorrelationId = new String[1];
        String[] seenActor = new String[1];
        doAnswer(inv -> {
            seenCorrelationId[0] = CorrelationId.current();
            seenActor[0] = Actor.current();
            return null;
        }).when(useCase).accept(any());

        consumer.onAuditEvent(json);

        assertThat(seenCorrelationId[0]).isEqualTo("corr-99");
        assertThat(seenActor[0]).isEqualTo("carol");
        // cleared after the call — current() falls back to a fresh id / "system"
        assertThat(Actor.current()).isEqualTo("system");
    }

    @Test
    void clearsCorrelationIdAndActorEvenWhenUseCaseThrows() throws Exception {
        AuditEventPayload payload = samplePayload();
        String json = envelopeJson(payload, "corr-1", "dave");
        doAnswer(inv -> { throw new RuntimeException("mongo down"); }).when(useCase).accept(any());

        assertThatThrownBy(() -> consumer.onAuditEvent(json)).isInstanceOf(RuntimeException.class);

        assertThat(Actor.current()).isEqualTo("system");
    }

    @Test
    void malformedJsonThrowsSerializationExceptionWithoutCallingUseCase() {
        assertThatThrownBy(() -> consumer.onAuditEvent("not json"))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("audit.events.v1");

        verify(useCase, never()).accept(any());
    }
}
