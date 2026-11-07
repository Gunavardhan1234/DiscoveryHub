package com.smarsh.discoveryhub.events;

import java.time.Instant;

public record AuditEventPayload(
        String eventId,
        Instant occurredAt,
        String actor,
        String action,
        String targetType,
        String targetId,
        String caseId,
        String before,
        String after,
        String correlationId) {
}
