package com.smarsh.discoveryhub.caseapp.infrastructure.http;

import com.smarsh.discoveryhub.caseapp.domain.port.HoldSummaryPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

/**
 * {@link HoldSummaryPort} adapter over hold-service {@code GET
 * /api/holds?caseId=} (FR-4.4). Sums {@code heldCount} across the case's
 * non-{@code RELEASED} holds — {@code RELEASED} holds no longer protect
 * anything, so they're excluded from the "currently held" total.
 *
 * <p>This is a deliberate approximation, not an exact distinct-message
 * count: two overlapping holds on the same case that both cover the same
 * message would each contribute their own {@code heldCount}, double
 * counting it. An exact figure would need archive-service to expose a
 * distinct-count-by-caseId query, which no message document currently
 * supports (messages reference holds by id, not by case). Good enough for
 * the FR-4.4 demo signal ("is anything held on this case, roughly how much");
 * not a substitute for the authoritative per-hold counts on the Holds screen.
 *
 * <p>No circuit breaker — same fail-open reasoning as {@code
 * CaseStatusHttpAdapter} elsewhere: a case-service ↔ hold-service hiccup
 * must not break the Case Detail screen, so any failure returns {@code 0}.
 */
// Adapter Pattern: translates between this service's HoldSummaryPort abstraction and
// hold-service's actual REST API shape (JSON array of hold objects) — if hold-service's API
// changed, only this class would need to change, never CaseDetailService or its callers.
// Synchronous HTTP, not Kafka: this is a request/response READ ("how many are held right now?")
// needed immediately to render a page, unlike case.closed.v1 (a one-way notification of a fact
// that already happened) — a synchronous call is the right tool when the caller needs an answer
// before it can respond to its own caller.
@Component
public class RestHoldSummaryAdapter implements HoldSummaryPort {

    private static final Logger log = LoggerFactory.getLogger(RestHoldSummaryAdapter.class);

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public RestHoldSummaryAdapter(
            @Value("${discoveryhub.case.hold-base-url:http://localhost:8087}") String holdBaseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        this.client = RestClient.builder()
                .baseUrl(holdBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public long heldCount(UUID caseId) {
        try {
            String json = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/holds").queryParam("caseId", caseId).build())
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(json == null ? "[]" : json);
            long total = 0;
            for (JsonNode hold : root) {
                if ("RELEASED".equals(hold.path("status").asText(""))) {
                    continue;
                }
                total += hold.path("heldCount").asLong(0);
            }
            return total;
        } catch (Exception e) {
            // Fail-open resiliency (NFR-2): if hold-service is down, the Case Detail screen must
            // still render — a display count defaulting to 0 is a far better failure mode than a
            // 500 error taking down an otherwise-unrelated page. This is a deliberately broad
            // catch because ANY failure mode here (timeout, connection refused, malformed JSON)
            // should degrade the same way.
            log.warn("Could not resolve held count for case {}; defaulting to 0 (fail-open)", caseId, e);
            return 0;
        }
    }
}
