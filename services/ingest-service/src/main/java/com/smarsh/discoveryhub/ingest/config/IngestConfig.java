package com.smarsh.discoveryhub.ingest.config;

import com.smarsh.discoveryhub.ingest.application.CorpusGenerationUseCase;
import com.smarsh.discoveryhub.ingest.application.IngestMessageUseCase;
import com.smarsh.discoveryhub.ingest.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.ingest.domain.port.ClaimCheckPort;
import com.smarsh.discoveryhub.ingest.domain.port.EventPublisher;
import com.smarsh.discoveryhub.ingest.domain.port.IngestDedupePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestConfig {

    @Bean
    IngestMessageUseCase ingestMessageUseCase(
            IngestDedupePort dedupePort,
            EventPublisher publisher,
            AuditEventPublisher auditPublisher,
            ClaimCheckPort claimCheckPort,
            @Value("${discoveryhub.ingest.source-system:discoveryhub}") String sourceSystem) {
        return new IngestMessageUseCase(dedupePort, publisher, auditPublisher, claimCheckPort, sourceSystem);
    }

    @Bean
    CorpusGenerationUseCase corpusGenerationUseCase(IngestMessageUseCase ingestUseCase) {
        return new CorpusGenerationUseCase(ingestUseCase);
    }
}
