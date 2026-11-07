package com.smarsh.discoveryhub.export.infrastructure.http;

import com.smarsh.discoveryhub.export.domain.model.ArchiveMessage;
import com.smarsh.discoveryhub.export.domain.port.MessageSourcePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link MessageSourcePort} adapter over archive-service
 * {@code GET /api/messages?ids=} (the authoritative Mongo-backed read, FR-1.4).
 * Ids are fetched in batches to keep URLs bounded; each returned document is
 * stored verbatim in the package, so the export reflects exactly what the
 * archive returned.
 */
@Component
public class ArchiveMessageHttpAdapter implements MessageSourcePort {

    static final int BATCH_SIZE = 200;

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ArchiveMessageHttpAdapter(
            @Value("${discoveryhub.export.archive-base-url:http://localhost:8082}") String archiveBaseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        this.client = RestClient.builder()
                .baseUrl(archiveBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Circuit breaker + bulkhead around the archive-service call
     * (execution-plan Day 4 M5 resiliency scenario: archive-service goes
     * down mid-export). No fallback method — an open breaker or a full
     * bulkhead throws, and {@code ExportWorkerUseCase}'s caller already has a
     * Kafka retry + DLT path for exactly that (Day-2 hardening), so failing
     * fast here just gets the job retried/dead-lettered sooner instead of
     * piling up blocked threads against a service that is already down.
     */
    @Override
    @CircuitBreaker(name = "archiveClient")
    @Bulkhead(name = "archiveClient")
    public List<ArchiveMessage> fetchMessages(List<String> messageIds) {
        List<ArchiveMessage> messages = new ArrayList<>(messageIds.size());
        for (int from = 0; from < messageIds.size(); from += BATCH_SIZE) {
            List<String> batch = messageIds.subList(from, Math.min(from + BATCH_SIZE, messageIds.size()));
            messages.addAll(fetchBatch(batch));
        }
        return messages;
    }

    private List<ArchiveMessage> fetchBatch(List<String> batch) {
        String json = client.get()
                .uri(builder -> builder.path("/api/messages").queryParam("ids", String.join(",", batch)).build())
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = mapper.readTree(json == null ? "{}" : json);
            List<ArchiveMessage> result = new ArrayList<>();
            for (JsonNode message : root.path("messages")) {
                String id = message.path("id").asText();
                if (id.isBlank()) continue;
                result.add(new ArchiveMessage(id, prettyBytes(message), attachmentRefs(message)));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Unparseable archive bulk response for ids " + batch, e);
        }
    }

    private List<ArchiveMessage.AttachmentRef> attachmentRefs(JsonNode message) {
        List<ArchiveMessage.AttachmentRef> refs = new ArrayList<>();
        for (JsonNode attachment : message.path("attachments")) {
            String filename = attachment.path("filename").asText("attachment.bin");
            String s3Key = attachment.path("s3Key").asText(null);
            refs.add(new ArchiveMessage.AttachmentRef(filename, s3Key));
        }
        return refs;
    }

    private byte[] prettyBytes(JsonNode message) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(message)
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unserializable archive message", e);
        }
    }
}
