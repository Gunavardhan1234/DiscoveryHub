package com.smarsh.discoveryhub.events;

import java.util.List;

/**
 * Payload for {@code hold.applied.v1} — emitted by {@code archive-service}'s
 * hold-applier after each batch of messages has had a hold
 * {@code $addToSet}/{$pull}-ed onto its {@code holds} set. Consumed by
 * {@code hold-service} (the {@code hold-tracker} group) to update
 * {@code heldCount}/{@code appliedCount} and flip status to {@code ACTIVE} or
 * {@code RELEASED} once {@code completed} is {@code true}; also consumed by
 * {@code search-service} to mirror the hold state into ES.
 *
 * <p>{@code onHold} is the per-message derived flag ({@code holds.size > 0});
 * for a {@code RELEASE} batch it is {@code true} when the message is still
 * covered by an overlapping hold and {@code false} once fully released — the
 * hold <em>set</em> is the source of truth, never a lone boolean.
 *
 * @see com.smarsh.discoveryhub.events.Topics#HOLD_APPLIED_V1
 */
public record HoldApplied(
        String holdId,
        String caseId,
        String action,
        List<String> messageIds,
        boolean onHold,
        boolean completed,
        int progressPct) {

    public static final String PLACE = "PLACE";
    public static final String RELEASE = "RELEASE";
}
