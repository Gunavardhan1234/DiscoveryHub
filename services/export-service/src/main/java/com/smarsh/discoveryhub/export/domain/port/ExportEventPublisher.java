package com.smarsh.discoveryhub.export.domain.port;

/**
 * Outbound port for publishing the {@code export.jobs.v1} event. The Kafka
 * adapter wraps the payload in an {@code EventEnvelope} and sends it with the
 * {@code jobId} as the Kafka key (events.md §5).
 */
public interface ExportEventPublisher {

    void publishExportRequested(com.smarsh.discoveryhub.events.ExportJob payload, String kafkaKey);
}
