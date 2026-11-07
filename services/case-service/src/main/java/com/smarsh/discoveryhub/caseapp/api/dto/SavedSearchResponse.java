package com.smarsh.discoveryhub.caseapp.api.dto;

import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.SavedSearchEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SavedSearchResponse(
        UUID id,
        UUID caseId,
        String name,
        String spec,
        OffsetDateTime createdAt) {

    public static SavedSearchResponse from(SavedSearchEntity e) {
        return new SavedSearchResponse(e.id, e.caseId, e.name, e.spec, e.createdAt);
    }
}
