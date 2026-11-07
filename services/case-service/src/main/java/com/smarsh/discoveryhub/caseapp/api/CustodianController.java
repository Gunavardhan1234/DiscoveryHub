package com.smarsh.discoveryhub.caseapp.api;

import com.smarsh.discoveryhub.caseapp.api.dto.CustodianRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.CustodianResponse;
import com.smarsh.discoveryhub.caseapp.service.CustodianService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// Nested resource path (/api/cases/{caseId}/custodians): custodians only ever exist in the
// context of a case (FR-2.3), so the URL structure itself expresses that ownership relationship —
// there is no top-level /api/custodians endpoint because a custodian id is meaningless without it.
@RestController
@RequestMapping("/api/cases/{caseId}/custodians")
public class CustodianController {

    private final CustodianService custodianService;

    public CustodianController(CustodianService custodianService) {
        this.custodianService = custodianService;
    }

    @PostMapping
    public ResponseEntity<CustodianResponse> add(
            @PathVariable UUID caseId,
            @Valid @RequestBody CustodianRequest request) {
        CustodianResponse created = custodianService.addCustodian(caseId, request);
        return ResponseEntity.created(
                java.net.URI.create("/api/cases/" + caseId + "/custodians/" + request.custodianId()))
                .body(created);
    }

    // 204 No Content: the standard REST response for a successful DELETE with nothing to return —
    // distinguishes "deleted successfully" from 404 (never existed) and 409 (blocked, case closed).
    @DeleteMapping("/{custodianId}")
    public ResponseEntity<Void> remove(@PathVariable UUID caseId, @PathVariable String custodianId) {
        custodianService.removeCustodian(caseId, custodianId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<CustodianResponse> list(@PathVariable UUID caseId) {
        return custodianService.listCustodians(caseId);
    }
}
