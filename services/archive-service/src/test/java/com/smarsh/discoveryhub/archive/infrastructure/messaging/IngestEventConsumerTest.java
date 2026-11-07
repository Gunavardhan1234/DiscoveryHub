package com.smarsh.discoveryhub.archive.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarsh.discoveryhub.archive.application.IngestConsumeUseCase;
import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.events.EventEnvelope;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class IngestEventConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final IngestConsumeUseCase useCase = mock(IngestConsumeUseCase.class);
    private final IngestEventConsumer consumer = new IngestEventConsumer(mapper, useCase);

    private CommunicationEvent payload() {
        return new CommunicationEvent("msg-1", "key-1", "ext-1", "EMAIL", "hi", "body",
                "thread-1", null, "c-1", List.of(), Instant.now(), List.of(), false);
    }

    private String envelopeJson(CommunicationEvent event, String correlationId, String actor) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                UUID.randomUUID(), "MESSAGE_INGESTED", 1, Instant.now(), event.messageId(), correlationId, actor, event));
    }

    @Test
    void deserializesAndDelegatesToUseCase() throws Exception {
        CommunicationEvent event = payload();

        consumer.onIngested(envelopeJson(event, "corr-1", "system"));

        verify(useCase).accept(eq(event));
    }

    @Test
    void seedsAndClearsCorrelationIdAndActor() throws Exception {
        CommunicationEvent event = payload();
        String[] seen = new String[2];
        doAnswer(inv -> {
            seen[0] = CorrelationId.current();
            seen[1] = Actor.current();
            return null;
        }).when(useCase).accept(any());

        consumer.onIngested(envelopeJson(event, "corr-77", "ingest-bot"));

        assertThat(seen[0]).isEqualTo("corr-77");
        assertThat(seen[1]).isEqualTo("ingest-bot");
        assertThat(Actor.current()).isEqualTo("system");
    }

    @Test
    void clearsCorrelationIdAndActorEvenWhenUseCaseThrows() throws Exception {
        doAnswer(inv -> { throw new RuntimeException("mongo down"); }).when(useCase).accept(any());

        assertThatThrownBy(() -> consumer.onIngested(envelopeJson(payload(), "corr-1", "system")))
                .isInstanceOf(RuntimeException.class);

        assertThat(Actor.current()).isEqualTo("system");
    }

    @Test
    void malformedJsonThrowsSerializationException() {
        assertThatThrownBy(() -> consumer.onIngested("not json"))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("comm.ingest.v1");

        verify(useCase, never()).accept(any());
    }
}
