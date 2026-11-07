package com.smarsh.discoveryhub.archive.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resiliency scaffolding for the Day-4 demo (NFR-2): exposes Kafka consumer lag
 * as a Micrometer gauge {@code discoveryhub.consumer.lag} (max {@code records-lag}
 * across all listener-container partitions) and logs it on a schedule.
 *
 * <p>During the resiliency demo ({@code docker compose stop archive-service} while
 * ingestion continues) this metric climbs while the consumer is down and drains to
 * 0 once it restarts and catches up — the observable proof that Kafka retained
 * the events and nothing was lost.
 *
 * <p>The metric is published to {@code /actuator/metrics/discoveryhub.consumer.lag}
 * and {@code /actuator/prometheus}.
 */
@Component
public class ConsumerLagMetrics {

    private static final Logger log = LoggerFactory.getLogger(ConsumerLagMetrics.class);

    private final KafkaListenerEndpointRegistry endpointRegistry;
    private final MeterRegistry meterRegistry;
    private final AtomicLong maxLag = new AtomicLong(0);

    public ConsumerLagMetrics(KafkaListenerEndpointRegistry endpointRegistry, MeterRegistry meterRegistry) {
        this.endpointRegistry = endpointRegistry;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerGauge() {
        Gauge.builder("discoveryhub.consumer.lag", maxLag, AtomicLong::doubleValue)
                .description("Max Kafka consumer records-lag across all archive listener partitions (resiliency demo NFR-2)")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${discoveryhub.metrics.lag.interval-ms:15000}")
    public void refresh() {
        long lag = computeMaxLag();
        long previous = maxLag.getAndSet(lag);
        if (lag != previous) {
            log.info("consumer lag updated: max records-lag={} (was {})", lag, previous);
        }
    }

    /** Visible for testing. */
    long currentLag() {
        return maxLag.get();
    }

    private long computeMaxLag() {
        long max = 0;
        for (MessageListenerContainer container : endpointRegistry.getListenerContainers()) {
            if (!container.isRunning()) {
                continue;
            }
            Map<String, Map<MetricName, ? extends Metric>> clientMetrics = container.metrics();
            if (clientMetrics == null) {
                continue;
            }
            for (Map<MetricName, ? extends Metric> metrics : clientMetrics.values()) {
                for (Map.Entry<MetricName, ? extends Metric> entry : metrics.entrySet()) {
                    MetricName name = entry.getKey();
                    if (name != null && name.name() != null && name.name().startsWith("records-lag")) {
                        Object value = entry.getValue().metricValue();
                        if (value instanceof Number n) {
                            max = Math.max(max, n.doubleValue() >= 0 ? n.longValue() : 0);
                        }
                    }
                }
            }
        }
        return max;
    }
}
