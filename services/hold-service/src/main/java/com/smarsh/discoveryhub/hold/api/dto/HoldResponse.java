package com.smarsh.discoveryhub.hold.api.dto;

import com.smarsh.discoveryhub.hold.infrastructure.persistence.HoldEntity;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Hold status response (FR-4.3). {@code status} is the hold lifecycle state;
 * {@code heldCount} is the number of messages matched by the scope (set by the
 * archive hold-applier on {@code completed:true}); {@code appliedCount} is the
 * running count of messages that have had the hold applied; {@code progressPct}
 * is the derived percentage.
 */
public record HoldResponse(
        String holdId,
        String caseId,
        String status,
        List<String> custodians,
        Instant from,
        Instant to,
        String terms,
        long heldCount,
        long appliedCount,
        int progressPct,
        String placedBy,
        Instant placedAt,
        Instant releasedAt) {

    public static HoldResponse from(HoldEntity h) {
        int pct = h.heldCount <= 0 ? 0 : (int) Math.min(100, (h.appliedCount * 100) / h.heldCount);
        return new HoldResponse(
                h.id.toString(),
                h.caseId.toString(),
                h.status.name(),
                h.scopeCustodians == null ? List.of() : Arrays.asList(h.scopeCustodians),
                h.scopeFrom == null ? null : h.scopeFrom.toInstant(),
                h.scopeTo == null ? null : h.scopeTo.toInstant(),
                h.scopeTerms,
                h.heldCount,
                h.appliedCount,
                pct,
                h.placedBy,
                h.placedAt == null ? null : h.placedAt.toInstant(),
                h.releasedAt == null ? null : h.releasedAt.toInstant());
    }
}
