package com.smarsh.discoveryhub.search.domain.model;

import java.util.Map;

/**
 * Aggregated counts for the dashboard and hold-scope preview (FR-3 / architecture §2.2).
 * <p>{@code total} is the hit count for the (optionally filtered) scope;
 * the breakdowns let the UI render filter badges and the hold wizard show
 * "this scope currently matches N messages" before the user commits.
 *
 * <p><b>OOP — immutable value object:</b> a {@code record} carrying pre-computed
 * aggregates rather than raw documents.
 *
 * <p><b>SOLID — SRP + the reason aggregation belongs server-side:</b> these
 * counts are produced by one ES aggregation request with {@code size(0)} — no
 * hits are fetched at all. The alternative (download the result set and count
 * in the client, or in Java) would transfer thousands of documents to compute
 * five numbers. Keeping the aggregation in the query layer is also what makes
 * the hold wizard's "this scope matches N messages" preview cheap enough to
 * show before the user commits to a corpus-wide hold (FR-4).
 *
 * @param total             total documents matching the (optional) filter
 * @param byType            count per communication type (EMAIL / CHAT)
 * @param byCustodian       top-N custodians by document count
 * @param onHoldCount       documents with at least one active hold
 * @param hasAttachmentCount documents with at least one attachment
 */
public record FacetResult(
        long total,
        Map<String, Long> byType,
        Map<String, Long> byCustodian,
        long onHoldCount,
        long hasAttachmentCount) {
}
