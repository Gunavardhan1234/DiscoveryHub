package com.smarsh.discoveryhub.caseapp.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Hardcoded stub matching docs/api-contracts.md §case GET /api/retention-policy. */
@RestController
@RequestMapping("/api/retention-policy")
public class RetentionPolicyController {
    @GetMapping
    public Map<String, Object> retentionPolicy() {
        // TODO FR-5.1: load retention policies from Postgres (case-service owns them).
        return Map.of("policies", List.of(
                Map.of("commType", "EMAIL", "duration", "PT2M"),
                Map.of("commType", "CHAT", "duration", "PT1M")));
    }
}
