package com.smarsh.discoveryhub.export.infrastructure.http;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the resiliency shape configured in {@code application.yml}'s
 * {@code resilience4j.circuitbreaker.instances.archiveClient} /
 * {@code resilience4j.bulkhead.instances.archiveClient} for the Day-4
 * resiliency scenario (execution-plan: "archive-service goes down mid-export
 * — export jobs must fail fast via CB/bulkhead instead of piling up blocked
 * threads, and Kafka retry/DLT takes it from there").
 *
 * <p>Mirrors the yml thresholds in code (rather than booting a full Spring
 * context, consistent with this module's mock-based unit test style) and
 * drives {@link ArchiveMessageHttpAdapter} — pointed at a closed local port so
 * every call fails fast with a real connection refusal — through the same
 * {@link CircuitBreaker}/{@link Bulkhead} decorators Resilience4j's AOP layer
 * would apply in production.</p>
 */
class ArchiveClientResilienceTest {

    // Mirrors resilience4j.circuitbreaker.configs.default in application.yml.
    private static final CircuitBreakerConfig CB_CONFIG = CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(15))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

    // Mirrors resilience4j.bulkhead.configs.default in application.yml.
    private static final BulkheadConfig BULKHEAD_CONFIG = BulkheadConfig.custom()
            .maxConcurrentCalls(10)
            .maxWaitDuration(Duration.ZERO)
            .build();

    /** Port 1 is a reserved, always-closed port — every call fails immediately with "connection refused". */
    private final ArchiveMessageHttpAdapter unreachableArchive = new ArchiveMessageHttpAdapter("http://127.0.0.1:1");

    @Test
    void circuitOpensAfterEnoughFailuresThenFailsFastWithoutHittingTheNetwork() {
        CircuitBreaker breaker = CircuitBreaker.of("archiveClient-test", CB_CONFIG);
        Supplier<List<?>> call = CircuitBreaker.decorateSupplier(breaker, () -> unreachableArchive.fetchMessages(List.of("m-1")));

        // Below minimumNumberOfCalls the breaker stays CLOSED and every call
        // really does try (and fail) to reach the network.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(call::get).isNotInstanceOf(CallNotPermittedException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Once OPEN, further calls must fail immediately with
        // CallNotPermittedException — no socket connection attempted at all —
        // which is exactly what stops export jobs from piling up blocked
        // threads against a downed archive-service.
        assertThatThrownBy(call::get).isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void bulkheadRejectsCallsBeyondTheConcurrencyLimit() throws InterruptedException {
        Bulkhead bulkhead = Bulkhead.of("archiveClient-test", BulkheadConfig.custom()
                .maxConcurrentCalls(2)
                .maxWaitDuration(Duration.ZERO)
                .build());

        CountDownLatch releaseLatch = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> occupying = List.of(
                    pool.submit(() -> runInsideBulkhead(bulkhead, releaseLatch, rejected)),
                    pool.submit(() -> runInsideBulkhead(bulkhead, releaseLatch, rejected)));
            // give the two occupying calls time to actually enter the bulkhead
            Thread.sleep(200);

            // a third concurrent call must be rejected — the bulkhead is full
            assertThatThrownBy(() -> Bulkhead.decorateSupplier(bulkhead, () -> "should not run").get())
                    .isInstanceOf(BulkheadFullException.class);

            releaseLatch.countDown();
            for (Future<?> f : occupying) f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object runInsideBulkhead(Bulkhead bulkhead, CountDownLatch releaseLatch, AtomicInteger rejected) {
        try {
            return Bulkhead.decorateSupplier(bulkhead, () -> {
                try {
                    releaseLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }).get();
        } catch (BulkheadFullException e) {
            rejected.incrementAndGet();
            return null;
        }
    }
}
