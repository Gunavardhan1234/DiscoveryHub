package com.smarsh.discoveryhub.export.infrastructure.storage;

import com.smarsh.discoveryhub.export.application.ManifestBuilder;
import com.smarsh.discoveryhub.export.application.PackageVerifier;
import com.smarsh.discoveryhub.export.application.ZipExportPackager;
import com.smarsh.discoveryhub.export.domain.model.ExportEntry;
import com.smarsh.discoveryhub.testsupport.LocalStackTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Export-verifiable" cross-boundary IT (execution-plan Day 4 M5): a real
 * export package — built the same way {@link ZipExportPackager} builds one —
 * is written to a real S3-compatible store (LocalStack, mirroring
 * {@code docker-compose.yml}'s wiring of {@link S3StorageAdapter}) and read
 * back byte-for-byte, then verified with {@link PackageVerifier} and
 * downloaded through a real pre-signed URL, proving the whole chain — package,
 * upload, download link, and hash verification — works against an actual
 * object store rather than an in-memory mock.
 *
 * <p>Skipped when Docker is unavailable (see AGENTS.md docker-java note).</p>
 */
class S3StorageAdapterIT extends LocalStackTestBase {

    private static final String BUCKET = "discoveryhub-exports-test";
    private static S3StorageAdapter adapter;

    @BeforeAll
    static void buildAdapter() {
        if (localstack == null) {
            return;
        }
        S3Client rawClient = S3Client.builder()
                .endpointOverride(endpoint())
                .region(Region.of(region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey(), secretKey())))
                .forcePathStyle(true)
                .build();
        rawClient.createBucket(b -> b.bucket(BUCKET));
        adapter = new S3StorageAdapter(rawClient, endpoint().toString(), endpoint().toString(), region(), accessKey(), secretKey());
    }

    @Test
    void putThenGetRoundTripsExactBytes() {
        byte[] content = "hello from the export worker".getBytes(StandardCharsets.UTF_8);

        String key = adapter.put(BUCKET, "exports/it-1/package.zip", content, "application/zip");
        byte[] fetched = adapter.get(BUCKET, key);

        assertThat(fetched).isEqualTo(content);
    }

    @Test
    void realExportPackageSurvivesTheRoundTripAndStillVerifies() {
        // Build an actual export package exactly the way ExportWorkerUseCase does.
        byte[] zip = new ZipExportPackager(new ManifestBuilder()).buildPackage("job-it-2", "case-it-2",
                List.of(new ExportEntry("messages/m-1.json",
                        "{\"id\":\"m-1\",\"subject\":\"wire transfer\"}".getBytes(StandardCharsets.UTF_8))),
                null).bytes();

        String key = adapter.put(BUCKET, "exports/it-2/package.zip", zip, "application/zip");
        byte[] roundTripped = adapter.get(BUCKET, key);

        assertThat(roundTripped).isEqualTo(zip);
        assertThat(new PackageVerifier().verify(roundTripped).valid())
                .as("a package must still hash-verify after a real S3 round trip")
                .isTrue();
    }

    @Test
    void presignedUrlActuallyDownloadsTheObject() throws IOException, InterruptedException {
        byte[] content = "download me via the presigned url".getBytes(StandardCharsets.UTF_8);
        String key = adapter.put(BUCKET, "exports/it-3/package.zip", content, "application/zip");

        String url = adapter.presignedUrl(BUCKET, key, 60);

        byte[] downloaded = downloadViaHttp(url);
        assertThat(downloaded).isEqualTo(content);
    }

    /** Plain HTTP GET against the pre-signed URL — no AWS SDK auth involved, exactly like a browser. */
    private static byte[] downloadViaHttp(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
