package com.smarsh.discoveryhub.audit.config;

import com.smarsh.discoveryhub.audit.application.AuditConsumeUseCase;
import com.smarsh.discoveryhub.audit.application.HashChain;
import com.smarsh.discoveryhub.audit.domain.port.AuditStorePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuditConfig {

    @Bean
    HashChain hashChain() {
        return new HashChain();
    }

    @Bean
    AuditConsumeUseCase auditConsumeUseCase(AuditStorePort store, HashChain hashChain) {
        return new AuditConsumeUseCase(store, hashChain);
    }
}
