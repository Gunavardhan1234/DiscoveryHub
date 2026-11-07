package com.smarsh.discoveryhub.events;

import java.time.Instant;
import java.util.List;

/**
 * Payload for {@code hold.commands.v1} — the command to place or release a
 * legal hold. Produced by {@code hold-service} via its transactional outbox;
 * consumed by {@code archive-service}'s hold-applier ({@code archive-hold-applier}
 * group). {@code action} is {@code PLACE} or {@code RELEASE}; {@code RELEASE}
 * carries the same {@code holdId} and the scope fields are ignored (the applier
 * already knows the scope from the hold row / the message's hold set).
 *
 * @see com.smarsh.discoveryhub.events.Topics#HOLD_COMMANDS_V1
 */
public record HoldCommand(
        String holdId,
        String caseId,
        String action,
        List<String> custodians,
        Instant from,
        Instant to,
        String terms,
        String placedBy) {

    public static final String PLACE = "PLACE";
    public static final String RELEASE = "RELEASE";

    public boolean isPlace() { return PLACE.equals(action); }
    public boolean isRelease() { return RELEASE.equals(action); }
}
