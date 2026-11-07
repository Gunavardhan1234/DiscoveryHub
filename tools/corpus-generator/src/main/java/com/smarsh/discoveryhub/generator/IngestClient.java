package com.smarsh.discoveryhub.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarsh.discoveryhub.generator.model.IngestBatchRequest;
import com.smarsh.discoveryhub.generator.model.IngestRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP client that posts generated messages to ingest-service in batches.
 * Tracks accepted/duplicate/failed counts for the run summary.
 */
final class IngestClient {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String ingestUrl;
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger duplicates = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    IngestClient(String ingestUrl) {
        this.ingestUrl = ingestUrl.endsWith("/") ? ingestUrl.substring(0, ingestUrl.length() - 1) : ingestUrl;
    }

    void postBatch(List<IngestRequest> batch) {
        try {
            byte[] body = MAPPER.writeValueAsBytes(new IngestBatchRequest(batch));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ingestUrl + "/api/messages/ingest:batch"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 202 || response.statusCode() == 200) {
                accepted.addAndGet(batch.size());
            } else {
                failed.addAndGet(batch.size());
                System.err.printf("[generator] batch rejected: %d %s%n", response.statusCode(),
                        response.body().lines().findFirst().orElse(""));
            }
        } catch (Exception e) {
            failed.addAndGet(batch.size());
            System.err.printf("[generator] batch failed: %s%n", e.getMessage());
        }
    }

    int accepted() { return accepted.get(); }
    int duplicates() { return duplicates.get(); }
    int failed() { return failed.get(); }
}
