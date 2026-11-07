package com.smarsh.discoveryhub.generator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 helpers for deterministic id derivation.
 */
public final class Hashes {

    private Hashes() {}

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append("%02x".formatted(b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Deterministic externalMessageId = sha256(custodian|thread|sentAt|body), prefixed ext-. */
    public static String externalId(String custodian, String threadId, String sentAt, String body) {
        return "ext-" + sha256Hex(custodian + "|" + threadId + "|" + sentAt + "|" + body).substring(0, 16);
    }
}
