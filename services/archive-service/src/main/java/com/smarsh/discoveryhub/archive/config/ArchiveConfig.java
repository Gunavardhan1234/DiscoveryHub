package com.smarsh.discoveryhub.archive.config;

import com.smarsh.discoveryhub.archive.application.IngestConsumeUseCase;
import com.smarsh.discoveryhub.archive.domain.port.MessageStorePort;
import com.smarsh.discoveryhub.archive.domain.port.StoredEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ArchiveConfig {

    @Bean
    IngestConsumeUseCase ingestConsumeUseCase(MessageStorePort messageStore, StoredEventPublisher publisher) {
        return new IngestConsumeUseCase(messageStore, publisher);
    }
}
