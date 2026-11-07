package com.smarsh.discoveryhub.ingest.infrastructure.storage;

import com.smarsh.discoveryhub.common.BlobStore;
import com.smarsh.discoveryhub.ingest.domain.port.ClaimCheckPort;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for the claim-check threshold + idempotent s3 key + sha256 logic.
 * The {@link BlobStore} is mocked so no S3/LocalStack is required.
 */
class S3ClaimCheckAdapterTest {

    private final BlobStore blobStore = mock(BlobStore.class);

    private S3ClaimCheckAdapter adapter(long threshold) {
        return new S3ClaimCheckAdapter(blobStore, "discoveryhub-attachments", threshold);
    }

    @Test
    void belowThresholdStaysInlineAndDoesNotTouchBlobStore() {
        S3ClaimCheckAdapter adapter = adapter(1024);

        ClaimCheckPort.ClaimCheck check = adapter.externalize("a-1", "small.txt", "text/plain", 100L,
                Base64.getEncoder().encodeToString("hi".getBytes(StandardCharsets.UTF_8)));

        assertThat(check.stored()).isFalse();
        assertThat(check.s3Key()).isNull();
        assertThat(check.sha256()).isNull();
        verifyNoInteractions(blobStore);
    }

    @Test
    void noContentStaysInline() {
        S3ClaimCheckAdapter adapter = adapter(1);

        ClaimCheckPort.ClaimCheck check = adapter.externalize("a-1", "f.bin", "application/octet-stream", 999L, null);

        assertThat(check.stored()).isFalse();
        verifyNoInteractions(blobStore);
    }

    @Test
    void aboveThresholdExternalizesAndReturnsClaimCheck() {
        S3ClaimCheckAdapter adapter = adapter(256);
        byte[] bytes = "a large attachment payload".getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(bytes);

        ClaimCheckPort.ClaimCheck check = adapter.externalize("a-9", "report.pdf", "application/pdf",
                2_000_000L, b64);

        assertThat(check.stored()).isTrue();
        assertThat(check.s3Key()).startsWith("attachments/a-9/");
        assertThat(check.s3Key()).endsWith("-report.pdf");
        assertThat(check.sha256()).startsWith("sha256:");
        verify(blobStore).put(eq("discoveryhub-attachments"), eq(check.s3Key()), eq(bytes), eq("application/pdf"));
    }

    @Test
    void blankContentStaysInline() {
        S3ClaimCheckAdapter adapter = adapter(1);

        ClaimCheckPort.ClaimCheck check = adapter.externalize("a-1", "f.bin", "application/octet-stream", 999L, "   ");

        assertThat(check.stored()).isFalse();
        verifyNoInteractions(blobStore);
    }

    @Test
    void sizeAtExactlyTheThresholdIsExternalized() {
        S3ClaimCheckAdapter adapter = adapter(256);
        byte[] bytes = "x".repeat(8).getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(bytes);

        ClaimCheckPort.ClaimCheck check = adapter.externalize("a-5", "edge.txt", "text/plain", 256L, b64);

        // The guard is `size < threshold` — an at-threshold attachment goes to S3.
        assertThat(check.stored()).isTrue();
        assertThat(check.s3Key()).startsWith("attachments/a-5/");
    }

    @Test
    void invalidBase64IsRejectedWithAttachmentId() {
        S3ClaimCheckAdapter adapter = adapter(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        adapter.externalize("a-6", "f.bin", "application/octet-stream", 10_000L, "not-base64!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a-6");
        verifyNoInteractions(blobStore);
    }

    @Test
    void blankOrMissingFilenameFallsBackToBlob() {
        S3ClaimCheckAdapter adapter = adapter(1);
        byte[] bytes = "z".repeat(64).getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(bytes);

        ClaimCheckPort.ClaimCheck blank = adapter.externalize("a-7", "  ", "text/plain", 10_000L, b64);
        ClaimCheckPort.ClaimCheck missing = adapter.externalize("a-8", null, "text/plain", 10_000L, b64);

        assertThat(blank.s3Key()).endsWith("-blob");
        assertThat(missing.s3Key()).endsWith("-blob");
    }

    @Test
    void blankOrMissingContentTypeDefaultsToOctetStream() {
        S3ClaimCheckAdapter adapter = adapter(1);
        byte[] bytes = "y".repeat(64).getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(bytes);

        adapter.externalize("a-9", "f.bin", "  ", 10_000L, b64);
        adapter.externalize("a-10", "f.bin", null, 10_000L, b64);

        verify(blobStore).put(eq("discoveryhub-attachments"), org.mockito.ArgumentMatchers.contains("a-9"),
                eq(bytes), eq("application/octet-stream"));
        verify(blobStore).put(eq("discoveryhub-attachments"), org.mockito.ArgumentMatchers.contains("a-10"),
                eq(bytes), eq("application/octet-stream"));
    }

    @Test
    void exposesConfiguredThresholdForDiagnostics() {
        assertThat(adapter(4096).thresholdBytes()).isEqualTo(4096L);
    }
}
