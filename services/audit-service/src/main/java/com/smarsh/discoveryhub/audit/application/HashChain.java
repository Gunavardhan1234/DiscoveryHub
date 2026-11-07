package com.smarsh.discoveryhub.audit.application;

import com.smarsh.discoveryhub.audit.domain.model.AuditEntry;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Computes the per-case hash chain (architecture §2.6, FR-7.2):
 * {@code hash = SHA-256(prevHash + canonical(entry))} where canonical sorts
 * map keys so insertion order cannot change the hash. Pure Java — operates on
 * the domain {@link AuditEntry}, not the Mongo document.
 *
 * <p><b>SOLID — SRP:</b> one reason to change — the canonical form. Both the
 * writer ({@code AuditConsumeUseCase}) and the verifier
 * ({@code MongoAuditStoreAdapter.verifyChain}) call this same class, so the
 * hashing rule can never drift between "write a link" and "check a link".
 */
public class HashChain {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    public String hash(String prevHash, AuditEntry entry) {
        String canonical = String.join("|", entry.eventId(), String.valueOf(entry.occurredAt()), entry.actor(),
                entry.action(), entry.targetType(), entry.targetId(), String.valueOf(entry.caseId()),
                canonicalJson(entry.before()), canonicalJson(entry.after()), entry.correlationId());
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest((String.valueOf(prevHash) + canonical)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String canonicalJson(Object value) {
        if (value == null) return "null";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Audit event snapshot is not JSON serializable", e);
        }
    }

    public boolean verify(AuditEntry entry) {
        return hash(entry.prevHash(), entry).equals(entry.hash());
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append("%02x".formatted(value));
        return result.toString();
    }
}
