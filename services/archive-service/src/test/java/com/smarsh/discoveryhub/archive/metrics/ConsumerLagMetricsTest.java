package com.smarsh.discoveryhub.archive.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsumerLagMetricsTest {

    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ConsumerLagMetrics lagMetrics = new ConsumerLagMetrics(registry, meterRegistry);

    private MetricName metricName(String name) {
        return new MetricName(name, "group", "desc", Map.of());
    }

    private Metric metric(MetricName name, double value) {
        Metric m = mock(Metric.class);
        when(m.metricValue()).thenReturn(value);
        return m;
    }

    @Test
    void registerGaugeExposesTheDiscoveryhubConsumerLagGauge() {
        lagMetrics.registerGauge();

        assertThat(meterRegistry.find("discoveryhub.consumer.lag").gauge()).isNotNull();
    }

    @Test
    void refreshUpdatesTheGaugeToTheMaxRecordsLagAcrossContainers() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        MetricName lagMetric = metricName("records-lag-max");
        Metric lagValue = metric(lagMetric, 42.0);
        Map<String, Map<MetricName, ? extends Metric>> clientMetrics = Map.of("client-1", Map.of(lagMetric, lagValue));
        when(container.metrics()).thenReturn(clientMetrics);
        when(registry.getListenerContainers()).thenReturn(List.of(container));

        lagMetrics.refresh();

        assertThat(lagMetrics.currentLag()).isEqualTo(42L);
    }

    @Test
    void refreshIgnoresStoppedContainers() {
        MessageListenerContainer stopped = mock(MessageListenerContainer.class);
        when(stopped.isRunning()).thenReturn(false);
        when(registry.getListenerContainers()).thenReturn(List.of(stopped));

        lagMetrics.refresh();

        assertThat(lagMetrics.currentLag()).isZero();
    }

    @Test
    void refreshIgnoresContainersWithNullMetrics() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        when(container.metrics()).thenReturn(null);
        when(registry.getListenerContainers()).thenReturn(List.of(container));

        lagMetrics.refresh();

        assertThat(lagMetrics.currentLag()).isZero();
    }

    @Test
    void refreshIgnoresMetricsThatAreNotRecordsLag() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        MetricName other = metricName("bytes-consumed-total");
        Metric otherValue = metric(other, 999.0);
        Map<String, Map<MetricName, ? extends Metric>> clientMetrics = Map.of("client-1", Map.of(other, otherValue));
        when(container.metrics()).thenReturn(clientMetrics);
        when(registry.getListenerContainers()).thenReturn(List.of(container));

        lagMetrics.refresh();

        assertThat(lagMetrics.currentLag()).isZero();
    }

    @Test
    void refreshIgnoresNegativeMetricValues() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        MetricName lagMetric = metricName("records-lag");
        Metric negativeValue = metric(lagMetric, -1.0);
        Map<String, Map<MetricName, ? extends Metric>> clientMetrics = Map.of("client-1", Map.of(lagMetric, negativeValue));
        when(container.metrics()).thenReturn(clientMetrics);
        when(registry.getListenerContainers()).thenReturn(List.of(container));

        lagMetrics.refresh();

        assertThat(lagMetrics.currentLag()).isZero();
    }

    @Test
    void refreshTakesTheMaxAcrossMultipleContainersAndClients() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        MetricName lag1 = metricName("records-lag");
        MetricName lag2 = metricName("records-lag-max");
        Metric value1 = metric(lag1, 10.0);
        Metric value2 = metric(lag2, 75.0);
        Map<String, Map<MetricName, ? extends Metric>> clientMetrics = Map.of(
                "client-1", Map.of(lag1, value1),
                "client-2", Map.of(lag2, value2));
        when(container.metrics()).thenReturn(clientMetrics);
        when(registry.getListenerContainers()).thenReturn(List.of(container));

        lagMetrics.refresh();

        assertThat(lagMetrics.currentLag()).isEqualTo(75L);
    }
}
