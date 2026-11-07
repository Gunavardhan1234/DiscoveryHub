package com.smarsh.discoveryhub.ingest.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The deterministic idempotency digest for an ingested message
 * (FR-1.6, architecture &sect;2.1):
 * <pre>
 *   messageKey = SHA-256( sourceSystem | externalId | canonicalBody
 *                        | sortedParticipants | timestamp )
 * </pre>
 * where {@code sortedParticipants} are sorted by address so that reordering in
 * a request cannot change the key. Two guards rely on this being stable:
 * ingest-service's {@code ingest_dedupe} table and archive-service's Mongo
 * unique index.
 *
 * <p>Pure Java — no framework I/O — so the determinism rule is unit-testable
 * without a Spring context.
 */
public record MessageKey(String value) {

    public static final String PREFIX = "sha256:";

    public static MessageKey of(
            String sourceSystem,
            String externalId,
            String canonicalBody,
            List<Participant> participants,
            Instant timestamp) {
        String sorted = participants.stream()
                .sorted(Comparator.comparing(Participant::address, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(p -> nullToEmpty(p.name()) + "|" + nullToEmpty(p.address()) + "|" + nullToEmpty(p.role()))
                .collect(Collectors.joining(","));
        String canonical = String.join("|",
                nullToEmpty(sourceSystem),
                nullToEmpty(externalId),
                nullToEmpty(canonicalBody),
                sorted,
                timestamp.toString());
        return new MessageKey(PREFIX + sha256Hex(canonical));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append("%02x".formatted(b));
        return out.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
