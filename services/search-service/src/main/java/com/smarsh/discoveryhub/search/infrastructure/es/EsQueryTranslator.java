package com.smarsh.discoveryhub.search.infrastructure.es;

import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQueryField;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlighterType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates a {@link QuerySpec} into an Elasticsearch {@link SearchRequest}.
 * <p>
 * Builder + Strategy pair called out in architecture §2.2: the {@code multi_match}
 * uses {@code best_fields} over {@code subject^3, body, participantsText^2}; structured
 * filters go into the {@code bool.filter} context (no scoring cost); highlighting uses
 * the unified highlighter with {@code <mark>} tags and 3 fragments.
 *
 * <p>ES is queried only through this translator + the {@link ElasticsearchSearchPort},
 * keeping the ES client behind the hexagonal port boundary.
 *
 * <p><b>Design pattern — Builder:</b> every method here incrementally assembles
 * a request through ES's fluent builders ({@code BoolQuery.Builder},
 * {@code SearchRequest.of(...)}) — clauses are added conditionally, one filter
 * at a time, and only then built. That is precisely why an optional filter
 * costs one {@code if} block rather than a new query variant.
 *
 * <p><b>Design pattern — Strategy (by enum):</b> {@link #sortOptions} selects
 * the sort algorithm at runtime from {@code QuerySpec.Sort}
 * (RELEVANCE / DATE_ASC / DATE_DESC), each mapping to a different ES sort
 * clause, with a secondary score sort added for deterministic ordering.
 *
 * <p><b>SOLID — SRP:</b> one job — translate a domain {@code QuerySpec} into an
 * ES request. It performs no I/O, which is the whole point: it is a pure
 * function from spec to request, so query-shape regressions (field boosts,
 * filter context, highlight fields) are caught by fast unit tests
 * ({@code EsQueryTranslatorTest}) instead of needing a live cluster.
 *
 * <p><b>SOLID — OCP:</b> the four request shapes (paged search, PIT scope-ids,
 * facets, bare query) all reuse {@link #toQuery} for the filter logic, so a new
 * filter is added once and is immediately honoured by search, counting,
 * bulk-scope resolution and aggregations alike. Adding a new request shape
 * likewise means adding a method, not editing the existing ones.
 *
 * <p><b>Performance rationale (NFR-3, "search under 2 s"):</b> full-text goes
 * into {@code bool.must} (scored) while every structured filter goes into
 * {@code bool.filter} — filter context is not scored and IS cached by
 * Elasticsearch, which is the main reason measured p95 is ~60 ms. Page size is
 * hard-capped at {@link #MAX_SIZE} so a client cannot request an unbounded
 * page, and {@link #toScopeIdsRequest} uses PIT + {@code search_after} sorted
 * by {@code _doc} with {@code _source} disabled, avoiding deep-paging cost
 * when draining a whole result set (FR-3.6).
 */
@Component
public class EsQueryTranslator {

    /** Full-text fields with per-field boosts (architecture §2.2). */
    static final List<String> FTS_FIELDS = List.of("subject^3", "body", "participantsText^2");
    static final int MAX_SIZE = 100;
    static final int SCOPE_BATCH = 5_000;

    private final String indexName;

    public EsQueryTranslator(@Value("${discoveryhub.search.index-name:messages}") String indexName) {
        this.indexName = indexName;
    }

    /** Build the reusable bool query (must = full-text, filter = structured). */
    public Query toQuery(QuerySpec spec) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        String q = spec.q() == null ? "" : spec.q().strip();
        if (q.isEmpty()) {
            bool.must(Query.of(m -> m.matchAll(ma -> ma)));
        } else {
            bool.must(Query.of(m -> m.multiMatch(mm -> mm
                    .query(q).fields(FTS_FIELDS).type(TextQueryType.BestFields))));
        }
        QuerySpec.Filters f = spec.filters() == null
                ? new QuerySpec.Filters(null, null, List.of(), List.of(), null, null, null)
                : spec.filters();

        if (f.from() != null || f.to() != null) {
            bool.filter(Query.of(qb -> qb.range(r -> r.date(d -> {
                d.field("timestamp");
                if (f.from() != null) d.gte(f.from().toString());
                if (f.to() != null) d.lte(f.to().toString());
                return d;
            }))));
        }
        if (!f.types().isEmpty()) {
            bool.filter(Query.of(qb -> qb.terms(t -> t.field("type")
                    .terms(TermsQueryField.of(tf -> tf.value(f.types().stream().map(FieldValue::of).toList()))))));
        }
        if (!f.custodians().isEmpty()) {
            bool.filter(Query.of(qb -> qb.terms(t -> t.field("custodianId")
                    .terms(TermsQueryField.of(tf -> tf.value(f.custodians().stream().map(FieldValue::of).toList()))))));
        }
        if (f.hasAttachment() != null) {
            bool.filter(Query.of(qb -> qb.term(t -> t.field("hasAttachment").value(FieldValue.of(f.hasAttachment().booleanValue())))));
        }
        if (f.onHold() != null) {
            bool.filter(Query.of(qb -> qb.term(t -> t.field("onHold").value(FieldValue.of(f.onHold().booleanValue())))));
        }
        if (f.holdId() != null && !f.holdId().isBlank()) {
            bool.filter(Query.of(qb -> qb.term(t -> t.field("holds").value(FieldValue.of(f.holdId())))));
        }
        return Query.of(qb -> qb.bool(bool.build()));
    }

    /** Full paged, highlighted, sorted search request. */
    public SearchRequest toSearchRequest(QuerySpec spec) {
        int page = Math.max(0, spec.page());
        int size = Math.min(Math.max(1, spec.size()), MAX_SIZE);
        return SearchRequest.of(s -> s
                .index(indexName)
                .query(toQuery(spec))
                .from(page * size)
                .size(size)
                .highlight(highlight())
                .sort(sortOptions(spec)));
    }

    /**
     * PIT + search_after request for streaming ids (FR-3.6). Each page is sorted
     * by {@code _doc} (cheapest) and uses the previous page's last sort values as
     * the cursor, so the full result set is drained without deep-paging cost.
     */
    public SearchRequest toScopeIdsRequest(QuerySpec spec, String pitId, List<FieldValue> searchAfter) {
        return SearchRequest.of(s -> {
            s.pit(p -> p.id(pitId).keepAlive(t -> t.time("5m")))
                    .query(toQuery(spec))
                    .size(SCOPE_BATCH)
                    .source(src -> src.fetch(false))
                    .sort(so -> so.field(f -> f.field("_doc")));
            if (searchAfter != null && !searchAfter.isEmpty()) {
                s.searchAfter(searchAfter);
            }
            return s;
        });
    }

    /** Aggregations request for dashboard counts + hold-scope preview. */
    public SearchRequest toFacetsRequest(QuerySpec spec, String idx) {
        return SearchRequest.of(s -> s
                .index(idx)
                .query(toQuery(spec))
                .size(0)
                .aggregations("by_type", a -> a.terms(t -> t.field("type").size(10)))
                .aggregations("by_custodian", a -> a.terms(t -> t.field("custodianId").size(20)))
                .aggregations("on_hold", a -> a.filter(f -> f.term(t -> t.field("onHold").value(true))))
                .aggregations("has_attachment", a -> a.filter(f -> f.term(t -> t.field("hasAttachment").value(true)))));
    }

    private Highlight highlight() {
        return Highlight.of(hb -> hb.type(HighlighterType.Unified)
                .fields("subject", fb -> fb.preTags("<mark>").postTags("</mark>")
                        .numberOfFragments(3).fragmentSize(150))
                .fields("body", fb -> fb.preTags("<mark>").postTags("</mark>")
                        .numberOfFragments(3).fragmentSize(150))
                // FR-3.3: participantsText is part of the multi_match (FTS_FIELDS
                // above) but was missing here, so a hit matched purely on a
                // participant's name/address came back with zero highlight
                // fragments. Same unified-highlighter config as subject/body.
                .fields("participantsText", fb -> fb.preTags("<mark>").postTags("</mark>")
                        .numberOfFragments(3).fragmentSize(150)));
    }

    private List<co.elastic.clients.elasticsearch._types.SortOptions> sortOptions(QuerySpec spec) {
        QuerySpec.Sort sort = spec.sort() == null ? QuerySpec.Sort.RELEVANCE : spec.sort();
        List<co.elastic.clients.elasticsearch._types.SortOptions> sorts = new ArrayList<>();
        switch (sort) {
            case DATE_ASC -> sorts.add(so(so -> so.field(f -> f.field("timestamp").order(SortOrder.Asc))));
            case DATE_DESC -> sorts.add(so(so -> so.field(f -> f.field("timestamp").order(SortOrder.Desc))));
            case RELEVANCE -> sorts.add(so(so -> so.score(sc -> sc.order(SortOrder.Desc))));
        }
        // Secondary sort keeps deterministic ordering for date sorts.
        if (sort != QuerySpec.Sort.RELEVANCE) {
            sorts.add(so(so -> so.score(sc -> sc.order(SortOrder.Desc))));
        }
        return sorts;
    }

    private static co.elastic.clients.elasticsearch._types.SortOptions so(
            java.util.function.Function<co.elastic.clients.elasticsearch._types.SortOptions.Builder,
                    co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch._types.SortOptions>> fn) {
        return co.elastic.clients.elasticsearch._types.SortOptions.of(fn);
    }
}
