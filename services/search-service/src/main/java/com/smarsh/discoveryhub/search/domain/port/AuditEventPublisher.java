package com.smarsh.discoveryhub.search.domain.port;

import com.smarsh.discoveryhub.events.AuditEventPayload;

/**
 * Outbound port for emitting {@code audit.events.v1} from search-service
 * (FR-7.1: "search executed").
 *
 * <p><b>SOLID — DIP + ISP:</b> a single-method abstraction over "tell the
 * audit trail something happened". {@code SearchService} depends on this, not
 * on {@code KafkaTemplate}, so the audit rule (every executed search is
 * recorded, keyed by case id to keep the per-case hash chain contiguous) is
 * unit-testable with a Mockito mock and no broker. Swapping Kafka for an
 * outbox table or an HTTP sink would be a new adapter, not a change here.
 */
public interface AuditEventPublisher {

    void publish(AuditEventPayload payload, String kafkaKey);
}
