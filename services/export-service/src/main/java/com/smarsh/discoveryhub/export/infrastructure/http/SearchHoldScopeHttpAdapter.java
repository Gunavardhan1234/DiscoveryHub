package com.smarsh.discoveryhub.export.infrastructure.http;

import com.smarsh.discoveryhub.export.domain.port.HoldScopeSourcePort;
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
import java.util.Map;

/**
 * {@link HoldScopeSourcePort} adapter over search-service
 * {@code POST /api/search/scope-ids} filtered on {@code holds} (FR-6.1). Uses
 * the PIT + search_after streaming endpoint so a hold covering the entire
 * corpus doesn't blow past ES's default result-window limits.
 */
@Component
public class SearchHoldScopeHttpAdapter implements HoldScopeSourcePort {

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public SearchHoldScopeHttpAdapter(
            @Value("${discoveryhub.export.search-base-url:http://localhost:8083}") String searchBaseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        this.client = RestClient.builder()
                .baseUrl(searchBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    @CircuitBreaker(name = "searchClient")
    @Bulkhead(name = "searchClient")
    public List<String> holdMessageIds(String holdId) {
        String json = client.post()
                .uri("/api/search/scope-ids")
                .body(Map.of("filters", Map.of("holdId", holdId)))
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = mapper.readTree(json == null ? "{}" : json);
            List<String> ids = new ArrayList<>();
            for (JsonNode id : root.path("ids")) {
                ids.add(id.asText());
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException("Unparseable scope-ids response for hold " + holdId, e);
        }
    }
}
