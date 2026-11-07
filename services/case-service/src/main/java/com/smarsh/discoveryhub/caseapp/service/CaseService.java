package com.smarsh.discoveryhub.caseapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseCreateRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseResponse;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseUpdateRequest;
import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import com.smarsh.discoveryhub.caseapp.domain.model.CaseStateMachine;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseEntity;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseRepository;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.OutboxEntity;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.OutboxRepository;
import com.smarsh.discoveryhub.caseapp.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.events.CaseClosed;
import com.smarsh.discoveryhub.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

// @Service: registers this as a Spring-managed singleton bean in the business/service layer,
// distinct from @RestController (HTTP) and @Repository (persistence) — Spring's stereotype
// annotations double as Separation of Concerns markers for where logic is allowed to live.
// @Transactional (class-level): every public method runs inside a database transaction by
// default via a Spring AOP proxy, so a failure partway through (e.g. after cases.save() but
// before the outbox write) rolls back both instead of leaving the case and its event out of sync.
@Service
@Transactional
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    // SRP: this class owns case lifecycle/CRUD business rules only; persistence is delegated to
    // CaseRepository/OutboxRepository and event delivery to AuditEventPublisher, so a change to
    // "how we store a case" or "how we publish an event" never requires touching this class.
    private final CaseRepository cases;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    // Dependency Inversion: depends on the AuditEventPublisher abstraction, not the concrete
    // Kafka publisher, so a unit test can substitute a Mockito mock (see CaseServiceTest).
    private final AuditEventPublisher audit;

    // Constructor Injection: all dependencies are required, final, and supplied at construction —
    // Spring wires the real beans in production; tests new CaseService(...) directly with mocks/
    // fakes, so this class is unit-testable without a Spring context or a running database.
    public CaseService(CaseRepository cases, OutboxRepository outbox, ObjectMapper objectMapper,
                        AuditEventPublisher audit) {
        this.cases = cases;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    // UUID.randomUUID(): a globally unique, immutable case identifier assigned by the service
    // itself rather than an auto-increment DB key — services can mint ids before the row is
    // persisted (needed for the Location header / URI) and ids never collide across services
    // even though each microservice owns its own database (NFR-1).
    public CaseResponse create(CaseCreateRequest request) {
        CaseEntity entity = new CaseEntity();
        entity.id = UUID.randomUUID();
        entity.name = request.name();
        entity.description = request.description();
        entity.matterType = request.matterType();
        entity.owner = request.owner();
        // Every case starts DRAFT (FR-2.2) — the only entry point into CaseStateMachine's table.
        entity.status = CaseStatus.DRAFT;
        OffsetDateTime now = OffsetDateTime.now();
        entity.createdAt = now;
        entity.updatedAt = now;
        cases.save(entity);
        cases.flush();
        publishAudit(entity, "CASE_CREATED", null,
                "{\"name\":\"" + escape(entity.name) + "\",\"matterType\":\"" + entity.matterType + "\",\"status\":\"" + entity.status + "\"}");
        return CaseResponse.from(entity);
    }

    // @Transactional(readOnly = true) overrides the class-level default for a read path: it lets
    // Hibernate skip dirty-checking and lets the DB driver optimize the transaction, since nothing
    // here writes. Page<CaseResponse>::map is a stream-style transform, not java.util.stream —
    // Spring Data's Page keeps the pagination metadata (totalElements, totalPages) while mapping
    // each CaseEntity to its DTO via the CaseResponse::from method reference (Java 8 method
    // reference — equivalent to `c -> CaseResponse.from(c)` but names the intent directly).
    @Transactional(readOnly = true)
    public Page<CaseResponse> list(CaseStatus status, String q, Pageable pageable) {
        return cases.search(status, q, pageable).map(CaseResponse::from);
    }

    @Transactional(readOnly = true)
    public CaseResponse get(UUID id) {
        return CaseResponse.from(requireCase(id));
    }

    // SRP in action: this method only orchestrates (load -> validate -> mutate -> persist ->
    // notify); the actual "is this transition legal" business rule lives entirely in
    // CaseStateMachine, so the two responsibilities (state-machine rules vs. persistence/eventing
    // orchestration) can change independently without touching each other.
    public CaseResponse transition(UUID id, CaseStatus to) {
        CaseEntity entity = requireCase(id);
        CaseStatus from = entity.status;
        // Throws InvalidCaseTransitionException (-> 409) before any field is mutated below —
        // fail fast, so a rejected transition never leaves the entity half-updated.
        CaseStateMachine.assertTransition(from, to);
        entity.status = to;
        cases.save(entity);
        cases.flush();
        if (to == CaseStatus.CLOSED) {
            emitCaseClosed(entity);
        }
        publishAudit(entity, "CASE_TRANSITIONED",
                "{\"status\":\"" + from + "\"}", "{\"status\":\"" + to + "\"}");
        return CaseResponse.from(entity);
    }

    /**
     * Partial update of a case's mutable metadata (FR-2.3 / FR-7.1
     * {@code CASE_UPDATED}). Each non-null field of {@link CaseUpdateRequest}
     * is applied; {@code null} fields are left unchanged. {@code status} is
     * not updatable here — lifecycle transitions go through {@link #transition}.
     *
     * <p>FR-2.5: a closed case is read-only, so updating a {@code CLOSED} case
     * is rejected with {@code 409 CASE_CLOSED}. The check is inline (rather
     * than via {@code @ClosedCaseGuard}) to mirror {@link #transition}, which
     * is also a case-level mutation that does its own state-machine check and
     * is explicitly exempt from the guard aspect.
     */
    public CaseResponse update(UUID id, CaseUpdateRequest request) {
        CaseEntity entity = requireCase(id);
        if (entity.status == CaseStatus.CLOSED) {
            throw new ConflictException("CASE_CLOSED",
                    "Case '" + id + "' is closed and read-only — updates are not allowed (FR-2.5)");
        }
        String before = snapshot(entity);
        if (request.name() != null && !request.name().isBlank()) {
            entity.name = request.name();
        }
        if (request.description() != null) {
            entity.description = request.description();
        }
        if (request.matterType() != null) {
            entity.matterType = request.matterType();
        }
        if (request.owner() != null && !request.owner().isBlank()) {
            entity.owner = request.owner();
        }
        cases.save(entity);
        cases.flush();
        String after = snapshot(entity);
        publishAudit(entity, "CASE_UPDATED", before, after);
        return CaseResponse.from(entity);
    }

    /** Compact before/after snapshot for the {@code CASE_UPDATED} audit entry. */
    private static String snapshot(CaseEntity c) {
        return "{\"name\":\"" + escape(c.name) + "\",\"matterType\":\"" + c.matterType
                + "\",\"owner\":\"" + escape(c.owner) + "\"}";
    }

    /**
     * Writes a {@code case.closed.v1} outbox row in the same transaction as the
     * status update (transactional outbox pattern — D-008). An outbox poller
     * (M1's Kafka wiring) reads unpublished rows and publishes them to Kafka.
     * hold-service consumes this to release all holds on the case (FR-4.5).
     */
    // Transactional Outbox pattern: the outbox row is saved via the SAME JPA EntityManager/
    // transaction as entity.status above (both run inside this @Transactional method), so either
    // both the status change and the event-to-be-published commit together, or neither does —
    // there is no window where the case is CLOSED in Postgres but the case.closed.v1 event was
    // silently dropped because Kafka was unreachable at that instant. A separate poller process
    // (not shown here) reads unpublished rows and forwards them to Kafka asynchronously; this
    // decouples "durably recorded" (this transaction, synchronous) from "delivered to hold-service"
    // (the poller, asynchronous) — the caller's request never blocks on Kafka being up.
    private void emitCaseClosed(CaseEntity entity) {
        CaseClosed payload = new CaseClosed(
                entity.id.toString(), Instant.now(), entity.owner);
        OutboxEntity outboxRow = new OutboxEntity();
        outboxRow.topic = Topics.CASE_CLOSED_V1;
        outboxRow.key = entity.id.toString();
        outboxRow.payload = serialize(payload);
        outboxRow.createdAt = OffsetDateTime.now();
        outbox.save(outboxRow);
    }

    // Checked-to-unchecked exception translation: JsonProcessingException is a checked exception
    // (the compiler forces every caller to handle it), but a serialization failure here means the
    // outbox row itself is malformed — the caller (transition/create) has no meaningful recovery,
    // so it's wrapped in an unchecked IllegalStateException instead of forcing every call site in
    // this class to declare `throws JsonProcessingException` for a failure mode they can't fix.
    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }

    /**
     * Publishes {@code audit.events.v1} for a case lifecycle event (FR-7.1:
     * "case created/updated/transitioned"). Best-effort — a Kafka hiccup here
     * must never fail the case request itself, since the row is already
     * durably committed by this point.
     */
    // Deliberate generic `catch (Exception e)`: normally a code smell (the training material
    // explicitly warns against catching generic Exception), but justified here — this is a
    // best-effort side channel after the case row is already durably committed (FR-7.1 audit is
    // "nice to have" for this call, not the primary operation), so ANY publish failure (network,
    // serialization, a checked exception from the Kafka client) must degrade to a logged warning
    // rather than fail the case create/update/transition the caller is actually waiting on.
    private void publishAudit(CaseEntity entity, String action, String before, String after) {
        try {
            audit.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    Actor.current(),
                    action,
                    "CASE",
                    entity.id.toString(),
                    entity.id.toString(),
                    before,
                    after,
                    CorrelationId.current()), entity.id.toString());
        } catch (Exception e) {
            log.warn("Failed to publish {} audit event for case {}", action, entity.id, e);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\"", "'");
    }

    // Optional.orElseThrow with a lambda: findById returns Optional<CaseEntity> rather than a
    // nullable reference, forcing every caller to explicitly decide the not-found behavior instead
    // of risking a NullPointerException deep in unrelated code — the lambda defers constructing
    // NotFoundException until the empty case actually happens (no wasted allocation on the hot path).
    @Transactional(readOnly = true)
    CaseEntity requireCase(UUID id) {
        return cases.findById(id)
                .orElseThrow(() -> new NotFoundException("Case", id.toString()));
    }
}
