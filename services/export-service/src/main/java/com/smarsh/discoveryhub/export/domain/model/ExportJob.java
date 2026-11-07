package com.smarsh.discoveryhub.export.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain model for an export job (architecture §2.5). Pure Java — no JPA/Mongo
 * annotations. The infrastructure layer maps this to {@code ExportJobEntity}.
 *
 * <p>{@code packageSha256} is the SHA-256 of the finished zip, recorded at
 * completion time so {@code POST /exports/{id}/verify} can prove the stored
 * package has not been altered since it was built (FR-6).
 *
 * <p><b>OOP — Encapsulation:</b> a Java {@code record} with no setters: the
 * job's state is captured at construction and can never be mutated into an
 * invalid combination from outside. Every lifecycle move ({@link #running},
 * {@link #completed}, {@link #failed}, {@link #requeue}) is a <i>method on the
 * type itself</i> that returns a new valid instance — illegal transitions are
 * unrepresentable rather than guarded by checks scattered across callers.
 */
public record ExportJob(
        UUID id,
        String caseId,
        String holdId,
        ExportJobStatus status,
        int progressPct,
        String packageS3Key,
        String packageSha256,
        String error,
        Instant createdAt,
        Instant updatedAt) {

    public static ExportJob queued(UUID id, String caseId, String holdId) {
        Instant now = Instant.now();
        return new ExportJob(id, caseId, holdId, ExportJobStatus.QUEUED, 0, null, null, null, now, now);
    }

    /** RUNNING with the given progress; clears any error from a prior attempt (retry-safe). */
    public ExportJob running(int progressPct) {
        return new ExportJob(id, caseId, holdId, ExportJobStatus.RUNNING, progressPct,
                packageS3Key, packageSha256, null, createdAt, Instant.now());
    }

    public ExportJob completed(String packageS3Key, String packageSha256) {
        return new ExportJob(id, caseId, holdId, ExportJobStatus.COMPLETED, 100,
                packageS3Key, packageSha256, null, createdAt, Instant.now());
    }

    public ExportJob failed(String error) {
        return new ExportJob(id, caseId, holdId, ExportJobStatus.FAILED, progressPct,
                packageS3Key, packageSha256, error, createdAt, Instant.now());
    }

    /**
     * Re-queues a FAILED job for another attempt (FR-6.6). Same {@code id}, so
     * the worker's S3 key ({@code exports/{jobId}/package.zip}) and idempotency
     * checks are untouched — a retry overwrites its own prior partial output
     * rather than minting a second package. Progress and any recorded error
     * are reset since this is a fresh attempt.
     */
    public ExportJob requeue() {
        return new ExportJob(id, caseId, holdId, ExportJobStatus.QUEUED, 0,
                packageS3Key, packageSha256, null, createdAt, Instant.now());
    }
}
