package com.smarsh.discoveryhub.caseapp.api.dto;

import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import com.smarsh.discoveryhub.caseapp.domain.model.MatterType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Facade DTO (Facade pattern) aggregating everything the Case Detail screen
 * needs in one call: the case itself, its custodians, evidence items, saved
 * searches, and summary counts. {@code counts.heldCount} is resolved from
 * hold-service (FR-4.4); the export summary is still stubbed until
 * export-service exposes a per-case count endpoint.
 */
public record CaseDetailResponse(
        UUID id,
        String name,
        String description,
        MatterType matterType,
        String owner,
        CaseStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version,
        List<CustodianResponse> custodians,
        List<EvidenceResponse> evidence,
        List<SavedSearchResponse> savedSearches,
        CaseCounts counts) {

    public record CaseCounts(int custodianCount, int evidenceCount, int savedSearchCount, long heldCount) {
    }
}
