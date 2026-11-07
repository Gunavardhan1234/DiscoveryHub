package com.smarsh.discoveryhub.archive.domain.port;

import java.util.List;

/**
 * Port for deleting attachment blobs after a message is disposed (FR-5).
 * Bound to a real S3 deletion (via the {@code BlobStore} adapter,
 * {@code ArchiveS3StorageAdapter}) in {@code DispositionConfig}; a
 * logging-only fallback backs off automatically if no {@code BlobStore} bean
 * is present (e.g. a narrower test slice), so the executor never depends on
 * infrastructure wiring directly.
 */
public interface AttachmentCleaner {

    void deleteAll(List<String> s3Keys);
}
