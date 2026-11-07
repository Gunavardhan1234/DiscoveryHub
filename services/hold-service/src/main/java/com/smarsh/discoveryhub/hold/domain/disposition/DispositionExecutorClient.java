package com.smarsh.discoveryhub.hold.domain.disposition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Port to archive-service's internal disposition executor
 * ({@code POST /internal/disposition/run}). hold-service computes the per-type
 * cutoffs from its retention {@code Strategy} configuration; archive-service
 * owns the message store and performs the guard-routed deletes, returning the
 * counts for the {@code disposition_runs} ledger.
 */
public interface DispositionExecutorClient {

    record TypeCutoff(String commType, Instant cutoff) {
    }

    record ExecutorResult(long scanned, long deleted, long skippedOnHold, long errors,
                          Map<String, Long> deletedByType) {
        public ExecutorResult {
            deletedByType = deletedByType == null ? Map.of() : Map.copyOf(deletedByType);
        }
    }

    ExecutorResult run(List<TypeCutoff> cutoffs);
}
