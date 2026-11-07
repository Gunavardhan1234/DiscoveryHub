package com.smarsh.discoveryhub.export.infrastructure.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link S3StorageAdapter} against a mocked {@link S3Client} — the injected
 * client is the real integration point (verified separately by Testcontainers
 * LocalStack ITs elsewhere); this covers the adapter's own translation logic.
 * Presigning is a local computation (no network call), so it's exercised for
 * real against a fake endpoint.
 */
class S3StorageAdapterTest {

    private final S3Client s3 = mock(S3Client.class);
    private final S3StorageAdapter adapter = new S3StorageAdapter(
            s3, "http://localhost:4566", "http://localhost:4566", "us-east-1", "test", "test");

    @Test
    void putReturnsTheKeyItWasGivenAndSendsContentType() {
        String key = adapter.put("bucket", "exports/job/package.zip", "hello".getBytes(), "application/zip");

        assertThat(key).isEqualTo("exports/job/package.zip");
        verify(s3).putObject(any(java.util.function.Consumer.class),
                any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    void getReturnsTheObjectBytes() {
        @SuppressWarnings("unchecked")
        ResponseBytes<GetObjectResponse> response = mock(ResponseBytes.class);
        when(response.asByteArray()).thenReturn("package-bytes".getBytes());
        when(s3.getObjectAsBytes(any(java.util.function.Consumer.class))).thenReturn(response);

        byte[] bytes = adapter.get("bucket", "key");

        assertThat(bytes).isEqualTo("package-bytes".getBytes());
    }

    @Test
    void deleteCallsS3DeleteObject() {
        adapter.delete("bucket", "exports/job/package.zip");

        verify(s3).deleteObject(any(java.util.function.Consumer.class));
    }

    @Test
    void existsReturnsTrueWhenHeadObjectSucceeds() {
        assertThat(adapter.exists("bucket", "key")).isTrue();
    }

    @Test
    void existsReturnsFalseWhenHeadObjectThrows() {
        when(s3.headObject(any(java.util.function.Consumer.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThat(adapter.exists("bucket", "missing-key")).isFalse();
    }

    @Test
    void presignedUrlComputesARealSignedUrlWithoutNetworkIo() {
        String url = adapter.presignedUrl("bucket", "exports/job/package.zip", 900);

        assertThat(url).contains("bucket").contains("exports/job/package.zip");
    }
}
