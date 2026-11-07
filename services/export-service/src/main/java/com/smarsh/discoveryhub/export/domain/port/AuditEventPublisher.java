package com.smarsh.discoveryhub.export.domain.port;

import com.smarsh.discoveryhub.events.AuditEventPayload;

/** Outbound port for publishing {@code audit.events.v1} (FR-7: every export action is audited). */
public interface AuditEventPublisher {

    void publish(AuditEventPayload payload, String kafkaKey);
}
