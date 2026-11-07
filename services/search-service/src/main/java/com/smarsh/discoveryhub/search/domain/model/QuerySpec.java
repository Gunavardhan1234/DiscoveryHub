package com.smarsh.discoveryhub.search.domain.model;

import java.time.LocalDate;
import java.util.List;

/**
 * The domain's technology-neutral description of "what to search for" —
 * free text, structured filters, sort, paging and an optional case scope.
 * Deliberately contains no Elasticsearch type: it is what lets the same spec
 * be produced by a controller, stored as JSONB by case-service as a saved
 * search (FR-3.5), and replayed verbatim later.
 *
 * <p><b>Design pattern — Builder:</b> {@link Builder} exists because a
 * six-argument constructor with sensible defaults (empty query, RELEVANCE
 * sort, page 0, size 25) is unreadable and error-prone at call sites. Compare
 * {@code QuerySpec.builder().q(q).page(page).size(size).build()} in
 * {@code SearchController} with the equivalent positional constructor call.
 *
 * <p><b>OOP — immutable value object:</b> a {@code record}, so it is
 * inherently immutable and safe to share; the compact constructor in
 * {@link Filters} additionally null-guards and defensively copies the
 * {@code types} and {@code custodians} lists via {@code List.copyOf}, so a
 * caller cannot mutate a spec after handing it over.
 *
 * <p><b>SOLID — OCP:</b> adding a filter dimension means adding a component
 * here and one {@code bool.filter} clause in {@code EsQueryTranslator}.
 * {@code SearchService}, {@code SearchPort} and every controller are untouched
 * — the {@code holdId} filter (added for export-service to resolve a hold's
 * live scope) was exactly that change.
 *
 * <p><b>Honest scope note:</b> {@link Filters} is a <em>flat</em> record, so
 * filters combine with implicit AND only. Nested boolean groups
 * ({@code (A OR B) AND C}) would need a recursive filter tree — the Composite
 * pattern — which is designed but NOT implemented in this build. Do not claim
 * Composite for this service.
 */
public record QuerySpec(String q, Filters filters, Sort sort, int page, int size, String caseId) {
    /** <b>Strategy-by-enum:</b> the sort mode selected at runtime; the actual
     *  comparator/ES sort clause for each is resolved in {@code EsQueryTranslator}. */
    public enum Sort { RELEVANCE, DATE_ASC, DATE_DESC }
    /**
     * @param holdId restrict to messages currently covered by this specific
     *                hold (its {@code holds[]} set contains the id) — used by
     *                export-service to resolve "a hold's full scope" (FR-6.1)
     *                against the live, reference-counted hold state kept in
     *                sync by {@code HoldIndexer}, rather than by replaying the
     *                hold's original placement criteria.
     */
    public record Filters(LocalDate from, LocalDate to, List<String> types, List<String> custodians,
                          Boolean hasAttachment, Boolean onHold, String holdId) {
        public Filters {
            types = types == null ? List.of() : List.copyOf(types);
            custodians = custodians == null ? List.of() : List.copyOf(custodians);
        }
    }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String q = "";
        private Filters filters = new Filters(null, null, List.of(), List.of(), null, null, null);
        private Sort sort = Sort.RELEVANCE;
        private int page;
        private int size = 25;
        private String caseId;
        public Builder q(String q) { this.q = q; return this; }
        public Builder filters(Filters filters) { this.filters = filters; return this; }
        public Builder sort(Sort sort) { this.sort = sort; return this; }
        public Builder page(int page) { this.page = page; return this; }
        public Builder size(int size) { this.size = size; return this; }
        public Builder caseId(String caseId) { this.caseId = caseId; return this; }
        public QuerySpec build() { return new QuerySpec(q, filters, sort, page, size, caseId); }
    }
}
