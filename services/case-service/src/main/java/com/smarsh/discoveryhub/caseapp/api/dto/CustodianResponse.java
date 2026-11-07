package com.smarsh.discoveryhub.caseapp.api.dto;

import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseCustodianEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CustodianResponse(
        UUID caseId,
        String custodianId,
        String custodianName,
        OffsetDateTime addedAt) {

    public static CustodianResponse from(CaseCustodianEntity e) {
        return new CustodianResponse(e.id.getCaseId(), e.id.getCustodianId(), e.custodianName, e.addedAt);
    }
}
