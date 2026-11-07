package com.smarsh.discoveryhub.search.api;

import com.smarsh.discoveryhub.search.domain.model.FacetResult;
import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import com.smarsh.discoveryhub.search.domain.model.SearchResult;
import com.smarsh.discoveryhub.search.domain.service.SearchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Search API — owns the Elasticsearch {@code messages} index. Stateless read API;
 * ES is an index, never the source of truth (architecture §3.4).
 *
 * <p>Implements the contract endpoints from {@code docs/api-contracts.md} §search:
 * {@code POST /api/search}, {@code POST /api/search/scope-ids},
 * {@code POST /api/search/scope-count}, {@code GET /api/search/facets}. A convenience
 * {@code GET /api/search?q=} is also exposed so the demo smoke script
 * ({@code GET /search?q=...}) works through the gateway without a request body.
 *
 * <p><b>SOLID — SRP (thin controller):</b> this class only translates HTTP to
 * domain calls and back. There is no query logic, no ES code and no error
 * mapping here — errors are handled once in {@link SearchExceptionHandler},
 * and every method is a one-liner delegating to {@code SearchService}. That is
 * what keeps the business behaviour testable without {@code MockMvc}.
 *
 * <p><b>SOLID — DIP:</b> it depends on the injected {@code SearchService}
 * abstraction, not on Elasticsearch. The whole controller layer could be
 * replaced by a GraphQL or gRPC front end without touching the domain.
 *
 * <p><b>Design pattern — Builder:</b> the GET/facets convenience endpoints use
 * {@code QuerySpec.builder()} to supply only the fields the caller provided
 * and let the rest default — the readable alternative to passing nulls
 * positionally.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /** Full-text + structured filter query, with highlighting and paging (FR-3.1–FR-3.4). */
    @PostMapping
    public SearchResult search(@RequestBody QuerySpec spec) {
        if (spec == null) {
            spec = QuerySpec.builder().build();
        }
        return searchService.search(spec);
    }

    /** Convenience GET form of the query for the smoke script / demo. */
    @GetMapping
    public SearchResult searchGet(@RequestParam(defaultValue = "") String q,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "25") int size) {
        return searchService.search(QuerySpec.builder().q(q).page(page).size(size).build());
    }

    /** Stream all matching ids for bulk "add this page to case" (FR-3.6). */
    @PostMapping("/scope-ids")
    public Map<String, Object> scopeIds(@RequestBody QuerySpec spec) {
        List<String> ids = searchService.scopeIds(spec == null ? QuerySpec.builder().build() : spec);
        return Map.of("ids", ids);
    }

    /** Cheap count of a scope without fetching hits (FR-3.6). */
    @PostMapping("/scope-count")
    public Map<String, Object> scopeCount(@RequestBody QuerySpec spec) {
        long count = searchService.count(spec == null ? QuerySpec.builder().build() : spec);
        return Map.of("count", count);
    }

    /** Aggregated counts for dashboard + hold-scope preview (architecture §2.2). */
    @GetMapping("/facets")
    public FacetResult facets(@RequestParam(defaultValue = "") String q) {
        return searchService.facets(QuerySpec.builder().q(q).build());
    }
}
