package com.smarsh.discoveryhub.ingest.domain.port;

import com.smarsh.discoveryhub.events.CommunicationEvent;

/**
 * Outbound port for publishing the {@code comm.ingest.v1} event. The
 * infrastructure adapter wraps the payload in an {@link
 * com.smarsh.discoveryhub.events.EventEnvelope} and sends it to Kafka with the
 * supplied {@code kafkaKey} (which is the {@code messageKey}, not the
 * {@code entityId}).
 */
public interface EventPublisher {

    void publishIngested(CommunicationEvent payload, String kafkaKey);
}
