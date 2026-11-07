package com.smarsh.discoveryhub.hold.api;

import com.smarsh.discoveryhub.hold.api.dto.RetentionPolicyResponse;
import com.smarsh.discoveryhub.hold.service.RetentionPolicyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Retention policy endpoints (FR-5.1). Mounted under {@code /api/holds/retention-policy}
 * so they are reachable through the api-gateway's existing {@code /api/holds/**}
 * route to hold-service — no gateway change required and case-service is left
 * untouched. {@code GET} lists the per-type policies; {@code PUT} upserts a
 * policy so a demo can be retuned live (e.g. set EMAIL retention to 1 minute).
 */
@RestController
@RequestMapping("/api/holds/retention-policy")
public class RetentionPolicyController {

    private final RetentionPolicyService service;

    public RetentionPolicyController(RetentionPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public RetentionPolicyResponse list() {
        return service.list();
    }

    @PutMapping("/{commType}")
    public ResponseEntity<RetentionPolicyResponse.Policy> upsert(
            @PathVariable String commType, @RequestBody Map<String, String> body) {
        String duration = body.get("duration");
        String updatedBy = body.get("updatedBy");
        return ResponseEntity.ok(service.upsert(commType, duration, updatedBy));
    }
}
