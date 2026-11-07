package com.smarsh.discoveryhub.audit.application;

import com.smarsh.discoveryhub.audit.domain.model.AuditEntry;
import com.smarsh.discoveryhub.audit.domain.port.AuditStorePort;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditConsumeUseCaseTest {

    private final AuditStorePort store = mock(AuditStorePort.class);
    private final HashChain hashChain = new HashChain();
    private final AuditConsumeUseCase useCase = new AuditConsumeUseCase(store, hashChain);

    @Test
    void chainsOffLatestHashForCase() {
        when(store.latestHashForCase("case-42")).thenReturn("prev-hash-abc");

        var payload = new AuditEventPayload(
                "e1", Instant.parse("2026-09-07T15:10:00Z"), "investigator@demo",
                "HOLD_PLACED", "HOLD", "h-1001", "case-42",
                null, "{\"holdId\":\"h-1001\"}", "corr-1");

        useCase.accept(payload);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(store).append(captor.capture());
        AuditEntry stored = captor.getValue();

        assertThat(stored.prevHash()).isEqualTo("prev-hash-abc");
        assertThat(stored.hash()).isNotNull();
        assertThat(hashChain.verify(stored)).isTrue();
        assertThat(stored.action()).isEqualTo("HOLD_PLACED");
        assertThat(stored.caseId()).isEqualTo("case-42");
    }

    @Test
    void truncatesOccurredAtToMillisSoHashSurvivesMongoRoundTrip() {
        // MongoDB's BSON Date only stores millisecond precision. If the hash were
        // computed over an Instant.now()-precision timestamp, re-verifying after a
        // round trip through Mongo (which truncates to millis) would always fail.
        when(store.latestHashForCase("case-77")).thenReturn(AuditEntry.GENESIS);
        Instant subMillisPrecision = Instant.parse("2026-09-09T11:17:07.090123456Z");

        var payload = new AuditEventPayload(
                "e3", subMillisPrecision, "system", "EXPORT_REQUESTED", "EXPORT", "job-1",
                "case-77", null, "{\"status\":\"QUEUED\"}", "corr-3");

        useCase.accept(payload);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(store).append(captor.capture());
        AuditEntry stored = captor.getValue();

        assertThat(stored.occurredAt()).isEqualTo(Instant.parse("2026-09-09T11:17:07.090Z"));

        // Simulate a Mongo round trip: it can only ever hand back millisecond
        // precision, so re-verifying with that value must still succeed.
        AuditEntry roundTripped = new AuditEntry(stored.eventId(), stored.occurredAt().truncatedTo(
                java.time.temporal.ChronoUnit.MILLIS), stored.actor(), stored.action(), stored.targetType(),
                stored.targetId(), stored.caseId(), stored.before(), stored.after(), stored.correlationId(),
                stored.prevHash(), stored.hash());
        assertThat(hashChain.verify(roundTripped)).isTrue();
    }

    @Test
    void usesGenesisForFirstEntryInCase() {
        when(store.latestHashForCase("case-99")).thenReturn(AuditEntry.GENESIS);

        var payload = new AuditEventPayload(
                "e2", Instant.now(), "system", "CASE_CREATED", "CASE", "case-99",
                "case-99", null, null, "corr-2");

        useCase.accept(payload);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(store).append(captor.capture());
        assertThat(captor.getValue().prevHash()).isEqualTo(AuditEntry.GENESIS);
    }
}
