package com.smarsh.discoveryhub.caseapp.api.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record EvidenceBulkAddRequest(
        @NotEmpty List<String> messageIds,
        String source) {
}
