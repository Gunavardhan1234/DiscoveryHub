package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.model.ExportJobStatus;
import com.smarsh.discoveryhub.export.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.ExportEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.ExportJobPort;
import com.smarsh.discoveryhub.export.domain.port.ExportProgressPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-6.6: a FAILED export job must be retryable without minting a new job id
 * or producing a corrupt/duplicate package.
 */
class RetryExportUseCaseTest {

    private final ExportJobPort jobPort = mock(ExportJobPort.class);
    private final ExportEventPublisher eventPublisher = mock(ExportEventPublisher.class);
    private final ExportProgressPublisher progressPublisher = mock(ExportProgressPublisher.class);
    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);
    private final RetryExportUseCase useCase =
            new RetryExportUseCase(jobPort, eventPublisher, progressPublisher, auditPublisher);

    @Test
    void retryingFailedJobRequeuesSameIdAndRepublishes() {
        UUID jobId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(60);
        ExportJob failed = new ExportJob(jobId, "case-1", null, ExportJobStatus.FAILED,
                40, null, null, "archive-service unreachable", createdAt, Instant.now());
        when(jobPort.findById(jobId)).thenReturn(Optional.of(failed));
        when(jobPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ExportJob result = useCase.execute(jobId);

        assertThat(result.id()).isEqualTo(jobId);
        assertThat(result.status()).isEqualTo(ExportJobStatus.QUEUED);
        assertThat(result.progressPct()).isZero();
        assertThat(result.error()).isNull();

        ArgumentCaptor<com.smarsh.discoveryhub.events.ExportJob> event =
                ArgumentCaptor.forClass(com.smarsh.discoveryhub.events.ExportJob.class);
        verify(eventPublisher).publishExportRequested(event.capture(), eq(jobId.toString()));
        assertThat(event.getValue().jobId()).isEqualTo(jobId.toString());

        verify(progressPublisher).publish(result);

        ArgumentCaptor<AuditEventPayload> audit = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(auditPublisher).publish(audit.capture(), eq("case-1"));
        assertThat(audit.getValue().action()).isEqualTo("EXPORT_RETRIED");
        assertThat(audit.getValue().targetId()).isEqualTo(jobId.toString());
    }

    @Test
    void retryingNonFailedJobThrowsConflict() {
        UUID jobId = UUID.randomUUID();
        ExportJob completed = new ExportJob(jobId, "case-1", null, ExportJobStatus.COMPLETED,
                100, "exports/x/package.zip", "abc123", null, Instant.now(), Instant.now());
        when(jobPort.findById(jobId)).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> useCase.execute(jobId))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).code()).isEqualTo("EXPORT_NOT_FAILED"));

        verify(jobPort, never()).save(any());
        verify(eventPublisher, never()).publishExportRequested(any(), any());
    }

    @Test
    void retryingUnknownJobThrowsNotFound() {
        UUID jobId = UUID.randomUUID();
        when(jobPort.findById(jobId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(jobId)).isInstanceOf(NotFoundException.class);
    }
}
