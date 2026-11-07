package com.smarsh.discoveryhub.ingest.config;

import com.smarsh.discoveryhub.ingest.application.CorpusGenerationUseCase;
import com.smarsh.discoveryhub.ingest.application.IngestMessageUseCase;
import com.smarsh.discoveryhub.ingest.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.ingest.domain.port.ClaimCheckPort;
import com.smarsh.discoveryhub.ingest.domain.port.EventPublisher;
import com.smarsh.discoveryhub.ingest.domain.port.IngestDedupePort;
import org.apache.kafka.clients.admin.NewTopic;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IngestConfigTest {

    private final IngestConfig config = new IngestConfig();

    @Test
    void wiresIngestMessageUseCaseWithPortsAndSourceSystem() {
        IngestMessageUseCase useCase = config.ingestMessageUseCase(
                mock(IngestDedupePort.class),
                mock(EventPublisher.class),
                mock(AuditEventPublisher.class),
                mock(ClaimCheckPort.class),
                "smarsh-import");

        assertThat(useCase).isNotNull();
    }

    @Test
    void wiresCorpusGenerationUseCaseAroundIngest() {
        IngestMessageUseCase ingest = mock(IngestMessageUseCase.class);

        CorpusGenerationUseCase generate = config.corpusGenerationUseCase(ingest);

        assertThat(generate).isNotNull();
    }
}
