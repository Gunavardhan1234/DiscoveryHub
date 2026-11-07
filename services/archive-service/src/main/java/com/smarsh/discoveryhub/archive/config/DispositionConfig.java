package com.smarsh.discoveryhub.archive.config;

import com.smarsh.discoveryhub.archive.application.DispositionExecutor;
import com.smarsh.discoveryhub.archive.application.RetentionGuard;
import com.smarsh.discoveryhub.archive.domain.port.AttachmentCleaner;
import com.smarsh.discoveryhub.archive.domain.port.DispositionStorePort;
import com.smarsh.discoveryhub.common.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the disposition executor (FR-5). Additive M4 wiring like
 * {@link HoldApplierConfig}/{@link RetentionGuardConfig} — no existing bean is
 * touched. {@link #s3AttachmentCleaner} deletes the real S3 objects (via the
 * {@link BlobStore} adapter — {@code ArchiveS3StorageAdapter}); the logging
 * {@link #loggingAttachmentCleaner} only backs it off via
 * {@code @ConditionalOnMissingBean} as a defensive fallback (e.g. a context
 * that never wires a {@link BlobStore} bean, such as a narrower test slice).
 */
@Configuration
public class DispositionConfig {

    private static final Logger log = LoggerFactory.getLogger(DispositionConfig.class);

    @Bean
    DispositionExecutor dispositionExecutor(DispositionStorePort store, RetentionGuard guard,
                                            AttachmentCleaner attachments,
                                            @Value("${discoveryhub.disposition.page-size:500}") int pageSize) {
        return new DispositionExecutor(store, guard, attachments, pageSize);
    }

    @Bean
    AttachmentCleaner s3AttachmentCleaner(BlobStore blobStore,
            @Value("${discoveryhub.archive.attachments-bucket:discoveryhub-attachments}") String bucket) {
        return s3Keys -> {
            for (String key : s3Keys) {
                try {
                    blobStore.delete(bucket, key);
                } catch (Exception e) {
                    log.warn("Failed to delete attachment blob {} from bucket {}", key, bucket, e);
                }
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(AttachmentCleaner.class)
    AttachmentCleaner loggingAttachmentCleaner() {
        return s3Keys -> log.info("No BlobStore bean available; attachment cleanup skipped for keys: {}", s3Keys);
    }
}
