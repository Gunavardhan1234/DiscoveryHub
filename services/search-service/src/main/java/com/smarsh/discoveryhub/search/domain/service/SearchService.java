package com.smarsh.discoveryhub.search.domain.service;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.search.domain.model.FacetResult;
import com.smarsh.discoveryhub.search.domain.model.IndexedMessage;
import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import com.smarsh.discoveryhub.search.domain.model.SearchResult;
import com.smarsh.discoveryhub.search.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.search.domain.port.ReindexPort;
import com.smarsh.discoveryhub.search.domain.port.SearchPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Application service orchestrating search use cases. Keeps controllers thin
 * and the ES adapter behind the {@link SearchPort} boundary.
 *
 * <p><b>SOLID — SRP:</b> this class <em>orchestrates</em> and nothing else. It
 * holds no query-building logic (that is {@code EsQueryTranslator}), no
 * transport logic (that is {@code ElasticsearchSearchPort}) and no HTTP
 * concerns (those are the controllers). The clearest example is
 * {@link #reindex()}: the two-step <em>policy</em> — drop and recreate the
 * index, then replay the log — lives here, while both <em>mechanisms</em> live
 * behind ports.
 *
 * <p><b>SOLID — DIP:</b> all three collaborators are interfaces owned by the
 * domain ({@link SearchPort}, {@link ReindexPort}, {@link AuditEventPublisher}),
 * injected through the constructor. Nothing in this file knows that
 * Elasticsearch or Kafka exist, which is exactly why it is unit-testable with
 * three mocks and no infrastructure.
 *
 * <p><b>SOLID — OCP:</b> new filters and new sort modes are added to
 * {@code QuerySpec} + {@code EsQueryTranslator}; this class does not change,
 * because it only ever passes the spec through.
 *
 * <p><b>Resiliency note (NFR-2):</b> {@link #publishAudit} is deliberately
 * best-effort — a Kafka outage must never fail a user's search. The audit
 * event is published only <em>after</em> the search has actually returned, so
 * a failed query can never leave an audit record claiming it ran.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final SearchPort port;
    private final ReindexPort reindexPort;
    private final AuditEventPublisher audit;

    public SearchService(SearchPort port, ReindexPort reindexPort, AuditEventPublisher audit) {
        this.port = port;
        this.reindexPort = reindexPort;
        this.audit = audit;
    }

    /** Full-text + structured filter query (FR-3.1). Emits {@code SEARCH_EXECUTED} (FR-7.1). */
    public SearchResult search(QuerySpec spec) {
        SearchResult result = port.search(spec);
        publishAudit(spec, result.total());
        return result;
    }

    public long count(QuerySpec spec) {
        return port.count(spec);
    }

    public List<String> scopeIds(QuerySpec spec) {
        return port.scopeIds(spec);
    }

    public FacetResult facets(QuerySpec spec) {
        return port.facets(spec);
    }

    public void index(IndexedMessage message) {
        port.index(message);
    }

    public void indexBatch(List<IndexedMessage> messages) {
        port.indexBatch(messages);
    }

    public void applyHold(String holdId, List<String> messageIds, boolean onHold) {
        port.applyHold(holdId, messageIds, onHold);
    }

    /** Retracts deleted messages from the index (FR-5/FR-1.7 — see {@link
     *  com.smarsh.discoveryhub.search.infrastructure.kafka.MessageDeindexer}). */
    public void deleteByIds(List<String> messageIds) {
        port.deleteByIds(messageIds);
    }

    /**
     * Rebuild the entire ES index from the durable source of truth
     * (drop + recreate, then replay {@code comm.stored.v1} from offset 0).
     *
     * @return number of documents re-indexed
     */
    public long reindex() {
        port.dropAndRecreateIndex();
        return reindexPort.replayFromBeginning();
    }

    /**
     * Best-effort audit publish (FR-7.1: "search executed") — never fails the
     * search itself. Keyed by {@code caseId} when the query is scoped to a
     * case (contiguous per-case hash chain); otherwise by a fresh id, since an
     * un-scoped system-wide search has no case to chain against.
     */
    private void publishAudit(QuerySpec spec, long total) {
        try {
            String caseId = spec.caseId();
            String key = caseId != null && !caseId.isBlank() ? caseId : UUID.randomUUID().toString();
            audit.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    Actor.current(),
                    "SEARCH_EXECUTED",
                    "SEARCH",
                    key,
                    caseId,
                    null,
                    "{\"q\":\"" + (spec.q() == null ? "" : spec.q().replace("\"", "'")) + "\",\"resultCount\":" + total + "}",
                    CorrelationId.current()), key);
        } catch (Exception e) {
            log.warn("Failed to publish SEARCH_EXECUTED audit event", e);
        }
    }
}
