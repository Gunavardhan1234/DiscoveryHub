package com.smarsh.discoveryhub.export.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /exports} (api-contracts §export).
 * Exactly one of {@code caseId} or {@code holdId} should be set; the worker
 * resolves the scope from whichever is present.
 */
public record ExportRequest(
        String caseId,
        String holdId) {

    public ExportRequest {
        if ((caseId == null || caseId.isBlank()) && (holdId == null || holdId.isBlank())) {
            throw new IllegalArgumentException("Either caseId or holdId must be provided");
        }
    }
}
