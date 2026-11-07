package com.smarsh.discoveryhub.caseapp.api.dto;

import com.smarsh.discoveryhub.caseapp.domain.model.MatterType;

/**
 * Partial update for a case (FR-2.3 / FR-7.1 {@code CASE_UPDATED}). Every
 * field is optional — {@code null} means "leave unchanged" — so the same
 * endpoint handles a rename, a description tweak, or a matter-type
 * correction. {@code status} is intentionally absent: lifecycle transitions go
 * through {@code POST /api/cases/{id}/transition} (FR-2.2 / FR-2.5), and the
 * update path enforces FR-2.5 by rejecting updates to a {@code CLOSED} case.
 *
 * <p>Validation is lenient (no {@code @NotBlank}): a blank-but-non-null
 * {@code name} would clear the case name, which is undesirable, so the
 * service treats a blank name as "leave unchanged" rather than rejecting —
 * this keeps the partial-update contract uniform across all fields.
 */
public record CaseUpdateRequest(
        String name,
        String description,
        MatterType matterType,
        String owner) {
}
