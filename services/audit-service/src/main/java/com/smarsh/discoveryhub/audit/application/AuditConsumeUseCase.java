package com.smarsh.discoveryhub.audit.application;

import com.smarsh.discoveryhub.audit.domain.model.AuditEntry;
import com.smarsh.discoveryhub.audit.domain.port.AuditStorePort;
import com.smarsh.discoveryhub.events.AuditEventPayload;

import java.time.temporal.ChronoUnit;

/**
 * Consumes an {@link AuditEventPayload} from Kafka, computes the per-case hash
 * chain link ({@code hash = SHA-256(prevHash + canonical(entry))}), and
 * appends it to the store. The previous hash is the latest hash for the same
 * {@code caseId} (or {@code GENESIS} for the first entry), so each case's chain
 * is contiguous (architecture §2.6).
 *
 * <p>{@code occurredAt} is truncated to millisecond precision <em>before</em>
 * hashing. {@code Instant.now()} on most JVMs carries microsecond/nanosecond
 * precision, but MongoDB's BSON {@code Date} only stores milliseconds — so a
 * hash computed over the untruncated instant would never re-verify once the
 * entry round-trips through Mongo. Truncating here keeps the hashed value and
 * the persisted value identical.
 *
 * <p><b>SOLID — SRP + OOP composition:</b> orchestrates two collaborators —
 * {@code AuditStorePort} (where the chain tail lives) and {@link HashChain}
 * (how to hash) — and contains nothing else, so storage and cryptography
 * evolve independently of the consume flow.
 */
public class AuditConsumeUseCase {

    private final AuditStorePort store;
    private final HashChain hashChain;

    public AuditConsumeUseCase(AuditStorePort store, HashChain hashChain) {
        this.store = store;
        this.hashChain = hashChain;
    }

    public void accept(AuditEventPayload payload) {
        String prevHash = store.latestHashForCase(payload.caseId());
        AuditEntry entry = new AuditEntry(
                payload.eventId(),
                payload.occurredAt().truncatedTo(ChronoUnit.MILLIS),
                payload.actor(),
                payload.action(),
                payload.targetType(),
                payload.targetId(),
                payload.caseId(),
                payload.before(),
                payload.after(),
                payload.correlationId(),
                prevHash,
                null);
        String hash = hashChain.hash(prevHash, entry);
        store.append(new AuditEntry(
                entry.eventId(), entry.occurredAt(), entry.actor(), entry.action(),
                entry.targetType(), entry.targetId(), entry.caseId(), entry.before(), entry.after(),
                entry.correlationId(), prevHash, hash));
    }
}
