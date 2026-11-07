package com.smarsh.discoveryhub.archive.domain.port;

import java.time.Instant;
import java.util.List;

/**
 * Outbound port for hold application against the message store (FR-4). The
 * archive hold-applier ({@code archive-hold-applier} group) consumes
 * {@code hold.commands.v1}, resolves the matching message ids by scope, and
 * applies {@code $addToSet}/{$pull} to each message's {@code holds} set via
 * this port. The set — not a lone boolean — is the source of truth for
 * "is this message held": {@code onHold} is derived as {@code holds.size > 0}
 * so that releasing one of several overlapping holds leaves the message
 * protected (FR-4.5).
 *
 * <p>This is a separate port from {@link MessageStorePort} so the hold-applier
 * is isolated from the ingest/durability pipeline and can be built/tested
 * independently.
 */
public interface HoldStorePort {

    /** Message ids matching a hold scope (custodians + date range + terms). */
    List<String> findIdsByScope(List<String> custodians, Instant from, Instant to, String terms);

    /** Message ids currently covered by a given hold (used for RELEASE). */
    List<String> findIdsByHold(String holdId);

    /**
     * Adds a hold to a message's hold set ({@code $addToSet}); returns the
     * resulting {@code onHold} (always {@code true} when the message exists).
     */
    boolean addHold(String messageId, String holdId);

    /**
     * Removes a hold from a message's hold set ({@code $pull}) and re-derives
     * {@code onHold = holds.size > 0}; returns the resulting {@code onHold} so
     * the applier can report whether the message is still covered by an
     * overlapping hold (FR-4.5).
     */
    boolean removeHold(String messageId, String holdId);
}
