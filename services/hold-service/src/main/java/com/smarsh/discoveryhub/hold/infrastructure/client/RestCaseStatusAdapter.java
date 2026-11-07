package com.smarsh.discoveryhub.hold.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.hold.domain.port.CaseStatusPort;
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
 * {@link CaseStatusPort} adapter over case-service {@code GET /api/cases/{id}}.
 * Service-to-service, not via the gateway (same pattern as
 * {@link RestDispositionExecutorClient}). The base URL defaults per profile
 * ({@code http://localhost:8084} local, {@code http://case-service:8084}
 * docker), so no compose or gateway change is required.
 *
 * <p>Fail-open on transport/5xx errors (see {@link CaseStatusPort}); 404 maps
 * to {@link NotFoundException} so placing a hold on a non-existent case
 * returns 404 rather than a phantom PENDING row.
 */
@Component
public class RestCaseStatusAdapter implements CaseStatusPort {

    private static final Logger log = LoggerFactory.getLogger(RestCaseStatusAdapter.class);

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public RestCaseStatusAdapter(
            @Value("${discoveryhub.case.base-url:http://localhost:8084}") String caseBaseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        this.client = RestClient.builder()
                .baseUrl(caseBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
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
            log.warn("Case-service returned {} for case {} — allowing hold (fail-open)",
                    e.getStatusCode(), caseId);
            return false;
        } catch (ResourceAccessException e) {
            log.warn("Case-service unreachable for case {} — allowing hold (fail-open): {}",
                    caseId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Could not determine status for case {} — allowing hold (fail-open): {}",
                    caseId, e.getMessage());
            return false;
        }
    }
}
