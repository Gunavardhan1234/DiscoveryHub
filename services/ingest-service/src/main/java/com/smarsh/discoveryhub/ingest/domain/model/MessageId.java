package com.smarsh.discoveryhub.ingest.domain.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/**
 * The immutable message identifier (FR-1.5, architecture &sect;2.1):
 * {@code messageId = UUIDv5(namespace, messageKey)} — deterministic, so a
 * replay of the {@code comm.ingest.v1} topic reproduces identical ids.
 *
 * <p>Java's {@link UUID#nameUUIDFromBytes} is UUIDv3 (MD5); this implements
 * UUIDv5 (SHA-1, version 5, IETF variant) per RFC 4122 &sect;4.3.
 */
public record MessageId(UUID value) {

    /** Fixed project namespace for UUIDv5 derivation. */
    public static final UUID NAMESPACE = UUID.fromString("a45bf3b2-7c1e-4f2d-9b6a-1e0c2d4b6e8f");

    public static MessageId of(MessageKey messageKey) {
        return of(NAMESPACE, messageKey.value());
    }

    public static MessageId of(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(toBytes(namespace));
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = sha1.digest();
            byte[] uuidBytes = Arrays.copyOf(hash, 16);
            uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0f) | 0x50); // version 5
            uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3f) | 0x80); // variant IETF (10xx)
            ByteBuffer bb = ByteBuffer.wrap(uuidBytes).order(ByteOrder.BIG_ENDIAN);
            return new MessageId(new UUID(bb.getLong(), bb.getLong()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}
