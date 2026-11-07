package com.smarsh.discoveryhub.search.api;

import com.smarsh.discoveryhub.common.ApiError;
import com.smarsh.discoveryhub.search.domain.SearchException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps service failures to the shared {@link ApiError} envelope (RFC-7807).
 *
 * <p><b>SOLID — SRP:</b> error-to-HTTP translation is a single, separate
 * concern, lifted out of every controller method. Controllers throw (or let
 * the domain throw) and never build an error body, so there is exactly one
 * place that decides what a failure looks like on the wire.
 *
 * <p><b>SOLID — OCP:</b> a new failure mode is a new {@code @ExceptionHandler}
 * method here; no existing controller or handler is modified. The catch-all
 * {@code Exception} handler is the safety net that guarantees a client never
 * receives a raw stack trace.
 *
 * <p><b>Design pattern — Builder:</b> the response body is assembled via
 * {@code ApiError.builder()}, shared across all seven services so every error
 * has the same shape and a machine-readable {@code code}
 * ({@code SEARCH_UNAVAILABLE} / {@code INVALID_QUERY} / {@code INTERNAL_ERROR})
 * that the Angular client can branch on.
 *
 * <p><b>Resiliency + InfoSec note:</b> an Elasticsearch outage surfaces as a
 * clean {@code 503 SEARCH_UNAVAILABLE} rather than a 500 with a stack trace —
 * which is what lets the frontend degrade only the search widget (NFR-2) — and
 * internal exception details are logged server-side, never echoed to the
 * caller.
 */
@RestControllerAdvice
public class SearchExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SearchExceptionHandler.class);

    @ExceptionHandler(SearchException.class)
    public ResponseEntity<ApiError> searchUnavailable(SearchException ex, HttpServletRequest req) {
        log.warn("Search backend unavailable: {}", ex.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_UNAVAILABLE",
                "Search backend is temporarily unavailable", req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> badBody(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_QUERY",
                "Malformed search request body", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error in search-service", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Unexpected error", req);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest req) {
        ApiError body = ApiError.builder()
                .status(status.value())
                .code(code)
                .title(status.getReasonPhrase())
                .detail(message)
                .build();
        return ResponseEntity.status(status).body(body);
    }
}
