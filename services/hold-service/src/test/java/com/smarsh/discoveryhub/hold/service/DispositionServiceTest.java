package com.smarsh.discoveryhub.hold.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.hold.api.dto.DispositionRunResponse;
import com.smarsh.discoveryhub.hold.domain.disposition.DispositionExecutorClient;
import com.smarsh.discoveryhub.hold.domain.disposition.DispositionLock;
import com.smarsh.discoveryhub.hold.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.hold.domain.retention.RetentionService;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.DispositionRunEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.DispositionRunRepository;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.RetentionPolicyEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.RetentionPolicyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Disposition orchestrator tests (FR-5, Day 3 M4): cutoffs come from the
 * retention {@code Strategy}, the ledger row records the executor's counts,
 * {@code audit.events.v1{DISPOSITION_RUN}} is emitted, and the advisory lock
 * makes concurrent passes impossible — a manual trigger gets 409, a scheduled
 * tick skips quietly.
 */
class DispositionServiceTest {

    private final DispositionRunRepository runs = mock(DispositionRunRepository.class);
    private final RetentionPolicyRepository policies = mock(RetentionPolicyRepository.class);
    private final DispositionExecutorClient executor = mock(DispositionExecutorClient.class);
    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);
    private final FakeLock lock = new FakeLock();
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    /** Runs the background pass synchronously on the calling thread so assertions can run right after {@code service.run(...)}. */
    private final Executor sameThreadExecutor = Runnable::run;
    private final DispositionService service = new DispositionService(
            runs, policies, new RetentionService(policies, "PT168H"), executor, lock, audit, mapper, sameThreadExecutor);

    @Test
    void manualRunRecordsLedgerAndEmitsAudit() {
        when(runs.save(any(DispositionRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policies.findAllByOrderByCommTypeAsc()).thenReturn(List.of(policy("EMAIL", "PT2M")));
        when(policies.findById("EMAIL")).thenReturn(Optional.of(policy("EMAIL", "PT2M")));
        when(executor.run(anyList())).thenReturn(
                new DispositionExecutorClient.ExecutorResult(10, 7, 3, 0, Map.of("EMAIL", 7L)));

        DispositionRunResponse response = service.run(DispositionService.Trigger.MANUAL, "operator").orElseThrow();

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.scanned()).isEqualTo(10);
        assertThat(response.deleted()).isEqualTo(7);
        assertThat(response.skippedOnHold()).isEqualTo(3);
        assertThat(response.deletedByType()).containsEntry("EMAIL", 7L);
        assertThat(response.finishedAt()).isNotNull();
        assertThat(lock.released.get()).as("lock released after run").isTrue();

        ArgumentCaptor<AuditEventPayload> auditCaptor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(auditCaptor.capture(), anyString());
        assertThat(auditCaptor.getValue().action()).isEqualTo("DISPOSITION_RUN");
        assertThat(auditCaptor.getValue().after()).contains("\"deleted\":7");
    }

    @Test
    void cutoffsComeFromRetentionStrategy() {
        when(runs.save(any(DispositionRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policies.findAllByOrderByCommTypeAsc()).thenReturn(List.of(policy("EMAIL", "PT2M")));
        when(policies.findById("EMAIL")).thenReturn(Optional.of(policy("EMAIL", "PT2M")));
        when(executor.run(anyList())).thenReturn(
                new DispositionExecutorClient.ExecutorResult(0, 0, 0, 0, Map.of()));

        Instant before = Instant.now();
        service.run(DispositionService.Trigger.MANUAL, "operator");
        Instant after = Instant.now();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DispositionExecutorClient.TypeCutoff>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(executor).run(captor.capture());
        DispositionExecutorClient.TypeCutoff cutoff = captor.getValue().get(0);
        assertThat(cutoff.commType()).isEqualTo("EMAIL");
        assertThat(cutoff.cutoff())
                .isAfterOrEqualTo(before.minus(Duration.ofMinutes(2)).minusSeconds(5))
                .isBeforeOrEqualTo(after.minus(Duration.ofMinutes(2)).plusSeconds(5));
    }

    @Test
    void manualRunWhileLockedIsRejectedWith409() {
        lock.available = false;

        assertThatThrownBy(() -> service.run(DispositionService.Trigger.MANUAL, "operator"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already running");
        verify(runs, never()).save(any());
    }

    @Test
    void scheduledTickWhileLockedSkipsQuietly() {
        lock.available = false;

        Optional<DispositionRunResponse> result = service.run(DispositionService.Trigger.SCHEDULED, "scheduler");

        assertThat(result).isEmpty();
        verify(runs, never()).save(any());
    }

    @Test
    void executorFailureRecordsFailedRunAndStillAudits() {
        when(runs.save(any(DispositionRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policies.findAllByOrderByCommTypeAsc()).thenReturn(List.of(policy("EMAIL", "PT2M")));
        when(policies.findById("EMAIL")).thenReturn(Optional.of(policy("EMAIL", "PT2M")));
        when(executor.run(anyList())).thenThrow(new IllegalStateException("archive unreachable"));

        DispositionRunResponse response = service.run(DispositionService.Trigger.SCHEDULED, "scheduler").orElseThrow();

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.finishedAt()).isNotNull();
        assertThat(lock.released.get()).isTrue();
        verify(audit).publish(any(AuditEventPayload.class), anyString());
    }

    private static RetentionPolicyEntity policy(String commType, String duration) {
        RetentionPolicyEntity entity = new RetentionPolicyEntity();
        entity.commType = commType;
        entity.duration = duration;
        return entity;
    }

    /** Lock fake: configurable availability, records release. */
    private static final class FakeLock implements DispositionLock {
        boolean available = true;
        final AtomicBoolean released = new AtomicBoolean(false);

        @Override
        public Optional<LockHandle> tryAcquire() {
            return available ? Optional.of(() -> released.set(true)) : Optional.empty();
        }
    }
}
