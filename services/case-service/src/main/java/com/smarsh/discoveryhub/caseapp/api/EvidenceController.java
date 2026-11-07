package com.smarsh.discoveryhub.caseapp.api;

import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceAddRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceBulkAddRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceBulkAddResponse;
import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceResponse;
import com.smarsh.discoveryhub.caseapp.service.EvidenceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/cases/{caseId}/evidence")
public class EvidenceController {

    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    // @RequestHeader(required = false): the caller's identity is optional at the HTTP layer — if
    // absent, EvidenceService substitutes a sane default ("investigator@demo") rather than
    // rejecting the request, since FR-8 explicitly has no login/auth (single persona, no auth flow).
    @PostMapping
    public ResponseEntity<EvidenceResponse> add(
            @PathVariable UUID caseId,
            @Valid @RequestBody EvidenceAddRequest request,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        EvidenceResponse created = evidenceService.addEvidence(caseId, request, actor);
        return ResponseEntity.created(
                java.net.URI.create("/api/cases/" + caseId + "/evidence/" + created.id()))
                .body(created);
    }

    @PostMapping("/bulk")
    public EvidenceBulkAddResponse addBulk(
            @PathVariable UUID caseId,
            @Valid @RequestBody EvidenceBulkAddRequest request,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return evidenceService.addEvidenceBulk(caseId, request, actor);
    }

    /**
     * Removes a single evidence item from a case (FR-7.1 {@code EVIDENCE_REMOVED}).
     * A closed case is read-only → {@code 409 CASE_CLOSED} (FR-2.5), enforced by
     * the {@code @ClosedCaseGuard} on {@link EvidenceService#removeEvidence}.
     */
    @DeleteMapping("/{evidenceId}")
    public ResponseEntity<Void> remove(
            @PathVariable UUID caseId,
            @PathVariable UUID evidenceId,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        evidenceService.removeEvidence(caseId, evidenceId, actor);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<EvidenceResponse> list(@PathVariable UUID caseId) {
        return evidenceService.listEvidence(caseId);
    }
}
