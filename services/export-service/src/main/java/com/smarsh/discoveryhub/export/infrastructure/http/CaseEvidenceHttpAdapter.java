package com.smarsh.discoveryhub.export.infrastructure.http;

import com.smarsh.discoveryhub.export.domain.port.EvidenceSourcePort;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link EvidenceSourcePort} adapter over case-service
 * {@code GET /api/cases/{caseId}/evidence}. Unlike the dashboard counts
 * adapter, failures here are NOT swallowed — a missing evidence list must fail
 * the job so the Kafka retry/DLT path handles it (Day-3 resiliency).
 *
 * <p><b>OOP — Adapter pattern + SOLID — DIP:</b> translates between the
 * domain's {@code List<String>} contract and case-service's JSON wire shape.
 * The domain never sees {@code RestClient}, Jackson or Resilience4j — those
 * are implementation details of this class alone, so they can change (or be
 * mocked in tests) without any domain code noticing.
 */
@Component
public class CaseEvidenceHttpAdapter implements EvidenceSourcePort {

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public CaseEvidenceHttpAdapter(
            @Value("${discoveryhub.export.case-base-url:http://localhost:8084}") String caseBaseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        this.client = RestClient.builder()
                .baseUrl(caseBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    @CircuitBreaker(name = "caseClient")
    @Bulkhead(name = "caseClient")
    public List<String> evidenceMessageIds(String caseId) {
        String json = client.get()
                .uri("/api/cases/{caseId}/evidence", caseId)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = mapper.readTree(json == null ? "[]" : json);
            List<String> ids = new ArrayList<>();
            for (JsonNode item : root) {
                JsonNode messageId = item.get("messageId");
                if (messageId != null && !messageId.isNull()) ids.add(messageId.asText());
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException("Unparseable evidence list for case " + caseId, e);
        }
    }
}
