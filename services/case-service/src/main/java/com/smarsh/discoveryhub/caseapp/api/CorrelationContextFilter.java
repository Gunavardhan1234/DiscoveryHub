package com.smarsh.discoveryhub.caseapp.api;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Seeds {@link CorrelationId} and {@link Actor} from the {@code X-Correlation-Id}
 * and {@code X-Actor} headers (defaults if absent) so every {@code audit.events.v1}
 * entry published from case-service (CASE_CREATED/CASE_TRANSITIONED/
 * CUSTODIAN_ADDED/SAVED_SEARCH_CREATED — FR-7.2 "actor" per entry) carries the
 * caller's identity rather than the fallback {@code "system"}.
 */
// Template Method (via inheritance): OncePerRequestFilter defines the fixed "run at most once per
// request, then decide whether to continue the chain" algorithm; this subclass only fills in the
// doFilterInternal hook — the boilerplate of checking "have I already run for this request" lives
// entirely in the Spring base class.
// @Order(HIGHEST_PRECEDENCE): guarantees this filter runs before every other filter in the chain,
// so CorrelationId/Actor are seeded before any controller or later filter could need them.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CorrelationId.HEADER);
        String actor = request.getHeader(Actor.HEADER);
        boolean seeded = false;
        // try/finally around a ThreadLocal set/clear pair (CorrelationId/Actor in `common` are
        // backed by ThreadLocal): the servlet container reuses worker threads across requests, so
        // failing to clear here would leak this request's correlation id/actor into whichever
        // unrelated request the same thread handles next — finally guarantees cleanup even if
        // chain.doFilter throws.
        try {
            if (correlationId != null || actor != null) {
                CorrelationId.set(correlationId);
                Actor.set(actor);
                seeded = true;
            }
            chain.doFilter(request, response);
        } finally {
            if (seeded) {
                CorrelationId.clear();
                Actor.clear();
            }
        }
    }
}
