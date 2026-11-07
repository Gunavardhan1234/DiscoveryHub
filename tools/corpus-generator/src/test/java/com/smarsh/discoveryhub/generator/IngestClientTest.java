package com.smarsh.discoveryhub.generator;

import com.smarsh.discoveryhub.generator.model.IngestRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IngestClient} against a local JDK HTTP server —
 * no ingest-service instance required.
 */
class IngestClientTest {

    private HttpServer server;
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private int status = 202;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestPaths.add(exchange.getRequestURI().toString());
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = status == 500 ? "boom".getBytes(StandardCharsets.UTF_8) : new byte[0];
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private List<IngestRequest> batch(int size) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> new IngestRequest("ext-" + i, "CHAT", null, "body " + i,
                        "thread-1", null, "c-0001",
                        List.of(new IngestRequest.Participant("A", "a@fict.example", "FROM")),
                        java.time.Instant.parse("2026-09-14T10:00:00Z"),
                        List.of()))
                .toList();
    }

    @Test
    void acceptedBatchCountsAsAccepted() {
        IngestClient client = new IngestClient(baseUrl());
        client.postBatch(batch(3));

        assertThat(client.accepted()).isEqualTo(3);
        assertThat(client.failed()).isEqualTo(0);
        assertThat(client.duplicates()).isEqualTo(0);
        assertThat(requestPaths).containsExactly("/api/messages/ingest:batch");
        assertThat(requestBodies.getFirst()).contains("\"externalMessageId\":\"ext-0\"");
    }

    @Test
    void trailingSlashInUrlIsNormalized() {
        IngestClient client = new IngestClient(baseUrl() + "/");
        client.postBatch(batch(1));

        assertThat(requestPaths).containsExactly("/api/messages/ingest:batch");
    }

    @Test
    void rejectedBatchCountsAsFailed() {
        status = 500;
        IngestClient client = new IngestClient(baseUrl());
        client.postBatch(batch(2));

        assertThat(client.accepted()).isEqualTo(0);
        assertThat(client.failed()).isEqualTo(2);
    }

    @Test
    void connectionFailureCountsAsFailed() {
        IngestClient client = new IngestClient("http://localhost:1");
        client.postBatch(batch(2));

        assertThat(client.accepted()).isEqualTo(0);
        assertThat(client.failed()).isEqualTo(2);
    }

    @Test
    void postsAreJsonBatches() {
        IngestClient client = new IngestClient(baseUrl());
        client.postBatch(batch(2));

        String body = requestBodies.getFirst();
        assertThat(body).startsWith("{\"messages\":[").endsWith("]}");
        assertThat(body).contains("\"type\":\"CHAT\"");
        // JavaTimeModule serializes Instant as epoch-seconds by default; the
        // ingest side's Jackson accepts numeric timestamps for Instant too.
        assertThat(body).contains("\"sentAt\":1789380000");
    }
}
