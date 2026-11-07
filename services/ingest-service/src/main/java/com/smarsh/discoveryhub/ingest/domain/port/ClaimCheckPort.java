package com.smarsh.discoveryhub.ingest.domain.port;

/**
 * Claim-check port (architecture §2.1, Day-2 hardening). When an attachment is
 * large enough that carrying its bytes through Kafka would bloat the event, the
 * ingest use case externalizes the bytes to blob storage and publishes only a
 * reference (the "claim check": {@code s3Key} + {@code sha256}) in
 * {@code comm.ingest.v1}. The archive consumer later resolves the reference to
 * fetch the bytes — the event stays small, the broker stays fast.
 *
 * <p>Hexagonal boundary: the use case depends on this port, not on the AWS SDK.
 * The adapter ({@code S3ClaimCheckAdapter}) is the only place that knows S3
 * exists.
 */
public interface ClaimCheckPort {

    /**
     * Store the attachment content (if any) and return its claim check.
     *
     * @param attachmentId    stable attachment id (used to build a deterministic s3 key)
     * @param filename        original filename (preserved as the object key suffix)
     * @param contentType     MIME type, stored as the object content-type
     * @param size            declared size in bytes
     * @param contentBase64  base64-encoded bytes, or {@code null} when the client
     *                       sent metadata only (no inline content) — in which case
     *                       no externalization happens and an empty claim check
     *                       is returned
     * @return a {@link ClaimCheck} carrying {@code s3Key} and {@code sha256};
     *         both are {@code null} when nothing was externalized
     */
    ClaimCheck externalize(String attachmentId, String filename, String contentType,
                           long size, String contentBase64);

    record ClaimCheck(String s3Key, String sha256) {
        public static ClaimCheck empty() { return new ClaimCheck(null, null); }
        public boolean stored() { return s3Key != null; }
    }
}
