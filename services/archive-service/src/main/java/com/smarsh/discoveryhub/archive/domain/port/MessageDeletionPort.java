package com.smarsh.discoveryhub.archive.domain.port;

import com.smarsh.discoveryhub.archive.infrastructure.persistence.MessageDocument;

import java.util.Optional;

/**
 * Port for hold-guarded message deletion (FR-4.2/FR-4.6). The
 * {@link com.smarsh.discoveryhub.archive.application.RetentionGuard} protection
 * proxy reads the message to check its hold set, then delegates the actual
 * delete to {@link #deleteIfUnheld} — which must only remove the document when
 * the hold set is empty ({@code deleteOne({_id, holds:{$size:0}})}), the
 * defence-in-depth second guard against a hold landing between read and delete.
 */
public interface MessageDeletionPort {

    Optional<MessageDocument> findById(String messageId);

    /**
     * Deletes the message only if its hold set is empty.
     *
     * @return {@code true} if a document was deleted
     */
    boolean deleteIfUnheld(String messageId);
}
