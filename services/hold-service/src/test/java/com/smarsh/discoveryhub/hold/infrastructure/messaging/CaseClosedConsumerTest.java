package com.smarsh.discoveryhub.hold.infrastructure.messaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarsh.discoveryhub.events.CaseClosed;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.hold.service.HoldService;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link CaseClosedConsumer} unit tests (execution-plan Day 2 M4: "Release hold
 * on case.closed.v1"). The consumer must accept both the raw {@link CaseClosed}
 * payload (what case-service writes to its outbox today) and an
 * {@code EventEnvelope}-wrapped payload (what a poller like hold-service's own
 * publishes), and must skip — not crash on — events with a missing/invalid
 * caseId so one bad record cannot poison the {@code hold-case-closed} group.
 */
class CaseClosedConsumerTest {

    private static final UUID CASE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final HoldService holdService = mock(HoldService.class);
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final CaseClosedConsumer consumer = new CaseClosedConsumer(mapper, holdService);

    @Test
    void rawPayloadReleasesAllHoldsForCase() throws Exception {
        String value = mapper.writeValueAsString(new CaseClosed(CASE_ID.toString(), Instant.now(), "alice"));

        consumer.onCaseClosed(value);

        verify(holdService).releaseAllForCase(CASE_ID);
    }

    @Test
    void envelopeWrappedPayloadReleasesAllHoldsForCase() throws Exception {
        EventEnvelope<CaseClosed> envelope = new EventEnvelope<>(
                UUID.randomUUID(), "CASE_CLOSED", 1, Instant.now(), CASE_ID.toString(),
                "corr-1", "alice", new CaseClosed(CASE_ID.toString(), Instant.now(), "alice"));
        String value = mapper.writeValueAsString(envelope);

        consumer.onCaseClosed(value);

        verify(holdService).releaseAllForCase(CASE_ID);
    }

    @Test
    void missingOrInvalidCaseIdIsSkipped() throws Exception {
        consumer.onCaseClosed(mapper.writeValueAsString(new CaseClosed(null, Instant.now(), "alice")));
        consumer.onCaseClosed(mapper.writeValueAsString(new CaseClosed("not-a-uuid", Instant.now(), "alice")));

        verify(holdService, never()).releaseAllForCase(any());
    }

    @Test
    void unparsableJsonThrowsSerializationException() {
        assertThatThrownBy(() -> consumer.onCaseClosed("{not json"))
                .isInstanceOf(SerializationException.class);
    }
}
