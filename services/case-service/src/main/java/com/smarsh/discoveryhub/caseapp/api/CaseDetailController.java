package com.smarsh.discoveryhub.caseapp.api;

import com.smarsh.discoveryhub.caseapp.api.dto.CaseDetailResponse;
import com.smarsh.discoveryhub.caseapp.service.CaseDetailService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Facade endpoint that returns the full Case Detail view in one call
 * (Facade pattern). The Angular Case Detail screen binds this single DTO
 * instead of making separate calls for case, custodians, evidence, and
 * saved searches.
 */
// Thin controller over a Facade service: this class adds no logic of its own beyond routing —
// every aggregation decision lives in CaseDetailService, keeping the HTTP layer a pure adapter.
@RestController
@RequestMapping("/api/cases/{caseId}/detail")
public class CaseDetailController {

    private final CaseDetailService caseDetailService;

    public CaseDetailController(CaseDetailService caseDetailService) {
        this.caseDetailService = caseDetailService;
    }

    @GetMapping
    public CaseDetailResponse getDetail(@PathVariable UUID caseId) {
        return caseDetailService.getDetail(caseId);
    }
}
