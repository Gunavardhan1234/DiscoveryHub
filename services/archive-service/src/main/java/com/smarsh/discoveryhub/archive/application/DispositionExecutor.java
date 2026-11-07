package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.archive.domain.model.HeldItemException;
import com.smarsh.discoveryhub.archive.domain.model.MessageNotFoundException;
import com.smarsh.discoveryhub.archive.domain.port.AttachmentCleaner;
import com.smarsh.discoveryhub.archive.domain.port.DispositionStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Disposition executor (FR-5, execution-plan Day 3 M4): pages retention-expired
 * messages per communication type and deletes the eligible ones. Every delete
 * is routed through the {@link RetentionGuard} protection proxy, so a held
 * message is <em>counted as skipped, never deleted</em> — including the race
 * where a hold lands mid-run (the guard's {@code deleteIfUnheld} defence).
 * Attachment blobs of deleted messages are handed to the
 * {@link AttachmentCleaner}.
 *
 * <p>Paging advances an ascending {@code _id} frontier, so deleting while
 * iterating cannot loop or skip; a page full of held messages still moves the
 * frontier forward. Pure application logic over ports — unit-testable with the
 * same fakes as the guard tests.
 *
 * <p>Invoked via the internal HTTP endpoint by hold-service's orchestrator,
 * which owns the schedule, the run ledger ({@code disposition_runs}) and the
 * {@code audit.events.v1} emission.
 */
public class DispositionExecutor {

    private static final Logger log = LoggerFactory.getLogger(DispositionExecutor.class);

    /** Cutoff per communication type: dispose messages with {@code sentAt < cutoff}. */
    public record TypeCutoff(String commType, Instant cutoff) {
    }

    public record Result(long scanned, long deleted, long skippedOnHold, long errors,
                         Map<String, Long> deletedByType) {
    }

    private final DispositionStorePort store;
    private final RetentionGuard guard;
    private final AttachmentCleaner attachments;
    private final int pageSize;

    public DispositionExecutor(DispositionStorePort store, RetentionGuard guard,
                               AttachmentCleaner attachments, int pageSize) {
        this.store = store;
        this.guard = guard;
        this.attachments = attachments;
        this.pageSize = Math.max(1, pageSize);
    }

    public Result run(List<TypeCutoff> cutoffs) {
        long scanned = 0, deleted = 0, skippedOnHold = 0, errors = 0;
        Map<String, Long> deletedByType = new LinkedHashMap<>();
        for (TypeCutoff typeCutoff : cutoffs == null ? List.<TypeCutoff>of() : cutoffs) {
            String afterId = null;
            while (true) {
                List<DispositionStorePort.Candidate> page =
                        store.findExpired(typeCutoff.commType(), typeCutoff.cutoff(), afterId, pageSize);
                if (page.isEmpty()) {
                    break;
                }
                for (DispositionStorePort.Candidate candidate : page) {
                    scanned++;
                    if (candidate.onHold()) {
                        skippedOnHold++;
                        continue;
                    }
                    try {
                        guard.delete(candidate.messageId(), "DISPOSITION");
                        deleted++;
                        deletedByType.merge(typeCutoff.commType(), 1L, Long::sum);
                        if (!candidate.attachmentS3Keys().isEmpty()) {
                            attachments.deleteAll(candidate.attachmentS3Keys());
                        }
                    } catch (HeldItemException e) {
                        skippedOnHold++;
                    } catch (MessageNotFoundException e) {
                        log.debug("Message {} already gone; nothing to dispose", candidate.messageId());
                    } catch (Exception e) {
                        errors++;
                        log.warn("Failed to dispose message {}", candidate.messageId(), e);
                    }
                }
                afterId = page.get(page.size() - 1).messageId();
            }
        }
        log.info("Disposition pass: scanned={}, deleted={}, skippedOnHold={}, errors={}, byType={}",
                scanned, deleted, skippedOnHold, errors, deletedByType);
        return new Result(scanned, deleted, skippedOnHold, errors, deletedByType);
    }
}
