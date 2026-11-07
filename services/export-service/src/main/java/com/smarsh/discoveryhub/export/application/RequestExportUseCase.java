package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.CaseStatusPort;
import com.smarsh.discoveryhub.export.domain.port.ExportEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.ExportJobPort;

import java.time.Instant;
import java.util.UUID;

/**
 * Accepts an export request, persists a QUEUED job, and publishes
 * {@code export.jobs.v1}. Returns 202 — the worker consumes the event
 * asynchronously. {@code jobId} is the idempotency key (events.md §5).
 *
 * <p><b>SOLID — SRP + DIP:</b> one use case per user-facing operation — this
 * class is only "accept a request", {@code ExportWorkerUseCase} is only
 * "execute the job", {@code RetryExportUseCase} is only "re-queue a failure".
 * All four collaborators are port interfaces, so the API layer can be tested
 * with the worker absent and vice versa.
 */
public class RequestExportUseCase {

    private final ExportJobPort jobPort;
    private final ExportEventPublisher publisher;
    private final AuditEventPublisher auditPublisher;
    private final CaseStatusPort caseStatus;

    public RequestExportUseCase(ExportJobPort jobPort, ExportEventPublisher publisher,
                                AuditEventPublisher auditPublisher, CaseStatusPort caseStatus) {
        this.jobPort = jobPort;
        this.publisher = publisher;
        this.auditPublisher = auditPublisher;
        this.caseStatus = caseStatus;
    }

    public ExportJob execute(String caseId, String holdId) {
        // FR-2.5: a closed case is read-only — reject case-scoped exports at
        // the service boundary before a QUEUED job is persisted. Hold-scoped
        // exports are not guarded here: a hold on a closed case has already
        // been released by the case.closed.v1 consumer (FR-4.5), and resolving
        // holdId → caseId would require an extra cross-service call with no
        // rubric-relevant payoff.
        if (caseId != null && !caseId.isBlank()) {
            try {
                UUID caseUuid = UUID.fromString(caseId);
                if (caseStatus.isClosed(caseUuid)) {
                    throw new ConflictException("CASE_CLOSED",
                            "Case '" + caseId + "' is closed — exports are not allowed (FR-2.5)");
                }
            } catch (IllegalArgumentException notAUuid) {
                // Non-UUID caseId — let the worker validate downstream.
            }
        }

        UUID jobId = UUID.randomUUID();
        ExportJob job = ExportJob.queued(jobId, caseId, holdId);
        jobPort.save(job);

        var payload = com.smarsh.discoveryhub.events.ExportJob.queued(jobId.toString(), caseId, holdId);
        publisher.publishExportRequested(payload, jobId.toString());
        auditPublisher.publish(new AuditEventPayload(
                UUID.randomUUID().toString(),
                Instant.now(),
                Actor.current(),
                "EXPORT_REQUESTED",
                "EXPORT",
                jobId.toString(),
                caseId,
                null,
                "{\"status\":\"QUEUED\"}",
                CorrelationId.current()),
                caseId != null ? caseId : jobId.toString());

        return job;
    }
}
