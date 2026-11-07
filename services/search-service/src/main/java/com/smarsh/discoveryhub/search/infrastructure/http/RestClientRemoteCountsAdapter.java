package com.smarsh.discoveryhub.search.infrastructure.http;

import com.smarsh.discoveryhub.search.domain.port.RemoteCountsPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Set;

/**
 * {@link RemoteCountsPort} adapter calling the case, hold, and export services
 * over HTTP. Every call has a short timeout and degrades to {@code 0} on any
 * failure (4xx/5xx/timeout/parse error) — so a down service blanks only its own
 * dashboard card, never the whole dashboard (NFR-2 / Day-4 resiliency demo).
 *
 * <p>Each service returns a different list shape, so the counts are derived from
 * what each owning service actually exposes today:
 * <ul>
 *   <li><b>case-service</b> {@code GET /api/cases?size=10000} → a paged
 *       {@code PageResponse} ({@code content} + {@code totalElements});
 *       <em>active cases</em> = content elements whose {@code status != CLOSED}.</li>
 *   <li><b>hold-service</b> {@code GET /api/holds} → a bare JSON array of
 *       {@code HoldResponse}; <em>active holds</em> = elements whose status is
 *       {@code PENDING}/{@code ACTIVE}/{@code RELEASING} (non-terminal).</li>
 *   <li><b>export-service</b> {@code GET /api/exports} → whatever shape is exposed;
 *       falls back to {@code total}/{@code totalElements}/{@code count}/array length,
 *       else {@code 0} until the list endpoint ships.</li>
 * </ul>
 *
 * <p>The parse helpers are static and package-visible so the JSON-shape
 * contracts are unit-tested without an HTTP server
 * (see {@code RestClientRemoteCountsAdapterTest}).
 *
 * <p><b>OOP — Adapter pattern + SOLID — DIP:</b> translates three different
 * downstream JSON shapes into the domain's {@code long} contract; neither
 * RestClient nor the wire formats leak past this class. The
 * {@code safeCount} wrapper keeps the degradation policy local — callers
 * (the domain) never branch on remote failure.
 */
@Component
public class RestClientRemoteCountsAdapter implements RemoteCountsPort {

    private static final Logger log = LoggerFactory.getLogger(RestClientRemoteCountsAdapter.class);
    private static final Set<String> ACTIVE_HOLD_STATUSES = Set.of("PENDING", "ACTIVE", "RELEASING");

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String caseBaseUrl;
    private final String holdBaseUrl;
    private final String exportBaseUrl;

    public RestClientRemoteCountsAdapter(
            @Value("${discoveryhub.dashboard.case-base-url:http://localhost:8084}") String caseBaseUrl,
            @Value("${discoveryhub.dashboard.hold-base-url:http://localhost:8087}") String holdBaseUrl,
            @Value("${discoveryhub.dashboard.export-base-url:http://localhost:8085}") String exportBaseUrl) {
        this.caseBaseUrl = caseBaseUrl;
        this.holdBaseUrl = holdBaseUrl;
        this.exportBaseUrl = exportBaseUrl;
        this.client = RestClient.builder()
                .requestFactory(shortTimeoutFactory())
                .requestInterceptor((req, body, exec) -> {
                    req.getHeaders().add("Accept", "application/json");
                    return exec.execute(req, body);
                })
                .build();
    }

    @Override
    public long activeCases() {
        return safeCount(() -> countActiveCases(
                client.get().uri(caseBaseUrl + "/api/cases?size=10000").retrieve().body(String.class)),
                "activeCases");
    }

    @Override
    public long activeHolds() {
        return safeCount(() -> countActiveHolds(
                client.get().uri(holdBaseUrl + "/api/holds").retrieve().body(String.class)),
                "activeHolds");
    }

    @Override
    public long exportsCompleted() {
        return safeCount(() -> readTotal(
                client.get().uri(exportBaseUrl + "/api/exports").retrieve().body(String.class)),
                "exportsCompleted");
    }

    private long safeCount(CountCall call, String label) {
        try {
            return call.call();
        } catch (Exception ex) {
            log.debug("dashboard {} degraded to 0: {}", label, ex.getMessage());
            return 0;
        }
    }

    @FunctionalInterface
    private interface CountCall {
        long call() throws Exception;
    }

    /**
     * Counts non-CLOSED cases from a {@code PageResponse} body. Uses the
     * {@code content} array (the current page) so the count reflects active
     * cases, not the cumulative total; falls back to {@code totalElements} when
     * the content array is absent.
     */
    static long countActiveCases(String json) throws Exception {
        JsonNode root = new ObjectMapper().readTree(json);
        JsonNode content = root != null && root.has("content") && root.get("content").isArray()
                ? root.get("content") : null;
        if (content != null) {
            long count = 0;
            for (JsonNode c : content) {
                String status = c.has("status") ? c.get("status").asText() : "";
                if (!"CLOSED".equals(status)) {
                    count++;
                }
            }
            return count;
        }
        return totalFromObject(root);
    }

    /**
     * Counts active (non-terminal) holds from a bare JSON array of
     * {@code HoldResponse}. Active = {@code PENDING}/{@code ACTIVE}/
     * {@code RELEASING}; {@code RELEASED}/{@code FAILED} are excluded. Falls
     * back to a plain total when the body is not an array.
     */
    static long countActiveHolds(String json) throws Exception {
        JsonNode root = new ObjectMapper().readTree(json);
        if (root != null && root.isArray()) {
            long count = 0;
            for (JsonNode h : root) {
                String status = h.has("status") ? h.get("status").asText() : "";
                if (ACTIVE_HOLD_STATUSES.contains(status)) {
                    count++;
                }
            }
            return count;
        }
        return totalFromObject(root);
    }

    /** Generic total extractor for any object with a count-ish field, or an array. */
    private long readTotal(String json) throws Exception {
        return totalFromObject(new ObjectMapper().readTree(json));
    }

    private static long totalFromObject(JsonNode node) {
        if (node == null) {
            return 0;
        }
        if (node.has("total")) {
            return node.get("total").asLong(0);
        }
        if (node.has("totalElements")) {
            return node.get("totalElements").asLong(0);
        }
        if (node.has("count")) {
            return node.get("count").asLong(0);
        }
        if (node.isArray()) {
            return node.size();
        }
        return 0;
    }

    private static ClientHttpRequestFactory shortTimeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofMillis(500).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(2).toMillis());
        return f;
    }
}
