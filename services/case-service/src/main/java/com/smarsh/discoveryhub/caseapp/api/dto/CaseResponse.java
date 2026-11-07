package com.smarsh.discoveryhub.caseapp.api.dto;

import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import com.smarsh.discoveryhub.caseapp.domain.model.MatterType;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CaseResponse(
        UUID id,
        String name,
        String description,
        MatterType matterType,
        String owner,
        CaseStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version) {

    // Static factory method (DTO pattern / entity-DTO separation): converts the JPA CaseEntity
    // into the API's wire shape at exactly one point, so CaseEntity (a persistence concern) never
    // leaks past the service layer into the JSON response — changing the DB schema doesn't force
    // an API contract change, and vice versa.
    public static CaseResponse from(CaseEntity c) {
        return new CaseResponse(
                c.id, c.name, c.description, c.matterType, c.owner, c.status,
                c.createdAt, c.updatedAt, c.version);
    }
}
