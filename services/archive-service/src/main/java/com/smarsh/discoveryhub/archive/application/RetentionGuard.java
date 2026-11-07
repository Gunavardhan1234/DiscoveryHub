package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.archive.domain.model.HeldItemException;
import com.smarsh.discoveryhub.archive.domain.model.MessageNotFoundException;
import com.smarsh.discoveryhub.archive.domain.port.MessageDeletionEventPublisher;
import com.smarsh.discoveryhub.archive.domain.port.MessageDeletionPort;
import com.smarsh.discoveryhub.archive.infrastructure.persistence.MessageDocument;

import java.util.Set;

/**
 * Protection <strong>Proxy</strong> in front of message deletion
 * (FR-4.2/FR-4.6, execution-plan Day 2 M4). Every delete goes through
 * {@link #delete}: the guard reads the message, rejects with
 * {@link HeldItemException} (→ {@code 409 MESSAGE_ON_HOLD} + holds list) while
 * the hold set is non-empty, and only then delegates to the store's
 * {@code deleteOne({_id, holds:{$size:0}})} — the defence-in-depth second
 * check that makes a hold applied <em>between</em> read and delete still win.
 *
 * <p>The hold <em>set</em> is the source of truth ({@code holds.size > 0}),
 * never a lone boolean, so overlapping holds keep a message protected until
 * the last one is released (FR-4.5). The Day-3 disposition executor routes
 * through this same guard so scheduled retention can never delete held mail.
 *
 * <p>Every delete that actually removes a document also fires {@link
 * MessageDeletionEventPublisher#publishDeleted}, so search-service can
 * retract the message from Elasticsearch — without it, a deleted message
 * stays visible ("ghost") in search results (FR-5/FR-1.7).
 */
public class RetentionGuard {

    private static final MessageDeletionEventPublisher NO_OP = (id, type, reason) -> { };

    private final MessageDeletionPort store;
    private final MessageDeletionEventPublisher events;

    public RetentionGuard(MessageDeletionPort store) {
        this(store, NO_OP);
    }

    public RetentionGuard(MessageDeletionPort store, MessageDeletionEventPublisher events) {
        this.store = store;
        this.events = events == null ? NO_OP : events;
    }

    /** Rejects a delete while the message's hold set is non-empty. */
    public void assertDeletable(MessageDocument message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        if (!message.getHolds().isEmpty()) {
            throw new HeldItemException(message.getHolds());
        }
    }

    /** Hold-guarded delete of a single message, attributed as a manual delete. */
    public void delete(String messageId) {
        delete(messageId, "MANUAL");
    }

    /**
     * Hold-guarded delete of a single message.
     *
     * @param reason attribution for {@code message.deleted.v1} (e.g. {@code
     *               "MANUAL"} for the API endpoint, {@code "DISPOSITION"} for
     *               a retention pass) — informational only, never affects the
     *               hold check.
     * @throws MessageNotFoundException if the id does not exist
     * @throws HeldItemException        if the message is protected by holds
     */
    public void delete(String messageId, String reason) {
        MessageDocument message = store.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException(messageId));
        assertDeletable(message);
        if (store.deleteIfUnheld(messageId)) {
            events.publishDeleted(messageId, message.getType(), reason);
            return;
        }
        // Defence in depth: the guarded delete matched nothing. Either a hold
        // landed between read and delete (reject), or the message was deleted
        // concurrently (the goal is met — idempotent success).
        Set<String> holds = store.findById(messageId)
                .map(MessageDocument::getHolds)
                .orElse(Set.of());
        if (!holds.isEmpty()) {
            throw new HeldItemException(holds);
        }
    }
}
