package com.smarsh.discoveryhub.search.domain.port;

/**
 * Outbound port for the platform counts the dashboard aggregates from the other
 * services (FR-8.1). Each method returns a count or {@code 0} when the owning
 * service is unreachable or has not yet exposed a count endpoint — graceful
 * degradation so the dashboard never fully fails (NFR-2, Day-4 resiliency demo).
 *
 * <p>Hexagonal boundary: the dashboard service depends on this port, not on an
 * HTTP client. The adapter is the only place that knows the downstream URLs.
 *
 * <p><b>SOLID — DIP + ISP:</b> three small methods, one per downstream card —
 * a new count is a new method, not a fatter parameter object, and the
 * dashboard logic depends on the abstraction while the HTTP adapter stays
 * substitutable (e.g. by a stub returning canned counts in tests).
 */
public interface RemoteCountsPort {

    /** Active (non-CLOSED) cases, from case-service. */
    long activeCases();

    /** Active (non-RELEASED) holds, from hold-service. */
    long activeHolds();

    /** Completed export jobs, from export-service. */
    long exportsCompleted();
}
