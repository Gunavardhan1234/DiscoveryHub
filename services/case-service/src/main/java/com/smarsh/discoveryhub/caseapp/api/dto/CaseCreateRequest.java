package com.smarsh.discoveryhub.caseapp.api.dto;

import com.smarsh.discoveryhub.caseapp.domain.model.MatterType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// Record (Java 16+): an immutable, boilerplate-free data carrier — the compiler generates the
// constructor, accessors, equals/hashCode/toString, so this class can't accidentally acquire
// mutable setters or business logic that would blur the DTO/domain-model boundary.
// Bean Validation (@NotBlank/@NotNull): fail-fast input validation declared on the DTO itself,
// enforced by @Valid in CaseController before CaseService ever sees a malformed request.
public record CaseCreateRequest(
        @NotBlank String name,
        String description,
        @NotNull MatterType matterType,
        @NotBlank String owner) {
}
