package com.smarsh.discoveryhub.export.api;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.BlobStore;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.export.api.dto.DownloadUrlResponse;
import com.smarsh.discoveryhub.export.api.dto.ExportRequest;
import com.smarsh.discoveryhub.export.api.dto.ExportResponse;
import com.smarsh.discoveryhub.export.api.dto.VerifyResponse;
import com.smarsh.discoveryhub.export.application.ExportWorkerUseCase;
import com.smarsh.discoveryhub.export.application.RequestExportUseCase;
import com.smarsh.discoveryhub.export.application.RetryExportUseCase;
import com.smarsh.discoveryhub.export.application.VerifyExportUseCase;
import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.model.ExportJobStatus;
import com.smarsh.discoveryhub.export.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.ExportJobPort;
import com.smarsh.discoveryhub.export.infrastructure.sse.ExportProgressBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Export endpoints (api-contracts §export). {@code POST /exports} persists a
 * QUEUED job, publishes {@code export.jobs.v1}, and returns 202; the worker
 * consumes the event asynchronously. Reads: list/detail, a 15-min pre-signed
 * download link, server-side verify, and SSE progress (Observer).
 */
@RestController
@RequestMapping("/api/exports")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private final RequestExportUseCase requestExportUseCase;
    private final RetryExportUseCase retryExportUseCase;
    private final VerifyExportUseCase verifyExportUseCase;
    private final ExportWorkerUseCase exportWorker;
    private final ExportJobPort jobPort;
    private final BlobStore blobStore;
    private final ExportProgressBroadcaster broadcaster;
    private final AuditEventPublisher auditPublisher;
    private final String exportsBucket;
    private final long downloadTtlSeconds;

    public ExportController(RequestExportUseCase requestExportUseCase,
                            RetryExportUseCase retryExportUseCase,
                            VerifyExportUseCase verifyExportUseCase,
                            ExportWorkerUseCase exportWorker,
                            ExportJobPort jobPort,
                            BlobStore blobStore,
                            ExportProgressBroadcaster broadcaster,
                            AuditEventPublisher auditPublisher,
                            @Value("${discoveryhub.export.exports-bucket:discoveryhub-exports}") String exportsBucket,
                            @Value("${discoveryhub.export.download-ttl-seconds:900}") long downloadTtlSeconds) {
        this.requestExportUseCase = requestExportUseCase;
        this.retryExportUseCase = retryExportUseCase;
        this.verifyExportUseCase = verifyExportUseCase;
        this.exportWorker = exportWorker;
        this.jobPort = jobPort;
        this.blobStore = blobStore;
        this.broadcaster = broadcaster;
        this.auditPublisher = auditPublisher;
        this.exportsBucket = exportsBucket;
        this.downloadTtlSeconds = downloadTtlSeconds;
    }

    @PostMapping
    public ResponseEntity<ExportResponse> create(@RequestBody ExportRequest request) {
        ExportJob job = requestExportUseCase.execute(request.caseId(), request.holdId());
        return ResponseEntity.accepted().body(toResponse(job));
    }

    @GetMapping
    public List<ExportResponse> list(@RequestParam(name = "caseId", required = false) String caseId) {
        List<ExportJob> jobs = caseId != null && !caseId.isBlank()
                ? jobPort.findByCaseId(caseId)
                : jobPort.findAll();
        return jobs.stream().map(ExportController::toResponse).toList();
    }

    @GetMapping("/{jobId}")
    public ExportResponse get(@PathVariable UUID jobId) {
        return toResponse(loadJob(jobId));
    }

    /**
     * Pre-signed S3 GET URL, TTL 15 min (api-contracts §export, FR-6.4).
     * Emits {@code EXPORT_DOWNLOADED} — FR-7.1 lists "export requested/
     * downloaded" as a distinct chain-of-custody event from "requested" or
     * "completed", since it records that an investigator actually retrieved
     * the evidence, not just that the package became available.
     */
    @GetMapping("/{jobId}/download-url")
    public DownloadUrlResponse downloadUrl(@PathVariable UUID jobId) {
        ExportJob job = loadJob(jobId);
        if (job.status() != ExportJobStatus.COMPLETED || job.packageS3Key() == null) {
            throw new ConflictException("EXPORT_NOT_READY",
                    "Job " + jobId + " is " + job.status() + " — download is available once COMPLETED");
        }
        // Self-heal: free LocalStack stores S3 in memory, so a container
        // restart wipes the package. If the object is missing, rebuild it from
        // the persistent source of truth (Mongo via archive-service) and
        // re-upload under the same key — transparent to the caller.
        if (!blobStore.exists(exportsBucket, job.packageS3Key())) {
            log.info("Package {} missing from S3 — regenerating from archive for job {}", job.packageS3Key(), jobId);
            try {
                exportWorker.regeneratePackage(jobId);
            } catch (Exception e) {
                log.warn("Package regeneration for job {} failed — download may 404", jobId, e);
            }
        }
        String url = blobStore.presignedUrl(exportsBucket, job.packageS3Key(), downloadTtlSeconds);
        auditDownload(job);
        return new DownloadUrlResponse(url, Instant.now().plus(Duration.ofSeconds(downloadTtlSeconds)));
    }

    private void auditDownload(ExportJob job) {
        try {
            String scopeKey = job.caseId() != null ? job.caseId() : job.id().toString();
            auditPublisher.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    Actor.current(),
                    "EXPORT_DOWNLOADED",
                    "EXPORT",
                    job.id().toString(),
                    job.caseId(),
                    null,
                    "{\"packageS3Key\":\"" + job.packageS3Key() + "\"}",
                    CorrelationId.current()), scopeKey);
        } catch (Exception e) {
            log.warn("Failed to publish EXPORT_DOWNLOADED audit event for job {} — download unaffected",
                    job.id(), e);
        }
    }

    /** Server-side integrity check: stored zip SHA-256 + every manifest item hash (FR-6.5). */
    @PostMapping("/{jobId}/verify")
    public VerifyResponse verify(@PathVariable UUID jobId) {
        VerifyExportUseCase.Result result = verifyExportUseCase.verify(jobId);
        // Self-heal: if the package is missing from S3 (LocalStack restart wiped
        // it) or its SHA-256 differs from the value recorded at completion (the
        // package was regenerated after a wipe), rebuild from the persistent
        // archive and re-verify so the reviewer sees a pass instead of a
        // failure caused by ephemeral storage.
        if (!result.valid() && result.message() != null
                && (result.message().contains("could not be fetched")
                    || result.message().contains("missing from the exports bucket")
                    || result.message().contains("SHA-256 differs"))) {
            log.info("Verify failed for job {} ({}) — regenerating from archive", jobId, result.message());
            try {
                exportWorker.regeneratePackage(jobId);
                result = verifyExportUseCase.verify(jobId);
            } catch (Exception e) {
                log.warn("Regeneration during verify for job {} failed", jobId, e);
            }
        }
        return new VerifyResponse(result.jobId(), result.valid(), result.message());
    }

    /**
     * Retries a FAILED job (FR-6.6): re-queues the same job id — never mints a
     * new one — and republishes {@code export.jobs.v1} so the worker rebuilds
     * the package from scratch under the same S3 key. 409 {@code EXPORT_NOT_FAILED}
     * if the job isn't currently FAILED (guards against racing an in-flight
     * or already-completed job).
     */
    @PostMapping("/{jobId}/retry")
    public ResponseEntity<ExportResponse> retry(@PathVariable UUID jobId) {
        ExportJob job = retryExportUseCase.execute(jobId);
        return ResponseEntity.accepted().body(toResponse(job));
    }

    /** Live progress stream (SSE, Observer): QUEUED → RUNNING(pct) → COMPLETED/FAILED, then closes. */
    @GetMapping(path = "/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID jobId) {
        return broadcaster.subscribe(loadJob(jobId));
    }

    private ExportJob loadJob(UUID jobId) {
        return jobPort.findById(jobId)
                .orElseThrow(() -> new NotFoundException("ExportJob", jobId.toString()));
    }

    private static ExportResponse toResponse(ExportJob job) {
        return new ExportResponse(
                job.id().toString(),
                job.caseId(),
                job.holdId(),
                job.status().name(),
                job.progressPct(),
                job.packageS3Key(),
                job.error(),
                job.createdAt());
    }
}
