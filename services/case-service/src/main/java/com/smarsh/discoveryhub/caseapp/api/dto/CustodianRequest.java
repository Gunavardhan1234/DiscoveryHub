package com.smarsh.discoveryhub.caseapp.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CustodianRequest(
        @NotBlank String custodianId,
        String custodianName) {
}
