package com.smarsh.discoveryhub.caseapp.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SavedSearchRequest(
        @NotBlank String name,
        @NotBlank String spec) {
}
