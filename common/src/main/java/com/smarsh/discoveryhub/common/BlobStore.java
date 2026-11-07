package com.smarsh.discoveryhub.common;

/**
 * Adapter interface over blob storage (S3 / LocalStack). Kept in {@code common}
 * so both archive-service (attachments) and export-service (packages) depend on
 * the abstraction, not the AWS SDK — the Dependency Inversion talking point
 * (architecture §2.1, execution-plan M5). No AWS types leak into this interface.
 */
public interface BlobStore {

    /** Stores the given bytes under {@code key} and returns the key (for event payloads). */
    String put(String bucket, String key, byte[] content, String contentType);

    /** Fetches the bytes stored under {@code key}, or null if absent. */
    byte[] get(String bucket, String key);

    /** Mints a pre-signed GET URL valid for {@code ttlSeconds}. */
    String presignedUrl(String bucket, String key, long ttlSeconds);

    /**
     * Deletes the object at {@code key}; a no-op if it does not exist (FR-5,
     * disposition attachment cleanup — execution-plan Day 3 M4).
     */
    void delete(String bucket, String key);

    /**
     * Checks whether an object exists at {@code key} without downloading it.
     * The default implementation falls back to a {@link #get} + catch, but
     * adapters should override with a lightweight HEAD request.
     */
    default boolean exists(String bucket, String key) {
        try {
            get(bucket, key);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
