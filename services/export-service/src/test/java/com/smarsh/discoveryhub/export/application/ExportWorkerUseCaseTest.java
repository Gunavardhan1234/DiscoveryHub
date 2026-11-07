package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.common.BlobStore;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.export.domain.model.ArchiveMessage;
import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.model.ExportJobStatus;
import com.smarsh.discoveryhub.export.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.EvidenceSourcePort;
import com.smarsh.discoveryhub.export.domain.port.ExportJobPort;
import com.smarsh.discoveryhub.export.domain.port.ExportProgressPublisher;
import com.smarsh.discoveryhub.export.domain.port.HoldScopeSourcePort;
import com.smarsh.discoveryhub.export.domain.port.MessageSourcePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportWorkerUseCaseTest {

    private final ExportJobPort jobPort = mock(ExportJobPort.class);
    private final EvidenceSourcePort evidenceSource = mock(EvidenceSourcePort.class);
    private final HoldScopeSourcePort holdScopeSource = mock(HoldScopeSourcePort.class);
    private final MessageSourcePort messageSource = mock(MessageSourcePort.class);
    private final BlobStore blobStore = mock(BlobStore.class);
    private final ExportProgressPublisher progressPublisher = mock(ExportProgressPublisher.class);
    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);
    private final List<ExportJob> saved = new ArrayList<>();

    private final ExportWorkerUseCase worker = new ExportWorkerUseCase(
            jobPort, evidenceSource, holdScopeSource, messageSource, blobStore,
            () -> new ZipExportPackager(new ManifestBuilder()),
            progressPublisher, auditPublisher, "attachments-bucket", "exports-bucket");

    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void saveEchoes() {
        when(jobPort.save(any())).thenAnswer(invocation -> {
            ExportJob job = invocation.getArgument(0);
            saved.add(job);
            return job;
        });
    }

    @Test
    void packagesEvidenceAndCompletes() {
        when(jobPort.findById(jobId)).thenReturn(Optional.of(ExportJob.queued(jobId, "case-42", null)));
        when(evidenceSource.evidenceMessageIds("case-42")).thenReturn(List.of("m-1"));
        when(messageSource.fetchMessages(List.of("m-1"))).thenReturn(List.of(new ArchiveMessage(
                "m-1", "{\"id\":\"m-1\"}".getBytes(StandardCharsets.UTF_8),
                List.of(new ArchiveMessage.AttachmentRef("a.txt", "attachments/m-1/a.txt")))));
        when(blobStore.get("attachments-bucket", "attachments/m-1/a.txt"))
                .thenReturn("hello".getBytes(StandardCharsets.UTF_8));

        worker.process(jobId);

        String expectedKey = "exports/" + jobId + "/package.zip";
        ArgumentCaptor<byte[]> zipCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(blobStore).put(eq("exports-bucket"), eq(expectedKey), zipCaptor.capture(), eq("application/zip"));

        ExportJob terminal = saved.getLast();
        assertThat(terminal.status()).isEqualTo(ExportJobStatus.COMPLETED);
        assertThat(terminal.progressPct()).isEqualTo(100);
        assertThat(terminal.packageS3Key()).isEqualTo(expectedKey);
        assertThat(terminal.packageSha256()).isEqualTo(ManifestBuilder.sha256Hex(zipCaptor.getValue()));
        assertThat(saved).extracting(ExportJob::status).contains(ExportJobStatus.RUNNING);
        // every save is observed (SSE Observer), terminal state included
        verify(progressPublisher, org.mockito.Mockito.times(saved.size())).publish(any());
        // the finished package verifies end-to-end
        assertThat(new PackageVerifier().verify(zipCaptor.getValue()).valid()).isTrue();

        ArgumentCaptor<AuditEventPayload> audit = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(auditPublisher).publish(audit.capture(), eq("case-42"));
        assertThat(audit.getValue().action()).isEqualTo("EXPORT_COMPLETED");
    }

    @Test
    void completedJobRedeliveryIsNoOp() {
        when(jobPort.findById(jobId)).thenReturn(Optional.of(
                ExportJob.queued(jobId, "case-42", null).completed("exports/x/package.zip", "sha")));

        worker.process(jobId);

        verify(jobPort, never()).save(any());
        verify(blobStore, never()).put(anyString(), anyString(), any(), anyString());
    }

    @Test
    void unknownJobIsSkippedWithoutRetry() {
        when(jobPort.findById(jobId)).thenReturn(Optional.empty());
        worker.process(jobId);
        verify(jobPort, never()).save(any());
    }

    @Test
    void jobWithNeitherCaseNorHoldFailsFast() {
        when(jobPort.findById(jobId)).thenReturn(Optional.of(ExportJob.queued(jobId, null, null)));
        assertThatThrownBy(() -> worker.process(jobId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("neither a caseId nor a holdId");
    }

    @Test
    void holdScopedJobResolvesScopeViaSearchServiceAndPackagesIt() {
        when(jobPort.findById(jobId)).thenReturn(Optional.of(ExportJob.queued(jobId, null, "h-1")));
        when(holdScopeSource.holdMessageIds("h-1")).thenReturn(List.of("m-1"));
        when(messageSource.fetchMessages(List.of("m-1"))).thenReturn(List.of(new ArchiveMessage(
                "m-1", "{\"id\":\"m-1\"}".getBytes(StandardCharsets.UTF_8), List.of())));

        worker.process(jobId);

        ExportJob terminal = saved.getLast();
        assertThat(terminal.status()).isEqualTo(ExportJobStatus.COMPLETED);
        verify(evidenceSource, never()).evidenceMessageIds(any());
        verify(holdScopeSource).holdMessageIds("h-1");
    }

    @Test
    void evidenceFailurePropagatesForRetry() {
        when(jobPort.findById(jobId)).thenReturn(Optional.of(ExportJob.queued(jobId, "case-42", null)));
        when(evidenceSource.evidenceMessageIds("case-42")).thenThrow(new RuntimeException("case-service down"));

        assertThatThrownBy(() -> worker.process(jobId)).hasMessageContaining("case-service down");
        verify(blobStore, never()).put(anyString(), anyString(), any(), anyString());
    }

    @Test
    void emptyEvidenceStillCompletesWithManifestOnlyPackage() {
        when(jobPort.findById(jobId)).thenReturn(Optional.of(ExportJob.queued(jobId, "case-42", null)));
        when(evidenceSource.evidenceMessageIds("case-42")).thenReturn(List.of());

        worker.process(jobId);

        assertThat(saved.getLast().status()).isEqualTo(ExportJobStatus.COMPLETED);
        verify(messageSource, never()).fetchMessages(anyList());
    }

    @Test
    void markFailedRecordsErrorAndAudits() {
        when(jobPort.findById(jobId)).thenReturn(Optional.of(ExportJob.queued(jobId, "case-42", null)));

        worker.markFailed(jobId, "archive unreachable");

        ExportJob failed = saved.getLast();
        assertThat(failed.status()).isEqualTo(ExportJobStatus.FAILED);
        assertThat(failed.error()).isEqualTo("archive unreachable");
        verify(progressPublisher).publish(failed);

        ArgumentCaptor<AuditEventPayload> audit = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(auditPublisher).publish(audit.capture(), eq("case-42"));
        assertThat(audit.getValue().action()).isEqualTo("EXPORT_FAILED");
    }

    @Test
    void markFailedNeverDowngradesCompleted() {
        when(jobPort.findById(jobId)).thenReturn(Optional.of(
                ExportJob.queued(jobId, "case-42", null).completed("exports/x/package.zip", "sha")));
        worker.markFailed(jobId, "late duplicate failure");
        verify(jobPort, never()).save(any());
    }

    @Test
    void regeneratePackageRebuildsFromArchiveWithoutChangingJobStatus() {
        // A COMPLETED job whose S3 object was wiped by a LocalStack restart.
        ExportJob completed = ExportJob.queued(jobId, "case-42", null)
                .completed("exports/" + jobId + "/package.zip", "old-sha");
        when(jobPort.findById(jobId)).thenReturn(Optional.of(completed));
        when(evidenceSource.evidenceMessageIds("case-42")).thenReturn(List.of("m-1"));
        when(messageSource.fetchMessages(List.of("m-1"))).thenReturn(List.of(new ArchiveMessage(
                "m-1", "{\"id\":\"m-1\"}".getBytes(StandardCharsets.UTF_8), List.of())));

        worker.regeneratePackage(jobId);

        String expectedKey = "exports/" + jobId + "/package.zip";
        verify(blobStore).put(eq("exports-bucket"), eq(expectedKey), any(byte[].class), eq("application/zip"));
        // The job is saved with the new SHA-256 so verify passes, but stays COMPLETED.
        ArgumentCaptor<ExportJob> jobCaptor = ArgumentCaptor.forClass(ExportJob.class);
        verify(jobPort).save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().status()).isEqualTo(ExportJobStatus.COMPLETED);
        assertThat(jobCaptor.getValue().packageS3Key()).isEqualTo(expectedKey);
        assertThat(jobCaptor.getValue().packageSha256()).isNotEqualTo("old-sha");
        // No progress publish (no SSE event) and no audit — self-heal is silent.
        verify(progressPublisher, never()).publish(any());
        verify(auditPublisher, never()).publish(any(), any());
    }

    @Test
    void regeneratePackageForUnknownJobIsNoOp() {
        when(jobPort.findById(jobId)).thenReturn(Optional.empty());
        worker.regeneratePackage(jobId);
        verify(blobStore, never()).put(anyString(), anyString(), any(), anyString());
    }
}
