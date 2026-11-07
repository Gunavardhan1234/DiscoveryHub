package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.model.ExportJobStatus;
import com.smarsh.discoveryhub.export.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.CaseStatusPort;
import com.smarsh.discoveryhub.export.domain.port.ExportEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.ExportJobPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestExportUseCaseTest {

    private final ExportJobPort jobPort = mock(ExportJobPort.class);
    private final ExportEventPublisher publisher = mock(ExportEventPublisher.class);
    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);
    private final CaseStatusPort caseStatus = mock(CaseStatusPort.class);
    private final RequestExportUseCase useCase = new RequestExportUseCase(jobPort, publisher, auditPublisher, caseStatus);

    @Test
    void persistsQueuedJobPublishesEventAndAudits() {
        when(jobPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ExportJob job = useCase.execute("case-42", null);

        assertThat(job.status()).isEqualTo(ExportJobStatus.QUEUED);
        assertThat(job.progressPct()).isZero();

        ArgumentCaptor<com.smarsh.discoveryhub.events.ExportJob> event =
                ArgumentCaptor.forClass(com.smarsh.discoveryhub.events.ExportJob.class);
        verify(publisher).publishExportRequested(event.capture(), eq(job.id().toString()));
        assertThat(event.getValue().jobId()).isEqualTo(job.id().toString());

        ArgumentCaptor<AuditEventPayload> audit = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(auditPublisher).publish(audit.capture(), eq("case-42"));
        assertThat(audit.getValue().action()).isEqualTo("EXPORT_REQUESTED");
        assertThat(audit.getValue().targetId()).isEqualTo(job.id().toString());
    }

    @Test
    void rejectsExportForClosedCaseWithoutPersisting() {
        // FR-2.5: a closed case is read-only — the guard runs before a job is
        // saved, so nothing is persisted, published, or audited.
        UUID caseId = UUID.randomUUID();
        when(caseStatus.isClosed(caseId)).thenReturn(true);

        assertThatThrownBy(() -> useCase.execute(caseId.toString(), null))
                .isInstanceOf(com.smarsh.discoveryhub.common.ConflictException.class)
                .satisfies(ex -> assertThat(((com.smarsh.discoveryhub.common.ConflictException) ex).code())
                        .isEqualTo("CASE_CLOSED"));

        verify(jobPort, never()).save(any());
        verify(publisher, never()).publishExportRequested(any(), any());
        verify(auditPublisher, never()).publish(any(), any());
    }

    @Test
    void allowsExportForOpenCase() {
        UUID caseId = UUID.randomUUID();
        when(caseStatus.isClosed(caseId)).thenReturn(false);
        when(jobPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ExportJob job = useCase.execute(caseId.toString(), null);

        assertThat(job.status()).isEqualTo(ExportJobStatus.QUEUED);
        verify(jobPort).save(any());
    }

    @Test
    void holdScopedExportSkipsCaseStatusCheck() {
        // A hold-scoped export (no caseId) is not blocked by the closed-case
        // guard: the hold was already released by case.closed.v1 (FR-4.5).
        when(jobPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ExportJob job = useCase.execute(null, "hold-99");

        assertThat(job.status()).isEqualTo(ExportJobStatus.QUEUED);
        verify(caseStatus, never()).isClosed(any());
    }
}
