package com.smarsh.discoveryhub.hold.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.hold.api.dto.DispositionRunResponse;
import com.smarsh.discoveryhub.hold.domain.disposition.DispositionExecutorClient;
import com.smarsh.discoveryhub.hold.domain.disposition.DispositionLock;
import com.smarsh.discoveryhub.hold.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.hold.domain.retention.RetentionService;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.DispositionRunEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.DispositionRunRepository;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.RetentionPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Disposition orchestrator (FR-5, execution-plan Day 3 M4). One pass:
 * <ol>
 *   <li>take the cluster-wide {@link DispositionLock} (skip/reject if a pass is
 *       already running — only one runner across replicas),</li>
 *   <li>compute one cutoff per configured retention policy (retention
 *       {@code Strategy}: {@code cutoff = now - retention(type)}),</li>
 *   <li>call archive-service's executor, which pages expired messages and
 *       deletes only unheld ones via the {@code RetentionGuard} proxy,</li>
 *   <li>record the {@code disposition_runs} ledger row
 *       ({@code RUNNING → COMPLETED/FAILED} with deleted/skippedOnHold/errors),</li>
 *   <li>emit {@code audit.events.v1{action:DISPOSITION_RUN}}.</li>
 * </ol>
 * The scan-and-delete pass (step 3) runs on a background {@link Executor}: a
 * corpus of thousands of messages can take well beyond a request timeout to
 * page through and delete one at a time, so {@link #run} returns as soon as
 * the {@code RUNNING} ledger row is saved — the Disposition screen polls
 * {@link #list()}/{@link #latest()} for completion instead of blocking on one
 * HTTP call. No {@code @Transactional} spans the pass — each ledger save
 * commits on its own so the call to archive never holds a database
 * transaction open.
 */
@Service
public class DispositionService {

    private static final Logger log = LoggerFactory.getLogger(DispositionService.class);

    public enum Trigger { MANUAL, SCHEDULED }

    private final DispositionRunRepository runs;
    private final RetentionPolicyRepository policies;
    private final RetentionService retention;
    private final DispositionExecutorClient executor;
    private final DispositionLock lock;
    private final AuditEventPublisher audit;
    private final ObjectMapper mapper;
    private final Executor dispositionExecutor;

    public DispositionService(DispositionRunRepository runs,
                              RetentionPolicyRepository policies,
                              RetentionService retention,
                              DispositionExecutorClient executor,
                              DispositionLock lock,
                              AuditEventPublisher audit,
                              ObjectMapper mapper,
                              @Qualifier("dispositionExecutor") Executor dispositionExecutor) {
        this.runs = runs;
        this.policies = policies;
        this.retention = retention;
        this.executor = executor;
        this.lock = lock;
        this.audit = audit;
        this.mapper = mapper;
        this.dispositionExecutor = dispositionExecutor;
    }

    /**
     * Kicks off one disposition pass and returns immediately with the
     * {@code RUNNING} ledger row. A {@code MANUAL} trigger while another pass
     * is running is rejected with {@code 409 DISPOSITION_ALREADY_RUNNING}; a
     * {@code SCHEDULED} tick simply skips (the running pass is doing the work).
     * The lock is released on the background thread once the pass finishes.
     */
    public Optional<DispositionRunResponse> run(Trigger trigger, String actor) {
        Optional<DispositionLock.LockHandle> handle = lock.tryAcquire();
        if (handle.isEmpty()) {
            if (trigger == Trigger.MANUAL) {
                throw new ConflictException("DISPOSITION_ALREADY_RUNNING",
                        "A disposition pass is already running");
            }
            log.debug("Disposition tick skipped: another runner holds the lock");
            return Optional.empty();
        }

        DispositionRunEntity run = new DispositionRunEntity();
        run.id = UUID.randomUUID();
        run.startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        run.status = "RUNNING";
        runs.save(run);

        DispositionLock.LockHandle lockHandle = handle.get();
        dispositionExecutor.execute(() -> {
            try {
                finishRun(run, actor);
            } finally {
                lockHandle.close();
            }
        });

        return Optional.of(DispositionRunResponse.from(run, mapper));
    }

    public List<DispositionRunResponse> list() {
        return runs.findAllByOrderByStartedAtDesc().stream()
                .map(e -> DispositionRunResponse.from(e, mapper))
                .toList();
    }

    public DispositionRunResponse latest() {
        return runs.findFirstByOrderByStartedAtDesc()
                .map(e -> DispositionRunResponse.from(e, mapper))
                .orElseThrow(() -> new NotFoundException("No disposition run recorded yet"));
    }

    /** Does the actual scan-and-delete pass and finalizes the ledger row. Runs on {@link #dispositionExecutor}. */
    private void finishRun(DispositionRunEntity run, String actor) {
        Instant now = Instant.now();
        List<DispositionExecutorClient.TypeCutoff> cutoffs = policies.findAllByOrderByCommTypeAsc().stream()
                .map(p -> new DispositionExecutorClient.TypeCutoff(
                        p.commType, now.minus(retention.retentionFor(p.commType))))
                .toList();
        try {
            DispositionExecutorClient.ExecutorResult result = executor.run(cutoffs);
            run.scanned = result.scanned();
            run.deleted = result.deleted();
            run.skippedOnHold = result.skippedOnHold();
            run.errors = result.errors();
            run.deletedByType = serialize(result.deletedByType());
            run.status = result.errors() > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        } catch (Exception e) {
            log.warn("Disposition run {} failed", run.id, e);
            run.status = "FAILED";
        }
        run.finishedAt = OffsetDateTime.now(ZoneOffset.UTC);
        runs.save(run);
        publishAudit(run, actor);
    }

    private void publishAudit(DispositionRunEntity run, String actor) {
        try {
            String after = mapper.writeValueAsString(new AuditCounts(
                    run.status, run.scanned, run.deleted, run.skippedOnHold, run.errors));
            audit.publish(new AuditEventPayload(
                    run.id.toString(),
                    Instant.now(),
                    actor,
                    "DISPOSITION_RUN",
                    "DISPOSITION_RUN",
                    run.id.toString(),
                    null,
                    null,
                    after,
                    CorrelationId.current()), run.id.toString());
        } catch (Exception e) {
            log.warn("Failed to publish DISPOSITION_RUN audit event for {}", run.id, e);
        }
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private record AuditCounts(String status, long scanned, long deleted, long skippedOnHold, long errors) {
    }
}
