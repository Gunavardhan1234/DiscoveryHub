package com.smarsh.discoveryhub.archive.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ArchiveS3StorageAdapter} against an unreachable endpoint. Unlike the
 * fail-open case-status adapters, S3 I/O here has no fallback — a failure
 * must propagate so the caller (disposition/attachment cleanup) knows the
 * write/read/delete did not happen. Presigning is a local computation (no
 * network call), so it's exercised for real.
 */
class ArchiveS3StorageAdapterTest {

    private final ArchiveS3StorageAdapter adapter =
            new ArchiveS3StorageAdapter("http://localhost:1", "us-east-1", "test", "test");

    @Test
    void putPropagatesTransportFailuresWhenS3IsUnreachable() {
        assertThatThrownBy(() -> adapter.put("bucket", "key", "content".getBytes(), "text/plain"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void getPropagatesTransportFailuresWhenS3IsUnreachable() {
        assertThatThrownBy(() -> adapter.get("bucket", "key"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void deletePropagatesTransportFailuresWhenS3IsUnreachable() {
        assertThatThrownBy(() -> adapter.delete("bucket", "key"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void presignedUrlComputesARealSignedUrlWithoutNetworkIo() {
        String url = adapter.presignedUrl("bucket", "attachments/a1.pdf", 900);

        assertThat(url).contains("bucket").contains("attachments/a1.pdf");
    }

    @Test
    void closeReleasesTheS3ClientAndPresignerWithoutThrowing() throws Exception {
        Method close = ArchiveS3StorageAdapter.class.getDeclaredMethod("close");
        close.setAccessible(true);

        org.assertj.core.api.Assertions.assertThatCode(() -> close.invoke(adapter))
                .doesNotThrowAnyException();
    }
}
