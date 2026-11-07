package com.smarsh.discoveryhub.audit.domain.model;

import java.time.Instant;

/**
 * Filter for {@code GET /api/audit} (FR-7.4, execution-plan M5 Day 3): any
 * combination of case, actor, action, target type, and time window, plus
 * page/size. All fields optional — nulls mean "no constraint".
 */
public record AuditQuery(
        String caseId,
        String actor,
        String action,
        String targetType,
        Instant from,
        Instant to,
        Integer page,
        Integer size) {

    /** Default page size when only {@code page} is supplied; hard cap either way. */
    public static final int MAX_SIZE = 500;

    public int effectiveSize() {
        int candidate = size == null ? MAX_SIZE : size;
        return Math.max(1, Math.min(candidate, MAX_SIZE));
    }

    public int effectivePage() {
        return page == null || page < 0 ? 0 : page;
    }
}
