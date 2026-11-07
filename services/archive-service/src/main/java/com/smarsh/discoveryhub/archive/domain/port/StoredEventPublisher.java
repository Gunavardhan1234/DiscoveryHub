package com.smarsh.discoveryhub.archive.domain.port;

import com.smarsh.discoveryhub.events.CommunicationEvent;

/**
 * Outbound port for publishing {@code comm.stored.v1} after a durable Mongo
 * upsert. The Kafka key is {@code messageId} (events.md §2).
 */
public interface StoredEventPublisher {

    void publishStored(CommunicationEvent payload, String kafkaKey);
}
