package com.smarsh.discoveryhub.events;

import java.time.Instant;

/**
 * Payload for {@code case.closed.v1} — emitted by {@code case-service} (via its
 * transactional outbox) when a case transitions to {@code CLOSED}. Consumed by
 * {@code hold-service} (the {@code hold-case-closed} group) to release every
 * hold attached to that case: it flips each hold to {@code RELEASING} and writes
 * {@code hold.commands.v1{action:RELEASE}} outbox rows, so the archive
 * hold-applier performs the actual Mongo {@code $pull}.
 *
 * @see com.smarsh.discoveryhub.events.Topics#CASE_CLOSED_V1
 */
public record CaseClosed(
        String caseId,
        Instant closedAt,
        String actor) {
}
