package com.smarsh.discoveryhub.hold.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.CaseClosed;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.hold.service.HoldService;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumes {@code case.closed.v1} in the {@code hold-case-closed} group
 * (execution-plan Day 2 M4: "Release hold on case.closed.v1"). When a case is
 * closed, every non-terminal hold on that case is flipped to {@code RELEASING}
 * and a {@code hold.commands.v1{action:RELEASE}} outbox row is written per hold
 * — the archive hold-applier then {@code $pull}s each hold id, so a message
 * covered by an overlapping hold from another case stays protected (FR-4.5).
 *
 * <p>Redeliveries are safe: an already-{@code RELEASING}/{@code RELEASED} hold
 * is skipped by {@link HoldService#releaseAllForCase}, so at-least-once
 * delivery cannot double-release.
 *
 * <p>case-service writes the {@link CaseClosed} payload to its outbox; its
 * poller may publish it raw or wrapped in an {@code EventEnvelope}. This
 * consumer accepts both shapes so the hold side does not depend on the exact
 * publisher wiring.
 */
@Component
public class CaseClosedConsumer {

    private static final Logger log = LoggerFactory.getLogger(CaseClosedConsumer.class);

    private final ObjectMapper mapper;
    private final HoldService holdService;

    public CaseClosedConsumer(ObjectMapper mapper, HoldService holdService) {
        this.mapper = mapper;
        this.holdService = holdService;
    }

    @KafkaListener(topics = Topics.CASE_CLOSED_V1, groupId = "hold-case-closed")
    @Transactional
    public void onCaseClosed(String value) {
        CaseClosed closed = parse(value);
        UUID caseId = parseId(closed == null ? null : closed.caseId());
        if (caseId == null) {
            log.warn("case.closed.v1 with missing/unparsable caseId; skipping: {}", value);
            return;
        }
        int released = holdService.releaseAllForCase(caseId);
        log.info("case.closed.v1 for case {} -> {} hold(s) moved to RELEASING", caseId, released);
    }

    /** Accepts either an {@code EventEnvelope<CaseClosed>} or a raw {@link CaseClosed}. */
    private CaseClosed parse(String value) {
        try {
            JsonNode root = mapper.readTree(value);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            return mapper.treeToValue(payload, CaseClosed.class);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Cannot parse case.closed.v1 payload", e);
        }
    }

    private UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
