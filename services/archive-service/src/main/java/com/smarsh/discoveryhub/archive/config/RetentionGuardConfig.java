package com.smarsh.discoveryhub.archive.config;

import com.smarsh.discoveryhub.archive.application.RetentionGuard;
import com.smarsh.discoveryhub.archive.domain.port.MessageDeletionEventPublisher;
import com.smarsh.discoveryhub.archive.domain.port.MessageDeletionPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link RetentionGuard} protection proxy over the guarded Mongo
 * delete (FR-4.2/FR-4.6). Kept separate from {@link ArchiveConfig} so the
 * ingest/durability wiring is untouched; purely additive M4 wiring like
 * {@link HoldApplierConfig}.
 */
@Configuration
public class RetentionGuardConfig {

    @Bean
    RetentionGuard retentionGuard(MessageDeletionPort store, MessageDeletionEventPublisher events) {
        return new RetentionGuard(store, events);
    }
}
