package com.smarsh.discoveryhub.search.domain.port;

import com.smarsh.discoveryhub.search.domain.model.FacetResult;
import com.smarsh.discoveryhub.search.domain.model.IndexedMessage;
import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import com.smarsh.discoveryhub.search.domain.model.SearchResult;

import java.util.List;

/**
 * Hexagonal outbound port isolating the domain from the Elasticsearch client.
 * The only place that knows ES exists is the {@code infrastructure/es} adapter.
 *
 * <p><b>SOLID — DIP (the headline principle of this service):</b> the domain
 * declares this interface and {@code infrastructure/es/ElasticsearchSearchPort}
 * implements it, so the dependency arrow points <em>inward</em>. The high-level
 * policy ({@code SearchService}) owns the abstraction; the low-level detail (the
 * ES transport) depends on it. Concretely: no domain class imports
 * {@code co.elastic.clients.*}, which is why {@code SearchServiceTest} and
 * {@code DashboardServiceTest} run with no Elasticsearch and no Spring context.
 *
 * <p><b>SOLID — LSP:</b> any implementation that honours this contract is
 * substitutable. Production uses the ES adapter; unit tests substitute
 * in-memory fakes, and neither caller behaves differently.
 *
 * <p><b>SOLID — ISP, with an honest caveat worth knowing for a viva:</b> this
 * port is deliberately <em>coarse</em> — it mixes query operations
 * ({@code search}/{@code count}/{@code scopeIds}/{@code facets}), write
 * operations ({@code index}/{@code indexBatch}/{@code applyHold}/
 * {@code deleteByIds}) and one admin operation
 * ({@code dropAndRecreateIndex}) in a single interface, so the Kafka indexers
 * technically see query methods they never call. archive-service splits the
 * equivalent surface into separate {@code MessageStorePort} /
 * {@code MessageReadPort} / {@code MessageDeletionPort}. The trade-off taken
 * here: there is exactly one adapter over exactly one ES index, so a single
 * cohesive "index" abstraction keeps the wiring simple. If a second
 * implementation ever appeared (an OpenSearch adapter, or a read-only
 * replica), splitting this along read/write lines would be the first refactor.
 */
public interface SearchPort {

    /** Full-text + structured filter query, with highlighting and paging. */
    SearchResult search(QuerySpec spec);

    /** Cheap count of the scope described by {@code spec} (no hits fetched). */
    long count(QuerySpec spec);

    /** All matching ids for bulk "add to case" (PIT + search_after streaming, uncapped). */
    List<String> scopeIds(QuerySpec spec);

    /** Aggregated counts for dashboard and hold-scope preview (FR-3 / architecture §2.2). */
    FacetResult facets(QuerySpec spec);

    /** Upsert one message projection. Idempotent under Kafka redelivery (upsert by id). */
    void index(IndexedMessage message);

    /** Bulk upsert a batch of message projections (flushed on 500 docs or 2 s). */
    void indexBatch(List<IndexedMessage> messages);

    /** Keep the {@code onHold} flag (and hold-id set) searchable. Reference-counted. */
    void applyHold(String holdId, List<String> messageIds, boolean onHold);

    /** Retract documents that were actually deleted from the source of truth
     *  (FR-5/FR-1.7); ignores ids that are already absent. */
    void deleteByIds(List<String> messageIds);

    /** Drop and recreate the {@code messages} index from the contract mapping. */
    void dropAndRecreateIndex();
}
