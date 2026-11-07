package com.smarsh.discoveryhub.audit.infrastructure.persistence;

import com.smarsh.discoveryhub.audit.domain.model.AuditEntry;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

/**
 * Mongo document for {@code audit_events}. Append-only by construction: the
 * service exposes no update/delete endpoint, and the Mongo user has
 * {@code insert}+{@code find} only (architecture §2.6).
 */
@Document("audit_events")
public record AuditEvent(
        @Id String eventId, Instant occurredAt, String actor, String action, String targetType,
        String targetId, String caseId, Object before, Object after, String correlationId,
        String prevHash, String hash) {

    public static AuditEvent fromDomain(AuditEntry entry) {
        return new AuditEvent(
                entry.eventId(), entry.occurredAt(), entry.actor(), entry.action(),
                entry.targetType(), entry.targetId(), entry.caseId(), entry.before(), entry.after(),
                entry.correlationId(), entry.prevHash(), entry.hash());
    }

    public AuditEntry toDomain() {
        return new AuditEntry(eventId, occurredAt, actor, action, targetType, targetId,
                caseId, before, after, correlationId, prevHash, hash);
    }
}
