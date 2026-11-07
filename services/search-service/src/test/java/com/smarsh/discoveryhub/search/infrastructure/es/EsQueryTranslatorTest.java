package com.smarsh.discoveryhub.search.infrastructure.es;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the QuerySpec -> Elasticsearch translation (no ES required).
 * Covers the Member 2 Day-1 test list: full-text, each filter, pagination, sorting.
 */
class EsQueryTranslatorTest {

    private final EsQueryTranslator translator = new EsQueryTranslator("messages");

    @Test
    void fullTextUsesMultiMatchBestFieldsWithBoosts() {
        QuerySpec spec = QuerySpec.builder().q("wire transfer").build();
        Query q = translator.toQuery(spec);
        assertThat(q.isBool()).isTrue();
        List<Query> must = q.bool().must();
        assertThat(must).hasSize(1);
        assertThat(must.get(0).isMultiMatch()).isTrue();
        var mm = must.get(0).multiMatch();
        assertThat(mm.query()).isEqualTo("wire transfer");
        assertThat(mm.type()).isEqualTo(TextQueryType.BestFields);
        assertThat(mm.fields()).containsExactly("subject^3", "body", "participantsText^2");
    }

    @Test
    void emptyQueryFallsBackToMatchAll() {
        QuerySpec spec = QuerySpec.builder().build();
        List<Query> must = translator.toQuery(spec).bool().must();
        assertThat(must.get(0).isMatchAll()).isTrue();
    }

    @Test
    void eachStructuredFilterIsEmitted() {
        QuerySpec spec = QuerySpec.builder().q("wire")
                .filters(new QuerySpec.Filters(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
                        List.of("EMAIL"), List.of("c-0007", "c-0012"), true, false, "hold-1"))
                .build();
        List<Query> filters = translator.toQuery(spec).bool().filter();
        // date range + types + custodians + hasAttachment + onHold + holdId
        assertThat(filters).hasSize(6);

        Query range = filters.stream().filter(Query::isRange).findFirst().orElseThrow();
        assertThat(range.range()._kind()).isEqualTo(RangeQuery.Kind.Date);
        assertThat(range.range().date().field()).isEqualTo("timestamp");
        assertThat(range.range().date().gte()).isEqualTo("2026-01-01");
        assertThat(range.range().date().lte()).isEqualTo("2026-06-30");

        Query typeTerms = filters.stream().filter(Query::isTerms)
                .filter(t -> t.terms().field().equals("type")).findFirst().orElseThrow();
        assertThat(typeTerms.terms().terms().value()).hasSize(1);

        Query custTerms = filters.stream().filter(Query::isTerms)
                .filter(t -> t.terms().field().equals("custodianId")).findFirst().orElseThrow();
        assertThat(custTerms.terms().terms().value()).hasSize(2);

        Query hasAtt = filters.stream().filter(Query::isTerm)
                .filter(t -> t.term().field().equals("hasAttachment")).findFirst().orElseThrow();
        assertThat(hasAtt.term().value().isBoolean()).isTrue();
        assertThat(hasAtt.term().value().booleanValue()).isTrue();

        Query onHold = filters.stream().filter(Query::isTerm)
                .filter(t -> t.term().field().equals("onHold")).findFirst().orElseThrow();
        assertThat(onHold.term().value().booleanValue()).isFalse();

        Query holdId = filters.stream().filter(Query::isTerm)
                .filter(t -> t.term().field().equals("holds")).findFirst().orElseThrow();
        assertThat(holdId.term().value().stringValue()).isEqualTo("hold-1");
    }

    @Test
    void blankHoldIdIsIgnored() {
        QuerySpec spec = QuerySpec.builder().q("wire")
                .filters(new QuerySpec.Filters(null, null, List.of(), List.of(), null, null, "  "))
                .build();
        List<Query> filters = translator.toQuery(spec).bool().filter();
        assertThat(filters).noneMatch(f -> f.isTerm() && f.term().field().equals("holds"));
    }

    @Test
    void pagingTranslatesToFromAndSize() {
        SearchRequest req = translator.toSearchRequest(
                QuerySpec.builder().q("x").page(2).size(10).build());
        assertThat(req.from()).isEqualTo(20);
        assertThat(req.size()).isEqualTo(10);
    }

    @Test
    void sizeIsCappedToMax() {
        SearchRequest req = translator.toSearchRequest(
                QuerySpec.builder().q("x").size(500).build());
        assertThat(req.size()).isEqualTo(EsQueryTranslator.MAX_SIZE);
    }

    @Test
    void sortByDateDescending() {
        List<co.elastic.clients.elasticsearch._types.SortOptions> sorts =
                translator.toSearchRequest(QuerySpec.builder().q("x").sort(QuerySpec.Sort.DATE_DESC).build()).sort();
        assertThat(sorts).isNotEmpty();
        assertThat(sorts.get(0).isField()).isTrue();
        assertThat(sorts.get(0).field().field()).isEqualTo("timestamp");
        assertThat(sorts.get(0).field().order()).isEqualTo(SortOrder.Desc);
    }

    @Test
    void sortByRelevanceScoresByScore() {
        List<co.elastic.clients.elasticsearch._types.SortOptions> sorts =
                translator.toSearchRequest(QuerySpec.builder().q("x").sort(QuerySpec.Sort.RELEVANCE).build()).sort();
        assertThat(sorts.get(0).isScore()).isTrue();
        assertThat(sorts.get(0).score().order()).isEqualTo(SortOrder.Desc);
    }

    @Test
    void highlightingUsesMarkTagsOnSubjectAndBody() {
        Highlight hl = translator.toSearchRequest(QuerySpec.builder().q("wire").build()).highlight();
        assertThat(hl.fields()).containsKeys("subject", "body");
        HighlightField body = hl.fields().get("body");
        assertThat(body.preTags()).contains("<mark>");
        assertThat(body.postTags()).contains("</mark>");
        assertThat(body.numberOfFragments()).isEqualTo(3);
    }

    /** FR-3.3: participantsText is part of the FTS_FIELDS multi_match (so a
     *  search matching only a participant's name/address still scores), and
     *  must also be highlighted — otherwise that hit comes back with no
     *  {@code <mark>} fragment at all. */
    @Test
    void highlightingAlsoCoversParticipantsText() {
        Highlight hl = translator.toSearchRequest(QuerySpec.builder().q("Ada Byron").build()).highlight();
        assertThat(hl.fields()).containsKeys("subject", "body", "participantsText");
        HighlightField participants = hl.fields().get("participantsText");
        assertThat(participants.preTags()).contains("<mark>");
        assertThat(participants.postTags()).contains("</mark>");
        assertThat(participants.numberOfFragments()).isEqualTo(3);
    }

    @Test
    void scopeIdsRequestUsesPitAndDocSort() {
        QuerySpec spec = QuerySpec.builder().q("wire").build();
        SearchRequest req = translator.toScopeIdsRequest(spec, "pit-test-id", null);
        assertThat(req.pit().id()).isEqualTo("pit-test-id");
        assertThat(req.size()).isEqualTo(EsQueryTranslator.SCOPE_BATCH);
        assertThat(req.source().fetch()).isFalse();
        assertThat(req.sort()).hasSize(1);
        assertThat(req.sort().get(0).isField()).isTrue();
        assertThat(req.sort().get(0).field().field()).isEqualTo("_doc");
    }

    @Test
    void scopeIdsRequestWithSearchAfterSetsCursor() {
        QuerySpec spec = QuerySpec.builder().q("wire").build();
        List<FieldValue> cursor = List.of(FieldValue.of("doc-42"));
        SearchRequest req = translator.toScopeIdsRequest(spec, "pit-id", cursor);
        assertThat(req.searchAfter()).containsExactlyElementsOf(cursor);
    }

    @Test
    void facetsRequestHasAggregationsAndZeroSize() {
        QuerySpec spec = QuerySpec.builder().build();
        SearchRequest req = translator.toFacetsRequest(spec, "messages");
        assertThat(req.size()).isZero();
        assertThat(req.aggregations()).containsKeys("by_type", "by_custodian", "on_hold", "has_attachment");
    }
}
