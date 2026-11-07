package com.smarsh.discoveryhub.export.api;

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
 * entry published from an HTTP request (FR-7.2: "actor" per entry) carries the
 * caller's identity instead of the fallback {@code "system"}. Without this
 * filter {@code Actor.current()}/{@code CorrelationId.current()} calls made
 * from {@link ExportController} (EXPORT_REQUESTED/DOWNLOADED/RETRIED) always
 * saw the default, since only Kafka-consumed events (worker-side
 * EXPORT_COMPLETED/FAILED) previously seeded these thread-locals.
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
