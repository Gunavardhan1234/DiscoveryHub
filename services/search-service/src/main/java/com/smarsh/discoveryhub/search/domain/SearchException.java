package com.smarsh.discoveryhub.search.domain;

import com.smarsh.discoveryhub.common.DomainException;

/**
 * Unchecked exception wrapping Elasticsearch transport/runtime failures.
 * Extends the shared {@link DomainException} so the error contract stays
 * consistent — mapped to HTTP 503 with code {@code SEARCH_UNAVAILABLE}.
 *
 * <p><b>SOLID — LSP:</b> this is substitutable wherever a
 * {@link DomainException} is handled, which is what lets the shared
 * platform-wide error handling treat it uniformly without a special case.
 *
 * <p><b>OOP — exception translation / leaky-abstraction control:</b> the ES
 * adapter catches the client's {@code IOException}/{@code ElasticsearchException}
 * and rethrows this instead, so an Elasticsearch type never escapes past the
 * port boundary. The domain expresses <em>meaning</em> ("search is
 * unavailable"), not a vendor failure mode, and the 503 status lives with the
 * exception rather than being decided in a controller.
 */
public class SearchException extends DomainException {

    public SearchException(String message, Throwable cause) {
        super(503, "SEARCH_UNAVAILABLE", message, cause);
    }
}
