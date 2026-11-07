package com.smarsh.discoveryhub.search.domain.model;

import java.util.List;

/**
 * Page of search results matching {@code docs/api-contracts.md} §search.
 *
 * <p><b>OOP — immutable value object:</b> a {@code record} that is also the
 * wire contract, so the API response shape is defined once in the domain
 * rather than in a separate DTO that has to be kept in sync.
 *
 * <p><b>Null Object pattern — {@link #empty(QuerySpec)}:</b> a degraded or
 * empty search returns an empty result that still echoes the caller's
 * {@code page}/{@code size}, so clients never have to null-check
 * {@code hits}. This is what lets the UI render "no results" and a
 * degraded-service state through the same code path.
 */
public record SearchResult(long total, int page, int size, List<SearchHit> hits) {
    public static SearchResult empty(QuerySpec spec) {
        return new SearchResult(0, spec.page(), spec.size(), List.of());
    }
}
