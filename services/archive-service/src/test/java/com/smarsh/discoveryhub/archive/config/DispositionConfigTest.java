package com.smarsh.discoveryhub.archive.config;

import com.smarsh.discoveryhub.archive.application.DispositionExecutor;
import com.smarsh.discoveryhub.archive.application.RetentionGuard;
import com.smarsh.discoveryhub.archive.domain.port.AttachmentCleaner;
import com.smarsh.discoveryhub.archive.domain.port.DispositionStorePort;
import com.smarsh.discoveryhub.common.BlobStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DispositionConfigTest {

    private final DispositionConfig config = new DispositionConfig();

    @Test
    void dispositionExecutorBeanWiresItsDependencies() {
        DispositionStorePort store = mock(DispositionStorePort.class);
        RetentionGuard guard = mock(RetentionGuard.class);
        AttachmentCleaner cleaner = mock(AttachmentCleaner.class);

        DispositionExecutor executor = config.dispositionExecutor(store, guard, cleaner, 500);

        assertThat(executor).isNotNull();
    }

    @Test
    void s3AttachmentCleanerDeletesEveryKeyFromTheBucket() {
        BlobStore blobStore = mock(BlobStore.class);
        AttachmentCleaner cleaner = config.s3AttachmentCleaner(blobStore, "discoveryhub-attachments");

        cleaner.deleteAll(List.of("att/1.pdf", "att/2.pdf"));

        verify(blobStore).delete("discoveryhub-attachments", "att/1.pdf");
        verify(blobStore).delete("discoveryhub-attachments", "att/2.pdf");
    }

    @Test
    void s3AttachmentCleanerSwallowsPerKeyFailuresAndContinues() {
        BlobStore blobStore = mock(BlobStore.class);
        doThrow(new RuntimeException("S3 down")).when(blobStore).delete("discoveryhub-attachments", "att/1.pdf");
        AttachmentCleaner cleaner = config.s3AttachmentCleaner(blobStore, "discoveryhub-attachments");

        assertThatCode(() -> cleaner.deleteAll(List.of("att/1.pdf", "att/2.pdf"))).doesNotThrowAnyException();

        verify(blobStore).delete("discoveryhub-attachments", "att/2.pdf");
    }

    @Test
    void loggingAttachmentCleanerNeverThrows() {
        AttachmentCleaner cleaner = config.loggingAttachmentCleaner();

        assertThatCode(() -> cleaner.deleteAll(List.of("att/1.pdf"))).doesNotThrowAnyException();
    }
}
