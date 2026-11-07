package com.smarsh.discoveryhub.hold.domain.port;

import com.smarsh.discoveryhub.events.AuditEventPayload;

/**
 * Outbound port for emitting {@code audit.events.v1} from hold-service.
 */
public interface AuditEventPublisher {

    void publish(AuditEventPayload payload, String kafkaKey);
}
