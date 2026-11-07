package com.smarsh.discoveryhub.archive.domain.port;

import java.time.Instant;
import java.util.List;

/**
 * Port for paging retention-expired disposition candidates (FR-5). A candidate
 * is any message of {@code type} sent before {@code cutoff} — held or not; the
 * executor routes every candidate through the {@code RetentionGuard} so held
 * messages are counted as skipped, never deleted.
 */
public interface DispositionStorePort {

    /**
     * One retention-expired message: its id, whether its hold set is non-empty,
     * and the S3 keys of its attachments (for blob cleanup after delete).
     */
    record Candidate(String messageId, boolean onHold, List<String> attachmentS3Keys) {
        public Candidate {
            attachmentS3Keys = attachmentS3Keys == null ? List.of() : List.copyOf(attachmentS3Keys);
        }
    }

    /**
     * Pages candidates of {@code commType} with {@code sentAt < cutoff}, ids
     * greater than {@code afterId} (exclusive, ascending id order), up to
     * {@code limit} rows. {@code afterId == null} starts from the beginning.
     */
    List<Candidate> findExpired(String commType, Instant cutoff, String afterId, int limit);
}
