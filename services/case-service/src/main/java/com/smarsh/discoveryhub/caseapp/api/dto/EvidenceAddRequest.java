package com.smarsh.discoveryhub.caseapp.api.dto;

import jakarta.validation.constraints.NotBlank;

public record EvidenceAddRequest(
        @NotBlank String messageId,
        String source) {
}
