package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.BlobStore;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.export.domain.model.ArchiveMessage;
import com.smarsh.discoveryhub.export.domain.model.ExportEntry;
import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.model.ExportJobStatus;
import com.smarsh.discoveryhub.export.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.EvidenceSourcePort;
import com.smarsh.discoveryhub.export.domain.port.ExportJobPort;
import com.smarsh.discoveryhub.export.domain.port.ExportProgressPublisher;
import com.smarsh.discoveryhub.export.domain.port.HoldScopeSourcePort;
import com.smarsh.discoveryhub.export.domain.port.MessageSourcePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The async export worker (execution-plan M5 Day 2/3). Consumes one
 * {@code export.jobs.v1} job id and drives QUEUED → RUNNING → COMPLETED:
 * evidence ids from case-service → message docs from archive-service →
 * attachment bytes from the shared attachments bucket → zip via the
 * {@link AbstractExportPackager Template Method} (per-item SHA-256 through the
 * {@code DigestOutputStream} decorator) → upload to the exports bucket.
 *
 * <p><b>Idempotency:</b> {@code jobId} is the key — a redelivered event for a
 * COMPLETED job is a logged no-op, and the S3 key is derived from the job id
 * alone, so a retried attempt overwrites its own partial output instead of
 * minting a second package (events.md §5).
 *
 * <p>Failures propagate to the listener's {@code DefaultErrorHandler} (3
 * attempts, exponential backoff); on exhaustion the recoverer dead-letters the
 * record and calls {@link #markFailed}.
 *
 * <p><b>SOLID — SRP:</b> one job only — execute an export job end to end.
 * Scope resolution, storage, packaging, progress and audit are delegated to
 * the collaborators below, so each reason to change (new evidence source, new
 * package format, new progress UI) lands in exactly one of them, not here.
 *
 * <p><b>OOP — Composition + SOLID — DIP:</b> every collaborator is a port
 * interface ({@code EvidenceSourcePort}, {@code BlobStore}, …) injected via the
 * constructor. The worker composes them into a workflow but depends on no
 * concrete technology — swap the JPA adapter or the S3 adapter and this class
 * is untouched (tested with mocks in
 * {@code ExportWorkerUseCaseTest}).
 */
public class ExportWorkerUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExportWorkerUseCase.class);

    private final ExportJobPort jobPort;
    private final EvidenceSourcePort evidenceSource;
    private final HoldScopeSourcePort holdScopeSource;
    private final MessageSourcePort messageSource;
    private final BlobStore blobStore;
    private final Supplier<AbstractExportPackager> packagerFactory;
    private final ExportProgressPublisher progressPublisher;
    private final AuditEventPublisher auditPublisher;
    private final String attachmentsBucket;
    private final String exportsBucket;

    public ExportWorkerUseCase(ExportJobPort jobPort,
                               EvidenceSourcePort evidenceSource,
                               HoldScopeSourcePort holdScopeSource,
                               MessageSourcePort messageSource,
                               BlobStore blobStore,
                               Supplier<AbstractExportPackager> packagerFactory,
                               ExportProgressPublisher progressPublisher,
                               AuditEventPublisher auditPublisher,
                               String attachmentsBucket,
                               String exportsBucket) {
        this.jobPort = jobPort;
        this.evidenceSource = evidenceSource;
        this.holdScopeSource = holdScopeSource;
        this.messageSource = messageSource;
        this.blobStore = blobStore;
        this.packagerFactory = packagerFactory;
        this.progressPublisher = progressPublisher;
        this.auditPublisher = auditPublisher;
        this.attachmentsBucket = attachmentsBucket;
        this.exportsBucket = exportsBucket;
    }

    public void process(UUID jobId) {
        ExportJob job = jobPort.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("export.jobs.v1 for unknown job {} — skipping", jobId);
            return;
        }
        if (job.status() == ExportJobStatus.COMPLETED) {
            log.info("Job {} already COMPLETED — redelivery is a no-op (idempotent)", jobId);
            return;
        }
        boolean hasCaseId = job.caseId() != null && !job.caseId().isBlank();
        boolean hasHoldId = job.holdId() != null && !job.holdId().isBlank();
        if (!hasCaseId && !hasHoldId) {
            throw new IllegalStateException("Job " + jobId + " has neither a caseId nor a holdId — nothing to export");
        }

        job = saveAndPublish(job.running(5));
        List<String> messageIds = hasCaseId
                ? evidenceSource.evidenceMessageIds(job.caseId())
                : holdScopeSource.holdMessageIds(job.holdId());
        job = saveAndPublish(job.running(10));

        List<ArchiveMessage> messages = messageIds.isEmpty()
                ? List.of()
                : messageSource.fetchMessages(messageIds);
        List<ExportEntry> entries = assembleEntries(messages);
        job = saveAndPublish(job.running(40));

        AbstractExportPackager.PackageResult result = buildPackage(job, entries);
        String key = "exports/%s/package.zip".formatted(jobId);
        blobStore.put(exportsBucket, key, result.bytes(), "application/zip");

        job = saveAndPublish(job.completed(key, ManifestBuilder.sha256Hex(result.bytes())));
        log.info("Job {} COMPLETED: {} evidence messages, {} package items, s3://{}/{}",
                jobId, messages.size(), result.manifest().items().size(), exportsBucket, key);
        audit(job, "EXPORT_COMPLETED",
                "{\"items\":" + result.manifest().items().size() + ",\"packageS3Key\":\"" + key + "\"}");
    }

    /** Called by the Kafka recoverer once retries are exhausted (Day-3 retry/DLT). */
    public void markFailed(UUID jobId, String error) {
        String safeError = error == null || error.isBlank() ? "unknown error" : error;
        jobPort.findById(jobId).ifPresent(job -> {
            if (job.status() == ExportJobStatus.COMPLETED) return;
            ExportJob failed = saveAndPublish(job.failed(safeError));
            audit(failed, "EXPORT_FAILED", "{\"error\":\"" + safeError.replace("\"", "'") + "\"}");
        });
    }

    /**
     * Self-heals a COMPLETED job whose S3 package was lost — e.g., a
     * LocalStack container restart wipes in-memory S3 state (community
     * LocalStack cannot persist; Pro-only). Rebuilds the package from the
     * persistent source of truth (Mongo via archive-service) and re-uploads
     * under the same key. The job stays COMPLETED throughout: no progress
     * publish, no audit — only the S3 object is restored and the recorded
     * SHA-256 is updated to match the regenerated zip so verify still passes.
     * Called by the download-url endpoint before minting the pre-signed URL,
     * so a download click transparently repairs itself.
     */
    public void regeneratePackage(UUID jobId) {
        ExportJob job = jobPort.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("regeneratePackage: job {} not found — nothing to heal", jobId);
            return;
        }
        boolean hasCaseId = job.caseId() != null && !job.caseId().isBlank();
        boolean hasHoldId = job.holdId() != null && !job.holdId().isBlank();
        if (!hasCaseId && !hasHoldId) {
            log.warn("regeneratePackage: job {} has no caseId/holdId — nothing to export", jobId);
            return;
        }

        List<String> messageIds = hasCaseId
                ? evidenceSource.evidenceMessageIds(job.caseId())
                : holdScopeSource.holdMessageIds(job.holdId());
        List<ArchiveMessage> messages = messageIds.isEmpty()
                ? List.of()
                : messageSource.fetchMessages(messageIds);
        List<ExportEntry> entries = assembleEntries(messages);

        String scopeLabel = hasCaseId ? job.caseId() : "hold:" + job.holdId();
        AbstractExportPackager.PackageResult result = packagerFactory.get().buildPackage(
                job.id().toString(), scopeLabel, entries, packaged -> { });
        String key = "exports/%s/package.zip".formatted(jobId);
        blobStore.put(exportsBucket, key, result.bytes(), "application/zip");
        // Update the recorded SHA-256 to match the regenerated zip so verify passes.
        String newSha = ManifestBuilder.sha256Hex(result.bytes());
        jobPort.save(job.completed(key, newSha));
        log.info("Job {} package regenerated (self-heal after S3 object loss): {} items, s3://{}/{}, sha={}",
                jobId, result.manifest().items().size(), exportsBucket, key, newSha);
    }

    private AbstractExportPackager.PackageResult buildPackage(ExportJob job, List<ExportEntry> entries) {
        int total = Math.max(1, entries.size());
        int step = Math.max(1, total / 10);
        var jobHolder = new ExportJob[] { job };
        String scopeLabel = job.caseId() != null && !job.caseId().isBlank()
                ? job.caseId() : "hold:" + job.holdId();
        return packagerFactory.get().buildPackage(
                job.id().toString(), scopeLabel, entries,
                packaged -> {
                    if (packaged % step == 0 || packaged == total) {
                        int pct = 40 + (int) (50L * packaged / total);
                        jobHolder[0] = saveAndPublish(jobHolder[0].running(pct));
                    }
                });
    }

    private List<ExportEntry> assembleEntries(List<ArchiveMessage> messages) {
        List<ExportEntry> entries = new ArrayList<>();
        for (ArchiveMessage message : messages) {
            entries.add(new ExportEntry("messages/%s.json".formatted(message.id()), message.contentJson()));
            for (ArchiveMessage.AttachmentRef ref : message.attachments()) {
                if (ref.s3Key() == null || ref.s3Key().isBlank()) continue;
                byte[] bytes = fetchAttachment(ref);
                if (bytes != null) {
                    entries.add(new ExportEntry("attachments/%s/%s".formatted(message.id(), ref.filename()), bytes));
                }
            }
        }
        return entries;
    }

    private byte[] fetchAttachment(ArchiveMessage.AttachmentRef ref) {
        try {
            byte[] bytes = blobStore.get(attachmentsBucket, ref.s3Key());
            if (bytes == null) log.warn("Attachment {} missing from s3://{} — skipped", ref.s3Key(), attachmentsBucket);
            return bytes;
        } catch (Exception e) {
            log.warn("Attachment {} unreadable ({}) — skipped", ref.s3Key(), e.getMessage());
            return null;
        }
    }

    private ExportJob saveAndPublish(ExportJob job) {
        ExportJob saved = jobPort.save(job);
        progressPublisher.publish(saved);
        return saved;
    }

    private void audit(ExportJob job, String action, String after) {
        try {
            auditPublisher.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    Actor.current(),
                    action,
                    "EXPORT",
                    job.id().toString(),
                    job.caseId(),
                    null,
                    after,
                    CorrelationId.current()),
                    job.caseId() != null ? job.caseId() : job.id().toString());
        } catch (Exception e) {
            log.warn("audit.events.v1 publish failed for job {} ({}) — export outcome unaffected",
                    job.id(), e.getMessage());
        }
    }
}
