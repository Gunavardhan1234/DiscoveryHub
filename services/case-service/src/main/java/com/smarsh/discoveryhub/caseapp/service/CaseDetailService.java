package com.smarsh.discoveryhub.caseapp.service;

import com.smarsh.discoveryhub.caseapp.api.dto.CaseDetailResponse;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseResponse;
import com.smarsh.discoveryhub.caseapp.api.dto.CustodianResponse;
import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceResponse;
import com.smarsh.discoveryhub.caseapp.api.dto.SavedSearchResponse;
import com.smarsh.discoveryhub.caseapp.domain.port.HoldSummaryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Facade (Facade pattern) that aggregates everything the Case Detail screen
 * needs in one call: the case itself, custodians, evidence, saved searches,
 * and summary counts. This avoids the frontend making 4+ round-trips and
 * gives the Case Detail screen a single DTO to bind.
 *
 * <p>{@code counts.heldCount} is resolved from hold-service via {@link
 * HoldSummaryPort} (FR-4.4). The export summary is still stubbed (zero)
 * until export-service exposes a per-case count endpoint — when it does,
 * inject an {@code ExportSummaryPort} here too; the facade shape won't
 * change for consumers.
 */
// Facade Pattern: CaseDetailService presents one simple getDetail() call over four independent
// subsystems (CaseService, CustodianService, EvidenceService, SavedSearchService, plus the
// cross-service HoldSummaryPort) — callers (CaseDetailController -> the Angular Case Detail
// screen) don't need to know those subsystems exist or call them in the right order.
@Service
@Transactional(readOnly = true)
public class CaseDetailService {

    // Composition ("has-a"), not inheritance: CaseDetailService is built BY HOLDING references to
    // the other services rather than extending any of them — it reuses their behavior without
    // inheriting (and being coupled to) their implementation, and each held service stays
    // independently replaceable/testable.
    private final CaseService caseService;
    private final CustodianService custodianService;
    private final EvidenceService evidenceService;
    private final SavedSearchService savedSearchService;
    private final HoldSummaryPort holdSummary;

    public CaseDetailService(CaseService caseService, CustodianService custodianService,
                             EvidenceService evidenceService, SavedSearchService savedSearchService,
                             HoldSummaryPort holdSummary) {
        this.caseService = caseService;
        this.custodianService = custodianService;
        this.evidenceService = evidenceService;
        this.savedSearchService = savedSearchService;
        this.holdSummary = holdSummary;
    }

    // Single source of truth: this is the ONE place that assembles the full case view — the
    // frontend makes one HTTP round-trip instead of four, and every counts.* figure is computed
    // fresh from the same underlying services on every call rather than cached and risking drift.
    public CaseDetailResponse getDetail(java.util.UUID caseId) {
        CaseResponse caseResponse = caseService.get(caseId);
        List<CustodianResponse> custodians = custodianService.listCustodians(caseId);
        List<EvidenceResponse> evidence = evidenceService.listEvidence(caseId);
        List<SavedSearchResponse> savedSearches = savedSearchService.list(caseId);
        long heldCount = holdSummary.heldCount(caseId);

        CaseDetailResponse.CaseCounts counts = new CaseDetailResponse.CaseCounts(
                custodians.size(),
                evidence.size(),
                savedSearches.size(),
                heldCount);

        return new CaseDetailResponse(
                caseResponse.id(),
                caseResponse.name(),
                caseResponse.description(),
                caseResponse.matterType(),
                caseResponse.owner(),
                caseResponse.status(),
                caseResponse.createdAt(),
                caseResponse.updatedAt(),
                caseResponse.version(),
                custodians,
                evidence,
                savedSearches,
                counts);
    }
}
