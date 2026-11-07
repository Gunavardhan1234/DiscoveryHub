package com.smarsh.discoveryhub.audit.config;

import com.smarsh.discoveryhub.audit.application.AuditConsumeUseCase;
import com.smarsh.discoveryhub.audit.application.HashChain;
import com.smarsh.discoveryhub.audit.domain.port.AuditStorePort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuditConfigTest {

    private final AuditConfig config = new AuditConfig();

    @Test
    void hashChainBeanIsUsable() {
        assertThat(config.hashChain()).isNotNull();
    }

    @Test
    void auditConsumeUseCaseBeanWiresStoreAndHashChain() {
        AuditStorePort store = mock(AuditStorePort.class);
        HashChain hashChain = config.hashChain();

        AuditConsumeUseCase useCase = config.auditConsumeUseCase(store, hashChain);

        assertThat(useCase).isNotNull();
    }
}
