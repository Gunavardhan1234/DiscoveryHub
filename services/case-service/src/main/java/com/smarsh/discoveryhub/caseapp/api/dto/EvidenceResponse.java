package com.smarsh.discoveryhub.caseapp.api.dto;

import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.EvidenceItemEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EvidenceResponse(
        UUID id,
        UUID caseId,
        String messageId,
        OffsetDateTime addedAt,
        String addedBy,
        String source) {

    public static EvidenceResponse from(EvidenceItemEntity e) {
        return new EvidenceResponse(e.id, e.caseId, e.messageId, e.addedAt, e.addedBy, e.source);
    }
}
