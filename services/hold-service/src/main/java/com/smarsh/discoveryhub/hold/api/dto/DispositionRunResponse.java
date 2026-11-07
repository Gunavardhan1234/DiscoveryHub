package com.smarsh.discoveryhub.hold.api.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.DispositionRunEntity;

import java.time.Instant;
import java.util.Map;

/**
 * One disposition run for the Disposition screen (FR-5): counts of deleted /
 * skipped-on-hold / errored messages plus the per-type breakdown
 * ({@code deletedByType}) proving held mail survives a retention pass.
 */
public record DispositionRunResponse(
        String id,
        Instant startedAt,
        Instant finishedAt,
        String status,
        long scanned,
        long deleted,
        long skippedOnHold,
        long errors,
        Map<String, Long> deletedByType) {

    private static final TypeReference<Map<String, Long>> BY_TYPE = new TypeReference<>() {};

    public static DispositionRunResponse from(DispositionRunEntity e, ObjectMapper mapper) {
        return new DispositionRunResponse(
                e.id.toString(),
                e.startedAt == null ? null : e.startedAt.toInstant(),
                e.finishedAt == null ? null : e.finishedAt.toInstant(),
                e.status,
                e.scanned,
                e.deleted,
                e.skippedOnHold,
                e.errors,
                parseByType(e.deletedByType, mapper));
    }

    private static Map<String, Long> parseByType(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, BY_TYPE);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
