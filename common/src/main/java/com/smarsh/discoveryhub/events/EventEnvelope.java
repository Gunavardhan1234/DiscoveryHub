package com.smarsh.discoveryhub.events;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String entityId,
        String correlationId,
        String actor,
        T payload) {
    public EventEnvelope {
        if (eventId == null) eventId = UUID.randomUUID();
        if (occurredAt == null) occurredAt = Instant.now();
        if (schemaVersion < 1) schemaVersion = 1;
        if (correlationId == null) correlationId = "";
        if (actor == null) actor = "system";
        if (eventType == null) eventType = "";
    }
}
