package com.smarsh.discoveryhub.export.infrastructure.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.export.domain.port.CaseStatusPort;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

/**
 * {@link CaseStatusPort} adapter over case-service {@code GET /api/cases/{id}},
 * sharing the {@code caseClient} circuit breaker / bulkhead with
 * {@link CaseEvidenceHttpAdapter}. Fail-open on transport/5xx errors; 404 maps
 * to {@link NotFoundException}.
 */
@Component
public class RestCaseStatusAdapter implements CaseStatusPort {

    private static final Logger log = LoggerFactory.getLogger(RestCaseStatusAdapter.class);

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public RestCaseStatusAdapter(
            @Value("${discoveryhub.export.case-base-url:http://localhost:8084}") String caseBaseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        this.client = RestClient.builder()
                .baseUrl(caseBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    @CircuitBreaker(name = "caseClient")
    @Bulkhead(name = "caseClient")
    public boolean isClosed(UUID caseId) {
        try {
            String json = client.get()
                    .uri("/api/cases/{caseId}", caseId)
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(json == null ? "{}" : json);
            return "CLOSED".equalsIgnoreCase(root.path("status").asText(""));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                throw new NotFoundException("Case", caseId.toString());
            }
            log.warn("Case-service returned {} for case {} — allowing export (fail-open)",
                    e.getStatusCode(), caseId);
            return false;
        } catch (ResourceAccessException e) {
            log.warn("Case-service unreachable for case {} — allowing export (fail-open): {}",
                    caseId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Could not determine status for case {} — allowing export (fail-open): {}",
                    caseId, e.getMessage());
            return false;
        }
    }
}
