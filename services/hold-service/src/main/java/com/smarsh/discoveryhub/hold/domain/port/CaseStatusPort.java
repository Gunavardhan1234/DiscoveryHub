package com.smarsh.discoveryhub.hold.domain.port;

import java.util.UUID;

/**
 * Reads a case's lifecycle status from case-service over HTTP. hold-service
 * owns no foreign key to {@code cases} (D-003 — the 7-service split): a hold
 * references its {@code caseId} by value, so FR-2.5 ("a closed case is
 * read-only — no new holds") is enforced here by asking case-service for the
 * status at placement time, <em>before</em> any hold row is written.
 *
 * <p>The durable FR-2.5 invariant is still the {@code case.closed.v1} event,
 * which hold-service consumes to release every non-terminal hold on a case
 * (FR-4.5). This port is the synchronous fast-path guard that rejects a new
 * hold placed on an <em>already-closed</em> case — without it, closing a case
 * and then placing a hold would succeed until the (already-fired) event could
 * reconcile it, which it never would.
 *
 * <p>Fail-open contract: if case-service is unreachable, implementations
 * return {@code false} (allow the hold) rather than coupling hold placement
 * availability to case-service availability (NFR-1). The event remains the
 * safety net. A 404 (case does not exist) is <em>not</em> fail-open — it
 * surfaces as a {@code NotFoundException} so the caller gets a 404, not a
 * silently placed hold on a non-existent case.
 */
public interface CaseStatusPort {

    /** True if the case exists and is {@code CLOSED}; false if it exists and is open. */
    boolean isClosed(UUID caseId);
}
