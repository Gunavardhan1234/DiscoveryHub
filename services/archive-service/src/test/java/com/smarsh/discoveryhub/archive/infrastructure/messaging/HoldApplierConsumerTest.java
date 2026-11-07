package com.smarsh.discoveryhub.archive.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarsh.discoveryhub.archive.application.HoldApplierUseCase;
import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.HoldCommand;
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

class HoldApplierConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final HoldApplierUseCase useCase = mock(HoldApplierUseCase.class);
    private final HoldApplierConsumer consumer = new HoldApplierConsumer(mapper, useCase);

    private HoldCommand command() {
        return new HoldCommand("hold-1", "case-1", HoldCommand.PLACE, List.of("c-1"), null, null, null, "alice");
    }

    private String envelopeJson(HoldCommand command, String correlationId, String actor) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                UUID.randomUUID(), "HOLD_COMMAND", 1, Instant.now(), command.holdId(), correlationId, actor, command));
    }

    @Test
    void deserializesAndDelegatesToUseCase() throws Exception {
        HoldCommand command = command();

        consumer.onCommand(envelopeJson(command, "corr-1", "alice"));

        verify(useCase).apply(eq(command));
    }

    @Test
    void seedsAndClearsCorrelationIdAndActor() throws Exception {
        String[] seen = new String[2];
        doAnswer(inv -> {
            seen[0] = CorrelationId.current();
            seen[1] = Actor.current();
            return null;
        }).when(useCase).apply(any());

        consumer.onCommand(envelopeJson(command(), "corr-99", "bob"));

        assertThat(seen[0]).isEqualTo("corr-99");
        assertThat(seen[1]).isEqualTo("bob");
        assertThat(Actor.current()).isEqualTo("system");
    }

    @Test
    void clearsCorrelationIdAndActorEvenWhenUseCaseThrows() throws Exception {
        doAnswer(inv -> { throw new RuntimeException("archive down"); }).when(useCase).apply(any());

        assertThatThrownBy(() -> consumer.onCommand(envelopeJson(command(), "corr-1", "alice")))
                .isInstanceOf(RuntimeException.class);

        assertThat(Actor.current()).isEqualTo("system");
    }

    @Test
    void malformedJsonThrowsSerializationException() {
        assertThatThrownBy(() -> consumer.onCommand("not json"))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("hold.commands.v1");

        verify(useCase, never()).apply(any());
    }
}
