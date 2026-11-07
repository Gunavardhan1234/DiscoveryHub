package com.smarsh.discoveryhub.caseapp.api.dto;

import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import jakarta.validation.constraints.NotNull;

public record TransitionRequest(@NotNull CaseStatus to) {
}
