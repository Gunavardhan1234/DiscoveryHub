package com.smarsh.discoveryhub.archive.config;

import com.smarsh.discoveryhub.archive.application.HoldApplierUseCase;
import com.smarsh.discoveryhub.archive.domain.port.HoldAppliedPublisher;
import com.smarsh.discoveryhub.archive.domain.port.HoldStorePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the hold-applier use case (FR-4.4/FR-4.5). Kept separate from
 * {@link ArchiveConfig} so the ingest/durability wiring is untouched; this is
 * purely additive for the M4 hold-applier contribution.
 */
@Configuration
public class HoldApplierConfig {

    @Bean
    HoldApplierUseCase holdApplierUseCase(HoldStorePort holdStore, HoldAppliedPublisher publisher,
                                          @Value("${discoveryhub.hold.applier.batch-size:200}") int batchSize) {
        return new HoldApplierUseCase(holdStore, publisher, batchSize);
    }
}
