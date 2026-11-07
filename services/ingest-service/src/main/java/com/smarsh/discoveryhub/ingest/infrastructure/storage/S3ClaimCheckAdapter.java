package com.smarsh.discoveryhub.ingest.infrastructure.storage;

import com.smarsh.discoveryhub.common.BlobStore;
import com.smarsh.discoveryhub.ingest.domain.port.ClaimCheckPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * {@link ClaimCheckPort} adapter over the shared {@link BlobStore} (LocalStack S3
 * in dev). Externalizes attachments whose declared size exceeds the configured
 * threshold ({@code discoveryhub.ingest.claim-check.threshold-bytes}, default
 * 256 KiB); smaller attachments stay inline and return an empty claim check.
 *
 * <p>The s3 key is deterministic: {@code attachments/<messageKey-safe
 * prefix>/<attachmentId>/<sha256-prefix>-<filename>}, so a re-ingest of the same
 * attachment writes the same object (idempotent under re-delivery) and the event
 * stays small enough for Kafka's default max.message.bytes.
 */
@Component
public class S3ClaimCheckAdapter implements ClaimCheckPort {

    private static final Logger log = LoggerFactory.getLogger(S3ClaimCheckAdapter.class);

    private final BlobStore blobStore;
    private final String bucket;
    private final long thresholdBytes;

    public S3ClaimCheckAdapter(BlobStore blobStore,
                               @Value("${discoveryhub.ingest.attachment-bucket:discoveryhub-attachments}") String bucket,
                               @Value("${discoveryhub.ingest.claim-check.threshold-bytes:262144}") long thresholdBytes) {
        this.blobStore = blobStore;
        this.bucket = bucket;
        this.thresholdBytes = thresholdBytes;
    }

    @Override
    public ClaimCheck externalize(String attachmentId, String filename, String contentType,
                                  long size, String contentBase64) {
        if (contentBase64 == null || contentBase64.isBlank()) {
            return ClaimCheck.empty();
        }
        if (size < thresholdBytes) {
            log.debug("attachment {} ({} bytes) below claim-check threshold {} — kept inline",
                    attachmentId, size, thresholdBytes);
            return ClaimCheck.empty();
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("attachment " + attachmentId + " has invalid base64 content", e);
        }
        String sha256 = sha256Hex(bytes);
        String safeName = filename == null || filename.isBlank() ? "blob" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        String s3Key = "attachments/" + attachmentId + "/" + sha256.substring(0, 12) + "-" + safeName;
        blobStore.put(bucket, s3Key, bytes, contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
        log.info("claim-check: externalized attachment {} ({} bytes) to s3://{}/{} sha256={}",
                attachmentId, size, bucket, s3Key, sha256);
        return new ClaimCheck(s3Key, "sha256:" + sha256);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Visible for testing. */
    long thresholdBytes() {
        return thresholdBytes;
    }
}
