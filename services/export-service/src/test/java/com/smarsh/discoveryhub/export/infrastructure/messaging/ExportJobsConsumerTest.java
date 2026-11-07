package com.smarsh.discoveryhub.export.infrastructure.messaging;

import com.smarsh.discoveryhub.export.application.ExportWorkerUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExportJobsConsumerTest {

    private final ExportWorkerUseCase worker = mock(ExportWorkerUseCase.class);
    private final ExportJobsConsumer consumer = new ExportJobsConsumer(
            new ObjectMapper().registerModule(new JavaTimeModule()), worker);

    @Test
    void parsesEnvelopeAndDelegatesJobId() {
        UUID jobId = UUID.randomUUID();
        String value = """
                {"eventId":"%s","eventType":"EXPORT_REQUESTED","schemaVersion":1,
                 "occurredAt":"2026-09-09T10:00:00Z","entityId":"%s","correlationId":"corr-1",
                 "actor":"investigator@demo","payload":{"jobId":"%s","caseId":"case-42","holdId":null}}
                """.formatted(UUID.randomUUID(), jobId, jobId);

        consumer.onExportJob(value);

        verify(worker).process(jobId);
    }

    @Test
    void unparseablePayloadThrowsSerializationExceptionForDlt() {
        assertThatThrownBy(() -> consumer.onExportJob("{not json"))
                .isInstanceOf(SerializationException.class);
    }
}
