package com.smarsh.discoveryhub.search.api;

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
 * and {@code X-Actor} headers (defaults if absent) so the {@code SEARCH_EXECUTED}
 * {@code audit.events.v1} entry (FR-7.1/FR-7.2) carries the caller's identity
 * rather than the fallback {@code "system"}.
 *
 * <p><b>Design pattern — Intercepting Filter / Chain of Responsibility (the
 * genuine article):</b> a servlet filter is a real handler chain — this one
 * does its work, calls {@code chain.doFilter}, and cleans up afterwards. It is
 * the cross-cutting-concern equivalent of AOP, applied to every request
 * without a single controller knowing it exists.
 *
 * <p><b>SOLID — SRP:</b> "who is calling, and under which correlation id" is
 * one concern, handled in one place. Without this filter every controller
 * method would have to read two headers and thread them down into the service
 * layer just so an audit event could name the actor.
 *
 * <p><b>SOLID — OCP:</b> auditing gained caller attribution by <em>adding</em>
 * this filter; no controller or service was modified.
 *
 * <p><b>Correctness detail — ThreadLocal hygiene:</b> the context is cleared in
 * a {@code finally} block, and only when this filter actually seeded it. On a
 * pooled request thread, leaking the previous request's actor would mean
 * attributing one user's search to another in the audit trail — an integrity
 * bug in a chain-of-custody system, not just untidiness.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CorrelationId.HEADER);
        String actor = request.getHeader(Actor.HEADER);
        boolean seeded = false;
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
