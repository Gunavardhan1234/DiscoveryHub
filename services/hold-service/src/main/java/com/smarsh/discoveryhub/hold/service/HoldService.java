package com.smarsh.discoveryhub.hold.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.events.HoldCommand;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.hold.api.dto.HoldRequest;
import com.smarsh.discoveryhub.hold.api.dto.HoldResponse;
import com.smarsh.discoveryhub.hold.domain.model.HoldStatus;
import com.smarsh.discoveryhub.hold.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.hold.domain.port.CaseStatusPort;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.HoldEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.HoldRepository;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.OutboxEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Places and reads legal holds (FR-4.1/FR-4.3). Placement is asynchronous by
 * design: the hold row is persisted {@code PENDING} and a {@code hold.commands.v1}
 * outbox row is written in the <em>same</em> Postgres transaction (transactional
 * outbox — D-008), so the 202 returned to the caller means "durably recorded",
 * not "applied". The {@link com.smarsh.discoveryhub.hold.infrastructure.messaging.OutboxPoller}
 * publishes the command; the archive hold-applier applies it to the matching
 * messages and reports progress back via {@code hold.applied.v1}, which the
 * {@code hold-tracker} consumer folds into {@code heldCount}/status.
 */
@Service
@Transactional
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final HoldRepository holds;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final AuditEventPublisher audit;
    private final CaseStatusPort caseStatus;

    public HoldService(HoldRepository holds, OutboxRepository outbox, ObjectMapper objectMapper,
                        AuditEventPublisher audit, CaseStatusPort caseStatus) {
        this.holds = holds;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.caseStatus = caseStatus;
    }

    public HoldResponse place(HoldRequest request) {
        UUID holdId = UUID.randomUUID();
        UUID caseId = parseCaseId(request.caseId());

        // FR-2.5: a closed case is read-only — reject new holds at the service
        // boundary before any row is written. case-service is the source of
        // truth for status; see CaseStatusPort for the fail-open rationale.
        if (caseStatus.isClosed(caseId)) {
            throw new ConflictException("CASE_CLOSED",
                    "Case '" + caseId + "' is closed — new legal holds are not allowed (FR-2.5)");
        }

        HoldEntity hold = new HoldEntity();
        hold.id = holdId;
        hold.caseId = caseId;
        hold.status = HoldStatus.PENDING;
        hold.scopeCustodians = request.custodians().toArray(new String[0]);
        hold.scopeFrom = request.from() == null ? null : OffsetDateTime.ofInstant(request.from(), ZoneOffset.UTC);
        hold.scopeTo = request.to() == null ? null : OffsetDateTime.ofInstant(request.to(), ZoneOffset.UTC);
        hold.scopeTerms = blankToNull(request.terms());
        hold.heldCount = 0;
        hold.appliedCount = 0;
        hold.placedBy = request.placedBy();
        hold.placedAt = OffsetDateTime.now(ZoneOffset.UTC);
        holds.save(hold);

        emitHoldCommand(hold, HoldCommand.PLACE);
        publishAudit(hold, "HOLD_PLACED", request.placedBy());
        return HoldResponse.from(hold);
    }

    @Transactional(readOnly = true)
    public HoldResponse get(UUID holdId) {
        return HoldResponse.from(requireHold(holdId));
    }

    @Transactional(readOnly = true)
    public List<HoldResponse> list(UUID caseId) {
        List<HoldEntity> rows = caseId == null
                ? holds.findAllByOrderByPlacedAtAsc()
                : holds.findByCaseIdOrderByPlacedAtAsc(caseId);
        return rows.stream().map(HoldResponse::from).toList();
    }

    /**
     * Releases a hold (FR-4.5): flips it to {@code RELEASING} and writes a
     * {@code hold.commands.v1{action:RELEASE}} outbox row in the same
     * transaction. The archive hold-applier performs the Mongo {@code $pull};
     * the {@code hold-tracker} consumer flips the status to {@code RELEASED}
     * once {@code hold.applied.v1} reports {@code completed:true}. Releasing a
     * hold that is already {@code RELEASING}/{@code RELEASED} is a no-op so
     * redeliveries and double-clicks stay idempotent.
     */
    public HoldResponse release(UUID holdId) {
        HoldEntity hold = requireHold(holdId);
        if (hold.status == HoldStatus.RELEASING || hold.status == HoldStatus.RELEASED) {
            return HoldResponse.from(hold);
        }
        hold.status = HoldStatus.RELEASING;
        holds.save(hold);
        emitHoldCommand(hold, HoldCommand.RELEASE);
        publishAudit(hold, "HOLD_RELEASED", hold.placedBy);
        return HoldResponse.from(hold);
    }

    /**
     * Releases every non-terminal hold on a case. Invoked by the
     * {@code hold-case-closed} consumer when {@code case.closed.v1} arrives
     * (execution-plan Day 2 M4: "Release hold on case.closed.v1").
     *
     * @return the number of holds transitioned to {@code RELEASING}
     */
    public int releaseAllForCase(UUID caseId) {
        List<HoldEntity> releasable = holds.findByCaseIdAndStatusIn(
                caseId, List.of(HoldStatus.PENDING, HoldStatus.ACTIVE, HoldStatus.FAILED));
        for (HoldEntity hold : releasable) {
            hold.status = HoldStatus.RELEASING;
            holds.save(hold);
            emitHoldCommand(hold, HoldCommand.RELEASE);
            publishAudit(hold, "HOLD_RELEASED", hold.placedBy);
        }
        return releasable.size();
    }

    /**
     * Writes a {@code hold.commands.v1} outbox row in the current transaction.
     * Mirrors case-service's {@code case.closed.v1} outbox: the poller reads
     * unpublished rows and publishes them to Kafka, then stamps
     * {@code published_at}.
     */
    private void emitHoldCommand(HoldEntity hold, String action) {
        HoldCommand command = new HoldCommand(
                hold.id.toString(),
                hold.caseId.toString(),
                action,
                hold.scopeCustodians == null ? java.util.List.of() : java.util.Arrays.asList(hold.scopeCustodians),
                hold.scopeFrom == null ? null : hold.scopeFrom.toInstant(),
                hold.scopeTo == null ? null : hold.scopeTo.toInstant(),
                hold.scopeTerms,
                hold.placedBy);
        OutboxEntity row = new OutboxEntity();
        row.topic = Topics.HOLD_COMMANDS_V1;
        row.key = hold.id.toString();
        row.payload = serialize(command);
        row.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        outbox.save(row);
    }

    /**
     * Publishes {@code audit.events.v1} for a hold state change
     * (execution-plan Day 1 M5: "make ingest + hold emit audit.events.v1").
     * Best-effort — a Kafka hiccup here must never fail the hold request
     * itself, since the hold row + outbox command are already durably
     * committed by this point.
     */
    private void publishAudit(HoldEntity hold, String action, String actor) {
        try {
            String after = serialize(new HoldAuditSnapshot(hold.status.name(), hold.scopeCustodians));
            audit.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    actor,
                    action,
                    "HOLD",
                    hold.id.toString(),
                    hold.caseId.toString(),
                    null,
                    after,
                    CorrelationId.current()), hold.id.toString());
        } catch (Exception e) {
            log.warn("Failed to publish {} audit event for hold {}", action, hold.id, e);
        }
    }

    private record HoldAuditSnapshot(String status, String[] custodians) {
    }

    HoldEntity requireHold(UUID holdId) {
        return holds.findById(holdId)
                .orElseThrow(() -> new NotFoundException("Hold", holdId.toString()));
    }

    private static UUID parseCaseId(String caseId) {
        try {
            return UUID.fromString(caseId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid caseId: must be a UUID", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }

    /** Resolves the hold-set scope timestamp for unit tests; not load-bearing yet. */
    static Instant nowUtc() {
        return Instant.now();
    }
}
