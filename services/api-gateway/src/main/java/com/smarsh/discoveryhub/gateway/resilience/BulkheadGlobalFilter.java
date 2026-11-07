package com.smarsh.discoveryhub.gateway.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Per-route bounded bulkhead (execution-plan Day 4 M5: "Resilience4j circuit
 * breaker + bounded bulkhead in gateway/export"). Spring Cloud Gateway's
 * {@code CircuitBreaker} filter only wires a {@link io.github.resilience4j.circuitbreaker.CircuitBreaker}
 * + {@code TimeLimiter} per route; the bulkhead is applied here as a
 * {@link GlobalFilter} using the <em>same</em> instance names configured
 * under {@code resilience4j.bulkhead.instances} in {@code application.yml},
 * so a slow or stuck downstream can't exhaust the gateway's own request
 * threads regardless of whether its circuit has tripped yet.
 *
 * <p>Runs after routing has resolved the matched {@link Route} (hence
 * {@link Ordered#LOWEST_PRECEDENCE}, same slot most gateway filters run in)
 * so the route id is available to pick the right bulkhead instance.</p>
 */
@Component
public class BulkheadGlobalFilter implements GlobalFilter, Ordered {

    /** route id -> resilience4j.bulkhead.instances name (mirrors the CircuitBreaker filter args in application.yml). */
    private static final Map<String, String> BULKHEAD_BY_ROUTE = Map.of(
            "ingest", "ingestCB",
            "archive", "archiveCB",
            "archive-admin", "archiveCB",
            "search", "searchCB",
            "cases", "caseCB",
            "holds", "holdCB",
            "exports", "exportCB",
            "audit", "auditCB");

    private final BulkheadRegistry bulkheadRegistry;

    public BulkheadGlobalFilter(BulkheadRegistry bulkheadRegistry) {
        this.bulkheadRegistry = bulkheadRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String bulkheadName = route == null ? null : BULKHEAD_BY_ROUTE.get(route.getId());
        if (bulkheadName == null) {
            return chain.filter(exchange);
        }
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(bulkheadName);
        return chain.filter(exchange)
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .onErrorResume(BulkheadFullException.class, ex -> {
                    exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    return exchange.getResponse().setComplete();
                });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
