package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.model.ExportJobStatus;
import com.smarsh.discoveryhub.export.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.ExportEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.ExportJobPort;
import com.smarsh.discoveryhub.export.domain.port.ExportProgressPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/**
 * Retries a FAILED export job (FR-6.6): a failed job is not retryable at all
 * through {@code POST /api/exports} today, since that mints a brand-new
 * {@code jobId}. This use case re-queues the <em>same</em> job id — so the
 * worker's S3 key ({@code exports/{jobId}/package.zip}) and its
 * COMPLETED-redelivery idempotency check (see {@link ExportWorkerUseCase#process})
 * stay intact — and republishes {@code export.jobs.v1} so the worker picks it
 * up again. Only jobs currently in {@code FAILED} are retryable; retrying a
 * job that is already QUEUED/RUNNING/COMPLETED is rejected so a second worker
 * pass never races the first.
 */
public class RetryExportUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetryExportUseCase.class);

    private final ExportJobPort jobPort;
    private final ExportEventPublisher eventPublisher;
    private final ExportProgressPublisher progressPublisher;
    private final AuditEventPublisher auditPublisher;

    public RetryExportUseCase(ExportJobPort jobPort, ExportEventPublisher eventPublisher,
                              ExportProgressPublisher progressPublisher, AuditEventPublisher auditPublisher) {
        this.jobPort = jobPort;
        this.eventPublisher = eventPublisher;
        this.progressPublisher = progressPublisher;
        this.auditPublisher = auditPublisher;
    }

    public ExportJob execute(UUID jobId) {
        ExportJob job = jobPort.findById(jobId)
                .orElseThrow(() -> new NotFoundException("ExportJob", jobId.toString()));
        if (job.status() != ExportJobStatus.FAILED) {
            throw new ConflictException("EXPORT_NOT_FAILED",
                    "Job " + jobId + " is " + job.status() + " — only a FAILED job can be retried");
        }

        ExportJob requeued = job.requeue();
        jobPort.save(requeued);
        progressPublisher.publish(requeued);

        var payload = com.smarsh.discoveryhub.events.ExportJob.queued(
                jobId.toString(), requeued.caseId(), requeued.holdId());
        eventPublisher.publishExportRequested(payload, jobId.toString());
        audit(job, requeued);
        log.info("Job {} re-queued for retry (was FAILED: {})", jobId, job.error());
        return requeued;
    }

    private void audit(ExportJob before, ExportJob after) {
        try {
            auditPublisher.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    Actor.current(),
                    "EXPORT_RETRIED",
                    "EXPORT",
                    after.id().toString(),
                    after.caseId(),
                    "{\"status\":\"FAILED\",\"error\":" + jsonString(before.error()) + "}",
                    "{\"status\":\"QUEUED\"}",
                    CorrelationId.current()),
                    after.caseId() != null ? after.caseId() : after.id().toString());
        } catch (Exception e) {
            log.warn("audit.events.v1 publish failed for retried job {} — retry unaffected",
                    after.id(), e);
        }
    }

    private static String jsonString(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "'") + "\"";
    }
}
