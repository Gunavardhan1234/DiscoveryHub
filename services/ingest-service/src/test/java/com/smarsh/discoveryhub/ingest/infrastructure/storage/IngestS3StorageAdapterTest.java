package com.smarsh.discoveryhub.ingest.infrastructure.storage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real AWS SDK S3 client inside {@link IngestS3StorageAdapter}
 * against a minimal in-process S3-shaped HTTP server (no LocalStack/Docker
 * needed): PUT/GET/DELETE + XML {@code NoSuchKey} error responses.
 */
class IngestS3StorageAdapterTest {

    private static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();
    private static HttpServer server;
    private static String endpoint;

    private IngestS3StorageAdapter adapter;

    @BeforeAll
    static void startFakeS3() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", IngestS3StorageAdapterTest::handle);
        server.start();
        endpoint = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopFakeS3() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        switch (exchange.getRequestMethod()) {
            case "PUT" -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
                if (encoding != null && encoding.contains("aws-chunked")) {
                    body = decodeAwsChunked(body);
                }
                OBJECTS.put(path, body);
                exchange.sendResponseHeaders(200, -1);
            }
            case "GET" -> {
                byte[] content = OBJECTS.get(path);
                if (content == null) {
                    byte[] error = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                            "<Error><Code>NoSuchKey</Code>" +
                            "<Message>The specified key does not exist.</Message></Error>")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/xml");
                    exchange.sendResponseHeaders(404, error.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(error);
                    }
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(content);
                }
            }
            case "DELETE" -> {
                OBJECTS.remove(path);
                exchange.sendResponseHeaders(204, -1);
            }
            default -> exchange.sendResponseHeaders(405, -1);
        }
        exchange.close();
    }

    /**
     * The AWS SDK uploads PutObject payloads as {@code aws-chunked} streams
     * ({@code <hex-size>[;chunk-signature=...]\r\n<bytes>\r\n...0;...\r\n});
     * decode to the raw object bytes so GET round-trips what was put.
     */
    private static byte[] decodeAwsChunked(byte[] raw) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int i = 0;
        while (i < raw.length) {
            int lineEnd = indexOfCrlf(raw, i);
            if (lineEnd < 0) {
                break;
            }
            String header = new String(raw, i, lineEnd - i, StandardCharsets.US_ASCII);
            int semi = header.indexOf(';');
            int size = Integer.parseInt(semi >= 0 ? header.substring(0, semi) : header, 16);
            i = lineEnd + 2;
            if (size == 0) {
                break;
            }
            out.write(raw, i, Math.min(size, raw.length - i));
            i += size + 2; // chunk bytes + trailing CRLF
        }
        return out.toByteArray();
    }

    private static int indexOfCrlf(byte[] haystack, int from) {
        for (int i = from; i < haystack.length - 1; i++) {
            if (haystack[i] == '\r' && haystack[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    @BeforeEach
    void createAdapter() {
        adapter = new IngestS3StorageAdapter(endpoint, "us-east-1", "test", "test");
    }

    @AfterEach
    void closeAdapter() {
        adapter.close();
    }

    @Test
    void putStoresContentUnderKey() {
        byte[] content = "attachment-bytes".getBytes(StandardCharsets.UTF_8);

        String key = adapter.put("discoveryhub-attachments", "attachments/a-1/blob.bin", content,
                "application/octet-stream");

        assertThat(key).isEqualTo("attachments/a-1/blob.bin");
        assertThat(OBJECTS).containsKey("/discoveryhub-attachments/attachments/a-1/blob.bin");
    }

    @Test
    void getRoundTripsWhatWasPut() {
        byte[] content = "round-trip-payload".getBytes(StandardCharsets.UTF_8);
        String key = "attachments/a-2/report.pdf";
        adapter.put("discoveryhub-attachments", key, content, "application/pdf");

        byte[] fetched = adapter.get("discoveryhub-attachments", key);

        assertThat(fetched).isEqualTo(content);
    }

    @Test
    void deleteRemovesTheObject() {
        byte[] content = "to-be-deleted".getBytes(StandardCharsets.UTF_8);
        String key = "attachments/a-3/old.txt";
        adapter.put("discoveryhub-attachments", key, content, "text/plain");

        adapter.delete("discoveryhub-attachments", key);

        assertThatThrownBy(() -> adapter.get("discoveryhub-attachments", key))
                .isInstanceOf(NoSuchKeyException.class);
    }

    @Test
    void presignedUrlPointsAtBucketAndKey() {
        String url = adapter.presignedUrl("discoveryhub-attachments", "attachments/a-4/x.png", 900);

        // The presigner uses virtual-host style: <bucket>.<endpoint-host>/<key>.
        assertThat(url).contains("discoveryhub-attachments.localhost:" + server.getAddress().getPort() + "/");
        assertThat(url).contains("/attachments/a-4/x.png?");
        assertThat(url).contains("X-Amz-Signature");
        assertThat(url).contains("X-Amz-Expires=900");
    }
}
