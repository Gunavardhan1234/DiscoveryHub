package com.smarsh.discoveryhub.hold.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

/**
 * Request to place a legal hold (FR-4.1). Hold placement is asynchronous:
 * hold-service persists a {@code PENDING} row and a transactional outbox entry,
 * returning 202 immediately; the archive hold-applier applies the hold to the
 * matching messages and reports progress via {@code hold.applied.v1}.
 *
 * @param caseId       the case the hold belongs to
 * @param custodians   scope: custodian ids whose messages are held
 * @param from         scope: inclusive lower bound on message timestamp
 * @param to           scope: inclusive upper bound on message timestamp
 * @param terms        scope: optional full-text terms (matched against
 *                     subject/body)
 * @param placedBy     actor placing the hold (audit attribution)
 */
public record HoldRequest(
        @NotBlank String caseId,
        List<String> custodians,
        Instant from,
        Instant to,
        String terms,
        String placedBy) {

    public HoldRequest {
        custodians = custodians == null ? List.of() : List.copyOf(custodians);
    }
}
