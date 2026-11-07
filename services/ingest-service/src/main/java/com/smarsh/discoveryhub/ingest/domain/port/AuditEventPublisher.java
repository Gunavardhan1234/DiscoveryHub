package com.smarsh.discoveryhub.ingest.domain.port;

import com.smarsh.discoveryhub.events.AuditEventPayload;

/**
 * Outbound port for emitting {@code audit.events.v1} (events.md §6). Called
 * inside the same logical operation as the change it records — after a
 * successful ingest publish.
 */
public interface AuditEventPublisher {

    void publish(AuditEventPayload payload, String kafkaKey);
}
