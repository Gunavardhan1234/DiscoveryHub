package com.smarsh.discoveryhub.caseapp.domain.port;

import java.util.UUID;

/**
 * Outbound port resolving the number of messages currently held for a case
 * (FR-4.4: the Case Detail screen must show "the total count of held items
 * per case"). hold-service owns hold state; case-service asks it for the
 * per-case summary rather than duplicating hold data — services never share
 * databases (architecture §1).
 */
// Dependency Inversion / Hexagonal "port": case-service depends on this interface, not on
// hold-service's REST API shape directly, so the caller (CaseDetailService) is swappable/testable
// with a mock and never touches HTTP client code — the RestHoldSummaryAdapter is the only class
// that knows hold-service is even reached over HTTP (NFR-1: services own their data, talk via APIs).
public interface HoldSummaryPort {

    /**
     * @return the number of messages currently protected by the case's
     *         non-released holds, or {@code 0} if hold-service is
     *         unreachable (fail-open — matches {@link
     *         com.smarsh.discoveryhub.caseapp.service.CaseDetailService}'s
     *         existing "stub to zero" behavior for cross-service summaries
     *         it can't resolve).
     */
    long heldCount(UUID caseId);
}
