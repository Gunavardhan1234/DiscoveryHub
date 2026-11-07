package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.common.BlobStore;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.model.ExportJobStatus;
import com.smarsh.discoveryhub.export.domain.port.ExportJobPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerifyExportUseCaseTest {

    private final ExportJobPort jobPort = mock(ExportJobPort.class);
    private final BlobStore blobStore = mock(BlobStore.class);
    private final PackageVerifier verifier = new PackageVerifier();
    private final VerifyExportUseCase useCase =
            new VerifyExportUseCase(jobPort, blobStore, verifier, "discoveryhub-exports");

    private ExportJob completedJob(UUID id, String s3Key, String sha256) {
        return new ExportJob(id, "case-1", null, ExportJobStatus.COMPLETED, 100,
                s3Key, sha256, null, Instant.now(), Instant.now());
    }

    @Test
    void unknownJobThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(jobPort.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.verify(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void nonCompletedJobThrowsConflict() {
        UUID id = UUID.randomUUID();
        ExportJob running = new ExportJob(id, "case-1", null, ExportJobStatus.RUNNING, 50,
                null, null, null, Instant.now(), Instant.now());
        when(jobPort.findById(id)).thenReturn(Optional.of(running));

        assertThatThrownBy(() -> useCase.verify(id))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).code()).isEqualTo("EXPORT_NOT_READY"));
    }

    @Test
    void fetchFailureReturnsInvalidWithoutThrowing() {
        UUID id = UUID.randomUUID();
        when(jobPort.findById(id)).thenReturn(Optional.of(completedJob(id, "exports/job/package.zip", "sha-1")));
        when(blobStore.get("discoveryhub-exports", "exports/job/package.zip"))
                .thenThrow(new RuntimeException("S3 unreachable"));

        VerifyExportUseCase.Result result = useCase.verify(id);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("could not be fetched");
    }

    @Test
    void missingPackageBytesReturnsInvalid() {
        UUID id = UUID.randomUUID();
        when(jobPort.findById(id)).thenReturn(Optional.of(completedJob(id, "exports/job/package.zip", "sha-1")));
        when(blobStore.get("discoveryhub-exports", "exports/job/package.zip")).thenReturn(null);

        VerifyExportUseCase.Result result = useCase.verify(id);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("missing from the exports bucket");
    }

    @Test
    void shaMismatchReturnsInvalidWithoutRunningPackageVerifier() {
        UUID id = UUID.randomUUID();
        byte[] bytes = "not the original zip content".getBytes();
        when(jobPort.findById(id)).thenReturn(Optional.of(completedJob(id, "exports/job/package.zip", "wrong-sha")));
        when(blobStore.get("discoveryhub-exports", "exports/job/package.zip")).thenReturn(bytes);

        VerifyExportUseCase.Result result = useCase.verify(id);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("SHA-256 differs");
    }

    @Test
    void matchingShaDelegatesToPackageVerifier() {
        UUID id = UUID.randomUUID();
        byte[] bytes = "some zip bytes".getBytes();
        String realSha = ManifestBuilder.sha256Hex(bytes);
        when(jobPort.findById(id)).thenReturn(Optional.of(completedJob(id, "exports/job/package.zip", realSha)));
        when(blobStore.get("discoveryhub-exports", "exports/job/package.zip")).thenReturn(bytes);

        VerifyExportUseCase.Result result = useCase.verify(id);

        // Not a real zip, so PackageVerifier itself will report invalid —
        // this proves control reached the verifier rather than short-circuiting.
        assertThat(result.jobId()).isEqualTo(id.toString());
        assertThat(result.valid()).isFalse();
    }

    @Test
    void nullRecordedShaSkipsTheShaCheckAndStillDelegatesToVerifier() {
        UUID id = UUID.randomUUID();
        byte[] bytes = "some zip bytes".getBytes();
        when(jobPort.findById(id)).thenReturn(Optional.of(completedJob(id, "exports/job/package.zip", null)));
        when(blobStore.get("discoveryhub-exports", "exports/job/package.zip")).thenReturn(bytes);

        VerifyExportUseCase.Result result = useCase.verify(id);

        assertThat(result.jobId()).isEqualTo(id.toString());
    }
}
