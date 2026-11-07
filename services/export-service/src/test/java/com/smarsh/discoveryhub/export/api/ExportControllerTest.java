package com.smarsh.discoveryhub.export.api;

import com.smarsh.discoveryhub.common.BlobStore;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.export.application.ExportWorkerUseCase;
import com.smarsh.discoveryhub.export.application.RequestExportUseCase;
import com.smarsh.discoveryhub.export.application.RetryExportUseCase;
import com.smarsh.discoveryhub.export.application.VerifyExportUseCase;
import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.model.ExportJobStatus;
import com.smarsh.discoveryhub.export.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.ExportJobPort;
import com.smarsh.discoveryhub.export.infrastructure.sse.ExportProgressBroadcaster;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExportController.class)
class ExportControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private RequestExportUseCase requestExportUseCase;
    @MockitoBean private RetryExportUseCase retryExportUseCase;
    @MockitoBean private VerifyExportUseCase verifyExportUseCase;
    @MockitoBean private ExportWorkerUseCase exportWorker;
    @MockitoBean private ExportJobPort jobPort;
    @MockitoBean private BlobStore blobStore;
    @MockitoBean private ExportProgressBroadcaster broadcaster;
    @MockitoBean private AuditEventPublisher auditPublisher;

    private ExportJob job(UUID id, ExportJobStatus status, String s3Key) {
        return new ExportJob(id, "case-1", null, status, status == ExportJobStatus.COMPLETED ? 100 : 0,
                s3Key, s3Key != null ? "sha-abc" : null, null, Instant.now(), Instant.now());
    }

    @Test
    void createReturns202() throws Exception {
        UUID id = UUID.randomUUID();
        when(requestExportUseCase.execute("case-1", null)).thenReturn(job(id, ExportJobStatus.QUEUED, null));

        mvc.perform(post("/api/exports").contentType(MediaType.APPLICATION_JSON).content("""
                        {"caseId":"case-1"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void createOnClosedCaseReturns409() throws Exception {
        when(requestExportUseCase.execute(any(), any()))
                .thenThrow(new ConflictException("CASE_CLOSED", "Case is closed"));

        mvc.perform(post("/api/exports").contentType(MediaType.APPLICATION_JSON).content("""
                        {"caseId":"case-1"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_CLOSED"));
    }

    @Test
    void listByCaseIdFilters() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobPort.findByCaseId("case-1")).thenReturn(List.of(job(id, ExportJobStatus.QUEUED, null)));

        mvc.perform(get("/api/exports").param("caseId", "case-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value(id.toString()));
    }

    @Test
    void listWithoutCaseIdReturnsAll() throws Exception {
        when(jobPort.findAll()).thenReturn(List.of());

        mvc.perform(get("/api/exports")).andExpect(status().isOk());
        verify(jobPort).findAll();
    }

    @Test
    void getReturnsTheJob() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobPort.findById(id)).thenReturn(Optional.of(job(id, ExportJobStatus.RUNNING, null)));

        mvc.perform(get("/api/exports/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void getUnknownJobReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobPort.findById(id)).thenReturn(Optional.empty());

        mvc.perform(get("/api/exports/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void downloadUrlOnCompletedJobReturnsPresignedUrl() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobPort.findById(id)).thenReturn(Optional.of(job(id, ExportJobStatus.COMPLETED, "exports/job/package.zip")));
        when(blobStore.exists("discoveryhub-exports", "exports/job/package.zip")).thenReturn(true);
        when(blobStore.presignedUrl(eq("discoveryhub-exports"), eq("exports/job/package.zip"), eq(900L)))
                .thenReturn("https://signed.example/package.zip");

        mvc.perform(get("/api/exports/" + id + "/download-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://signed.example/package.zip"));
        verify(auditPublisher).publish(any(), any());
    }

    @Test
    void downloadUrlOnNonCompletedJobReturns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobPort.findById(id)).thenReturn(Optional.of(job(id, ExportJobStatus.RUNNING, null)));

        mvc.perform(get("/api/exports/" + id + "/download-url"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPORT_NOT_READY"));
    }

    @Test
    void downloadUrlSelfHealsWhenPackageMissingFromS3() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobPort.findById(id)).thenReturn(Optional.of(job(id, ExportJobStatus.COMPLETED, "exports/job/package.zip")));
        when(blobStore.exists("discoveryhub-exports", "exports/job/package.zip")).thenReturn(false);
        when(blobStore.presignedUrl(any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("https://signed.example/package.zip");

        mvc.perform(get("/api/exports/" + id + "/download-url"))
                .andExpect(status().isOk());

        verify(exportWorker).regeneratePackage(id);
    }

    @Test
    void verifyReturnsThePassResult() throws Exception {
        UUID id = UUID.randomUUID();
        when(verifyExportUseCase.verify(id)).thenReturn(new VerifyExportUseCase.Result(id.toString(), true, "OK"));

        mvc.perform(post("/api/exports/" + id + "/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void verifySelfHealsOnMissingPackageThenReVerifies() throws Exception {
        UUID id = UUID.randomUUID();
        when(verifyExportUseCase.verify(id))
                .thenReturn(new VerifyExportUseCase.Result(id.toString(), false, "package missing from the exports bucket"))
                .thenReturn(new VerifyExportUseCase.Result(id.toString(), true, "OK after regeneration"));

        mvc.perform(post("/api/exports/" + id + "/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        verify(exportWorker).regeneratePackage(id);
    }

    @Test
    void verifyDoesNotRegenerateForAGenuineTamperFailure() throws Exception {
        UUID id = UUID.randomUUID();
        when(verifyExportUseCase.verify(id))
                .thenReturn(new VerifyExportUseCase.Result(id.toString(), false, "manifest item hash mismatch"));

        mvc.perform(post("/api/exports/" + id + "/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));

        verify(exportWorker, never()).regeneratePackage(any());
    }

    @Test
    void retryReturns202() throws Exception {
        UUID id = UUID.randomUUID();
        when(retryExportUseCase.execute(id)).thenReturn(job(id, ExportJobStatus.QUEUED, null));

        mvc.perform(post("/api/exports/" + id + "/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void streamReturnsAnSseEmitter() throws Exception {
        UUID id = UUID.randomUUID();
        ExportJob current = job(id, ExportJobStatus.RUNNING, null);
        when(jobPort.findById(id)).thenReturn(Optional.of(current));
        when(broadcaster.subscribe(current)).thenReturn(new SseEmitter());

        mvc.perform(get("/api/exports/" + id + "/stream"))
                .andExpect(status().isOk());
    }
}
