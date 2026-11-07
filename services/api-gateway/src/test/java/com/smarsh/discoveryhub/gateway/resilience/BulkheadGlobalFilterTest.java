package com.smarsh.discoveryhub.gateway.resilience;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link BulkheadGlobalFilter} actually bounds concurrency per route
 * (execution-plan Day 4 M5 resiliency: a slow/stuck downstream must not
 * exhaust the gateway's own request-handling capacity for every other
 * route).
 */
class BulkheadGlobalFilterTest {

    private final BulkheadRegistry registry = BulkheadRegistry.of(BulkheadConfig.custom()
            .maxConcurrentCalls(1)
            .maxWaitDuration(Duration.ZERO)
            .build());
    private final BulkheadGlobalFilter filter = new BulkheadGlobalFilter(registry);

    @Test
    void unmatchedRouteIsNotBulkheaded() {
        ServerWebExchange exchange = exchangeFor(null);
        GatewayFilterChain chain = ex -> Mono.empty();

        assertThat(filter.filter(exchange, chain).blockOptional(Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    void secondConcurrentCallToTheSameRouteIsRejectedWith503() throws InterruptedException {
        ServerWebExchange first = exchangeFor("archive");
        ServerWebExchange second = exchangeFor("archive");
        Sinks.Empty<Void> holdOpen = Sinks.empty();
        CountDownLatch firstRunning = new CountDownLatch(1);

        // Occupy the (maxConcurrentCalls=1) bulkhead slot with a call that
        // never completes until we release it below. The latch fires from
        // doOnSubscribe — i.e. only once the bulkhead has actually granted
        // the permit and subscribed downstream — not at Mono-construction
        // time, otherwise this races with the bulkhead acquisition itself.
        Thread firstCall = new Thread(() -> filter.filter(first,
                ex -> holdOpen.asMono().doOnSubscribe(s -> firstRunning.countDown())).subscribe());
        firstCall.start();
        assertThat(firstRunning.await(2, TimeUnit.SECONDS)).isTrue();

        // The bulkhead only allows one concurrent call for this route; the
        // second must be rejected and mapped to a 503 rather than queueing or
        // blocking the gateway's event loop. Its chain must never be
        // subscribed to (deferred, exactly like a real downstream HTTP call
        // would be — the assertion fires on subscription, not construction).
        GatewayFilterChain chainThatMustNotRun = ex ->
                Mono.error(new AssertionError("chain must not run when the bulkhead is full"));
        filter.filter(second, chainThatMustNotRun).block(Duration.ofSeconds(2));

        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        holdOpen.tryEmitEmpty();
        firstCall.join(2000);
    }

    private static ServerWebExchange exchangeFor(String routeId) {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/messages/1").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        if (routeId != null) {
            Route route = mock(Route.class);
            when(route.getId()).thenReturn(routeId);
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        }
        return exchange;
    }
}
