package com.smarsh.discoveryhub.search.domain.service;

import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import com.smarsh.discoveryhub.search.domain.port.RemoteCountsPort;
import com.smarsh.discoveryhub.search.domain.port.SearchPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Aggregates the four dashboard counts (FR-8.1): total messages (an ES count
 * the search-service owns) plus active cases, active holds, and exports
 * completed (fetched from the owning services via {@link RemoteCountsPort}).
 *
 * <p>Each remote count degrades to {@code 0} on failure (NFR-2), so a down
 * service never blanks the whole dashboard — only its own card. Total messages
 * comes from the local ES index so it is always available while search-service
 * is up.
 *
 * <p><b>SOLID — SRP + OOP composition:</b> one job — aggregate four counts —
 * by composing two ports ({@code SearchPort} for the local count,
 * {@code RemoteCountsPort} for the remote ones). Because both are
 * abstractions, the degradation rules and the aggregation logic are unit
 * tested with no ES and no HTTP.
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final SearchPort searchPort;
    private final RemoteCountsPort remoteCountsPort;

    public DashboardService(SearchPort searchPort, RemoteCountsPort remoteCountsPort) {
        this.searchPort = searchPort;
        this.remoteCountsPort = remoteCountsPort;
    }

    public DashboardCounts counts() {
        long totalMessages = safeMessageCount();
        long activeCases = safeCount(RemoteCountsPort::activeCases, "activeCases");
        long activeHolds = safeCount(RemoteCountsPort::activeHolds, "activeHolds");
        long exportsCompleted = safeCount(RemoteCountsPort::exportsCompleted, "exportsCompleted");
        return new DashboardCounts(totalMessages, activeCases, activeHolds, exportsCompleted);
    }

    private long safeMessageCount() {
        try {
            return searchPort.count(QuerySpec.builder().build());
        } catch (RuntimeException ex) {
            log.warn("ES message count failed — degrading totalMessages to 0: {}", ex.getMessage());
            return 0;
        }
    }

    private long safeCount(CountFn fn, String label) {
        try {
            return fn.apply(remoteCountsPort);
        } catch (RuntimeException ex) {
            log.warn("dashboard {} failed — degrading to 0: {}", label, ex.getMessage());
            return 0;
        }
    }

    @FunctionalInterface
    private interface CountFn {
        long apply(RemoteCountsPort port);
    }

    public record DashboardCounts(long totalMessages, long activeCases, long activeHolds, long exportsCompleted) {}
}
