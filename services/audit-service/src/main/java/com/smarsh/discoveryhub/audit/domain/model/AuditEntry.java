package com.smarsh.discoveryhub.audit.domain.model;

import java.time.Instant;

/**
 * Domain model for an audit entry (FR-7, architecture §2.6). Pure Java — no
 * Mongo annotations. The infrastructure adapter maps this to/from
 * {@code AuditEventDocument}. The hash chain is computed over these fields:
 * {@code hash = SHA-256(prevHash + canonical(entry))}.
 *
 * <p><b>OOP — Encapsulation:</b> immutable {@code record} — once an entry is
 * appended it cannot be edited in memory, mirroring the append-only store.
 * {@code prevHash}/{@code hash} travel with the value, so chain integrity is
 * checkable from the data alone, no hidden mutable state.
 */
public record AuditEntry(
        String eventId,
        Instant occurredAt,
        String actor,
        String action,
        String targetType,
        String targetId,
        String caseId,
        Object before,
        Object after,
        String correlationId,
        String prevHash,
        String hash) {

    public static final String GENESIS = "GENESIS";
}
