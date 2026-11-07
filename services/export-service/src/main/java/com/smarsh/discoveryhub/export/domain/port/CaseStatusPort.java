package com.smarsh.discoveryhub.export.domain.port;

import java.util.UUID;

/**
 * Reads a case's lifecycle status from case-service over HTTP. export-service
 * references a case by {@code caseId} value (no cross-service FK), so FR-2.5
 * ("a closed case is read-only — no exports") is enforced here at request
 * time, before a QUEUED job is persisted. See hold-service's
 * {@code CaseStatusPort} for the same pattern.
 *
 * <p>Fail-open contract: if case-service is unreachable, implementations
 * return {@code false} (allow the request) — the export worker re-checks
 * case-service for the evidence list downstream and fails the job on a real
 * outage, so a transient hiccup at request time does not need to block. A 404
 * (case does not exist) surfaces as a {@code NotFoundException} → 404.
 */
public interface CaseStatusPort {

    /** True if the case exists and is {@code CLOSED}; false if it exists and is open. */
    boolean isClosed(UUID caseId);
}
