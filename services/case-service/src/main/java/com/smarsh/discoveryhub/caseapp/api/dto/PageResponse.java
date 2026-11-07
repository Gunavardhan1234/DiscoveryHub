package com.smarsh.discoveryhub.caseapp.api.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Wire shape for a paged list, matching the Angular {@code Page<T>} model in
 * {@code web/src/app/core/api.model.ts} exactly ({@code content},
 * {@code totalElements}, {@code totalPages}, {@code page}, {@code size}).
 * Spring Data's own {@link Page} serializes the current page index as
 * {@code number}, not {@code page} — this record exists so the frontend
 * never has to special-case that.
 */
// Generics: PageResponse<T> works for a page of CaseResponse today and would work identically for
// any other DTO tomorrow with zero new code — the compiler enforces that `content` and the
// `from(Page<T>)` factory always agree on the same type T, no unchecked casts required.
public record PageResponse<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int page,
        int size) {

    // Generic static factory method: <T> is inferred from the Page<T> argument at the call site
    // (PageResponse.from(caseService.list(...))), so callers never have to spell out the type.
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize());
    }
}
