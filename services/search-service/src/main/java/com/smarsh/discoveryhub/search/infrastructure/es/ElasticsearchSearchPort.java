package com.smarsh.discoveryhub.search.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.JsonData;
import com.smarsh.discoveryhub.search.domain.SearchException;
import com.smarsh.discoveryhub.search.domain.model.FacetResult;
import com.smarsh.discoveryhub.search.domain.model.IndexedMessage;
import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import com.smarsh.discoveryhub.search.domain.model.SearchHit;
import com.smarsh.discoveryhub.search.domain.model.SearchResult;
import com.smarsh.discoveryhub.search.domain.port.SearchPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch adapter implementing {@link SearchPort}. The only component that
 * touches the ES client — keeping the transport behind the hexagonal boundary.
 *
 * <p><b>Design pattern — Adapter:</b> this is the textbook definition. It adapts
 * an incompatible third-party interface (the {@code ElasticsearchClient} and its
 * {@code SearchRequest}/{@code SearchResponse}/{@code BulkRequest} types) to the
 * interface the domain wants ({@link SearchPort}, which speaks only
 * {@code QuerySpec} / {@code SearchResult} / {@code IndexedMessage}). It is the
 * single place where {@code co.elastic.clients.*} appears in this service.
 *
 * <p><b>SOLID — DIP (the dependency is inverted here):</b> this class depends on
 * the domain's interface, not the other way round. That is what makes the ES
 * version, or Elasticsearch itself, a replaceable detail.
 *
 * <p><b>SOLID — LSP:</b> as a {@link SearchPort} implementation it must honour
 * the contract exactly — notably that {@code index}/{@code indexBatch} are
 * idempotent (upsert by {@code messageId}, so Kafka redelivery cannot
 * duplicate) and that {@code deleteByIds} tolerates ids that are already
 * absent. Callers rely on those guarantees without knowing which
 * implementation they hold.
 *
 * <p><b>SOLID — SRP, by delegation:</b> this class does transport and result
 * mapping only; it does <em>not</em> build queries. Query construction is
 * delegated to {@link EsQueryTranslator}, which is why the 13 query-shape
 * tests in {@code EsQueryTranslatorTest} need no Elasticsearch at all.
 *
 * <p><b>FR-4.5 note — reference-counted hold sets:</b> {@code applyHold} uses
 * the painless scripts above rather than a blind {@code onHold} overwrite, so
 * a message covered by two overlapping holds stays {@code onHold:true} when
 * only one is released. A boolean assignment here would silently break
 * overlapping-hold correctness in the projection.
 */
@Component
public class ElasticsearchSearchPort implements SearchPort {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchSearchPort.class);

    /** Painless scripts for reference-counted hold sets (architecture §2.3 / FR-4.5). */
    private static final String APPLY_HOLD_SCRIPT =
            "if (ctx._source.holds == null) { ctx._source.holds = new ArrayList(); } " +
            "if (!ctx._source.holds.contains(params.holdId)) { ctx._source.holds.add(params.holdId); } " +
            "ctx._source.onHold = true;";
    private static final String RELEASE_HOLD_SCRIPT =
            "if (ctx._source.holds != null) { " +
            "  ctx._source.holds.removeIf(h -> h.equals(params.holdId)); " +
            "  ctx._source.onHold = !ctx._source.holds.isEmpty(); " +
            "}";

    private final ElasticsearchClient client;
    private final EsQueryTranslator translator;
    private final ResourceLoader resourceLoader;
    private final String indexName;
    private final String mappingLocation;

    @Autowired
    public ElasticsearchSearchPort(ElasticsearchClient client, EsQueryTranslator translator,
                                   ResourceLoader resourceLoader,
                                   @Value("${discoveryhub.search.index-name:messages}") String indexName,
                                   @Value("${discoveryhub.search.index-mapping:classpath:es/messages-index.json}") String mappingLocation) {
        this.client = client;
        this.translator = translator;
        this.resourceLoader = resourceLoader;
        this.indexName = indexName;
        this.mappingLocation = mappingLocation;
    }

    /** Test-only constructor (no ResourceLoader — index management not needed). */
    public ElasticsearchSearchPort(ElasticsearchClient client, EsQueryTranslator translator, String indexName) {
        this(client, translator, null, indexName, null);
    }

    @Override
    public SearchResult search(QuerySpec spec) {
        SearchResponse<IndexedMessage> resp = run(() -> client.search(translator.toSearchRequest(spec), IndexedMessage.class));
        long total = resp.hits() == null || resp.hits().total() == null ? 0 : resp.hits().total().value();
        List<SearchHit> hits = resp.hits().hits().stream().map(this::toHit).toList();
        return new SearchResult(total, spec.page(), spec.size(), hits);
    }

    @Override
    public long count(QuerySpec spec) {
        CountResponse resp = run(() -> client.count(c -> c.index(indexName).query(translator.toQuery(spec))));
        return resp.count();
    }

    @Override
    public List<String> scopeIds(QuerySpec spec) {
        // PIT + search_after streaming (FR-3.6) — uncapped, avoids deep-paging cost.
        List<String> allIds = new ArrayList<>();
        String pitId = run(() -> client.openPointInTime(o -> o.index(indexName).keepAlive(t -> t.time("5m"))))
                .id();
        try {
            List<FieldValue> searchAfter = null;
            while (true) {
                final List<FieldValue> after = searchAfter;
                SearchResponse<Void> resp = run(() -> client.search(
                        translator.toScopeIdsRequest(spec, pitId, after), Void.class));
                List<Hit<Void>> hits = resp.hits().hits();
                if (hits.isEmpty()) {
                    break;
                }
                for (Hit<Void> h : hits) {
                    allIds.add(h.id());
                    searchAfter = h.sort();
                }
            }
        } finally {
            String finalPitId = pitId;
            try {
                client.closePointInTime(c -> c.id(finalPitId));
            } catch (IOException e) {
                log.warn("Failed to close PIT", e);
            }
        }
        log.info("scopeIds streamed {} ids via PIT + search_after", allIds.size());
        return allIds;
    }

    @Override
    public FacetResult facets(QuerySpec spec) {
        SearchResponse<Void> resp = run(() -> client.search(
                translator.toFacetsRequest(spec, indexName), Void.class));
        long total = resp.hits() == null || resp.hits().total() == null ? 0 : resp.hits().total().value();
        Map<String, Aggregate> aggs = resp.aggregations() != null ? resp.aggregations() : Map.of();

        Map<String, Long> byType = termsAgg(aggs, "by_type");
        Map<String, Long> byCustodian = termsAgg(aggs, "by_custodian");
        long onHoldCount = filterAgg(aggs, "on_hold");
        long hasAttachmentCount = filterAgg(aggs, "has_attachment");
        return new FacetResult(total, byType, byCustodian, onHoldCount, hasAttachmentCount);
    }

    @Override
    public void index(IndexedMessage message) {
        run(() -> client.index(i -> i.index(indexName).id(message.messageId()).document(message)));
    }

    @Override
    public void indexBatch(List<IndexedMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (IndexedMessage m : messages) {
            bulk.operations(op -> op.index(idx -> idx.index(indexName).id(m.messageId()).document(m)));
        }
        run(() -> client.bulk(bulk.build()));
        log.debug("Bulk-indexed {} message(s) into ES", messages.size());
    }

    @Override
    public void applyHold(String holdId, List<String> messageIds, boolean onHold) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        String script = onHold ? APPLY_HOLD_SCRIPT : RELEASE_HOLD_SCRIPT;
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (String id : messageIds) {
            bulk.operations(op -> op.update(u -> u.index(indexName).id(id)
                    .action(a -> a.script(s -> s.lang("painless").source(script)
                            .params("holdId", JsonData.of(holdId))))));
        }
        run(() -> client.bulk(bulk.build()));
        log.info("Applied hold={} onHold={} to {} message(s) in ES", holdId, onHold, messageIds.size());
    }

    @Override
    public void deleteByIds(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (String id : messageIds) {
            bulk.operations(op -> op.delete(d -> d.index(indexName).id(id)));
        }
        // A 404 per-item (already absent, or never indexed) is expected and
        // harmless — bulk() itself only throws on a transport-level failure;
        // per-item errors are silently ignored the same way applyHold's does.
        run(() -> client.bulk(bulk.build()));
        log.info("Retracted {} deleted message(s) from ES", messageIds.size());
    }

    @Override
    public void dropAndRecreateIndex() {
        try {
            client.indices().delete(d -> d.index(indexName).ignoreUnavailable(true));
            log.info("Dropped ES index '{}'", indexName);
        } catch (IOException e) {
            throw new SearchException("Failed to drop index '" + indexName + "'", e);
        }
        createIndex();
    }

    /** Create the index from the contract mapping (idempotent — skipped if it exists). */
    void createIndex() {
        try {
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                log.info("ES index '{}' already present — skipping creation", indexName);
                return;
            }
            Resource resource = resourceLoader.getResource(mappingLocation);
            try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                client.indices().create(CreateIndexRequest.of(c -> c.index(indexName).withJson(reader)));
            }
            log.info("Created ES index '{}' from {}", indexName, mappingLocation);
        } catch (Exception e) {
            throw new SearchException("Failed to create Elasticsearch index '" + indexName + "'", e);
        }
    }

    private SearchHit toHit(Hit<IndexedMessage> hit) {
        IndexedMessage m = hit.source();
        float score = hit.score() == null ? 0f : hit.score().floatValue();
        if (m == null) {
            return new SearchHit(hit.id(), "", "", score, "", "", "", false, false);
        }
        return new SearchHit(
                m.messageId() != null ? m.messageId() : hit.id(),
                m.subject() == null ? "" : m.subject(),
                snippet(hit, m),
                score,
                m.custodianId() == null ? "" : m.custodianId(),
                m.timestamp() == null ? "" : m.timestamp(),
                m.type() == null ? "" : m.type(),
                m.hasAttachment(),
                m.onHold());
    }

    private String snippet(Hit<IndexedMessage> hit, IndexedMessage m) {
        Map<String, List<String>> hl = hit.highlight();
        if (hl != null && !hl.isEmpty()) {
            List<String> bodyHl = hl.get("body");
            if (bodyHl != null && !bodyHl.isEmpty()) {
                return String.join(" … ", bodyHl);
            }
            List<String> subjHl = hl.get("subject");
            if (subjHl != null && !subjHl.isEmpty()) {
                return String.join(" … ", subjHl);
            }
            // FR-3.3: a hit matched only on a participant's name/address (e.g.
            // searching a custodian) still needs a highlighted snippet rather
            // than falling through to an unmarked body truncation below.
            List<String> participantsHl = hl.get("participantsText");
            if (participantsHl != null && !participantsHl.isEmpty()) {
                return String.join(" … ", participantsHl);
            }
        }
        if (m.body() != null && !m.body().isBlank()) {
            String b = m.body();
            return b.length() <= 150 ? b : b.substring(0, 150) + "…";
        }
        return "";
    }

    private Map<String, Long> termsAgg(Map<String, Aggregate> aggs, String name) {
        Map<String, Long> result = new LinkedHashMap<>();
        Aggregate agg = aggs.get(name);
        if (agg == null || !agg.isSterms()) {
            return result;
        }
        for (StringTermsBucket b : agg.sterms().buckets().array()) {
            result.put(b.key().stringValue(), (long) b.docCount());
        }
        return result;
    }

    private long filterAgg(Map<String, Aggregate> aggs, String name) {
        Aggregate agg = aggs.get(name);
        if (agg == null || !agg.isFilter()) {
            return 0;
        }
        return agg.filter().docCount();
    }

    /** Wrap checked IOException and ES runtime errors in a domain exception. */
    private <T> T run(IoSupplier<T> action) {
        try {
            return action.get();
        } catch (IOException e) {
            throw new SearchException("Elasticsearch operation failed", e);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
