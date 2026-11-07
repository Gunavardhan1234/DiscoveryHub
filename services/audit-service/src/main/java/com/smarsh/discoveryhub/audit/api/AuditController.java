package com.smarsh.discoveryhub.audit.api;

import com.smarsh.discoveryhub.audit.domain.model.AuditEntry;
import com.smarsh.discoveryhub.audit.domain.model.AuditQuery;
import com.smarsh.discoveryhub.audit.domain.port.AuditStorePort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Read-only audit API (FR-7.1–FR-7.4). **No POST/PUT/PATCH/DELETE endpoint
 * exists** — that absence is itself a rubric item and a test (architecture
 * §2.6). Append-only is structural, not policy: events arrive only via Kafka.
 *
 * <p>{@code GET /api/audit} filters by any combination of case, actor, action,
 * targetType, and ISO-8601 time window, paged (execution-plan M5 Day 3). The
 * body stays a bare array (contract-stable); the unfiltered-by-page total rides
 * along in the {@code X-Total-Count} header so the UI can render a page count.
 *
 * <p><b>SOLID — SRP + DIP:</b> a thin read-only adapter — parameter parsing
 * and query assembly only, delegated to {@code AuditStorePort}. It holds no
 * business rule that could be violated, and the same store port backs both
 * the REST read path and the Kafka write path.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    /** Header carrying the total match count for the current filters (paging UI). */
    public static final String TOTAL_COUNT_HEADER = "X-Total-Count";

    private final AuditStorePort store;

    public AuditController(AuditStorePort store) {
        this.store = store;
    }

    @GetMapping
    public ResponseEntity<List<AuditEntry>> find(
            @RequestParam(name = "caseId", required = false) String caseId,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "targetType", required = false) String targetType,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        AuditQuery query = new AuditQuery(
                blankToNull(caseId), blankToNull(actor), blankToNull(action), blankToNull(targetType),
                parseInstant("from", from), parseInstant("to", to), page, size);
        return ResponseEntity.ok()
                .header(TOTAL_COUNT_HEADER, Long.toString(store.count(query)))
                .body(store.find(query));
    }

    @GetMapping("/verify-chain")
    public Map<String, Object> verifyChain(@RequestParam(name = "caseId", required = false) String caseId) {
        if (caseId == null || caseId.isBlank()) {
            return Map.of("valid", false, "error", "caseId is required");
        }
        List<AuditEntry> entries = store.findByCaseId(caseId);
        return Map.of("caseId", caseId, "valid", store.verifyChain(caseId), "entries", entries.size());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant parseInstant(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'" + name + "' must be an ISO-8601 instant like 2026-09-09T00:00:00Z, got: " + value);
        }
    }
}
