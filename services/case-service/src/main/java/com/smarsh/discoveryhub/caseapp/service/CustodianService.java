package com.smarsh.discoveryhub.caseapp.service;

import com.smarsh.discoveryhub.caseapp.api.annotation.ClosedCaseGuard;
import com.smarsh.discoveryhub.caseapp.api.dto.CustodianRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.CustodianResponse;
import com.smarsh.discoveryhub.caseapp.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseCustodianEntity;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseRepository;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CustodianRepository;
import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class CustodianService {

    private static final Logger log = LoggerFactory.getLogger(CustodianService.class);

    private final CustodianRepository custodians;
    private final CaseRepository cases;
    private final AuditEventPublisher audit;

    public CustodianService(CustodianRepository custodians, CaseRepository cases, AuditEventPublisher audit) {
        this.custodians = custodians;
        this.cases = cases;
        this.audit = audit;
    }

    // Custom annotation + AOP (see ClosedCaseGuardAspect): @ClosedCaseGuard(0) marks that
    // parameter index 0 (caseId) must belong to a non-CLOSED case, enforcing FR-2.5 ("a closed
    // case is read-only") without an `if (case.status == CLOSED) throw ...` copy-pasted into every
    // mutating method here and in EvidenceService/SavedSearchService — Open/Closed Principle: new
    // guarded methods just add the annotation, the aspect's logic never needs to change.
    @ClosedCaseGuard(0)
    public CustodianResponse addCustodian(UUID caseId, CustodianRequest request) {
        requireCaseExists(caseId);
        CaseCustodianEntity.CaseCustodianId id = new CaseCustodianEntity.CaseCustodianId(caseId, request.custodianId());
        // Fail fast with a specific custom exception (not a generic 500) so the API contract is
        // explicit: attaching the same custodian twice is a conflict, not a server error.
        if (custodians.existsById(id)) {
            throw new ConflictException("DUPLICATE_CUSTODIAN",
                    "Custodian '" + request.custodianId() + "' is already attached to case '" + caseId + "'");
        }
        CaseCustodianEntity entity = new CaseCustodianEntity();
        entity.id = id;
        entity.custodianName = request.custodianName();
        entity.addedAt = OffsetDateTime.now();
        custodians.save(entity);
        publishAudit(caseId, entity, "CUSTODIAN_ADDED");
        return CustodianResponse.from(entity);
    }

    /**
     * Detaches a custodian from a case (FR-7.1 {@code CUSTODIAN_REMOVED}).
     * Loaded before the delete so the removed row can be recorded in the audit
     * {@code before} field; the audit event is published only after the delete
     * succeeds, so a failed removal leaves no audit trail claiming otherwise.
     */
    @ClosedCaseGuard(0)
    public void removeCustodian(UUID caseId, String custodianId) {
        requireCaseExists(caseId);
        CaseCustodianEntity.CaseCustodianId id = new CaseCustodianEntity.CaseCustodianId(caseId, custodianId);
        CaseCustodianEntity entity = custodians.findById(id)
                .orElseThrow(() -> new NotFoundException("Custodian", custodianId + " on case " + caseId));
        custodians.deleteById(id);
        publishRemovalAudit(caseId, entity);
    }

    // List, not Set: custodians are returned in a meaningful, stable order (added-at ascending,
    // enforced by the repository query name) for display — a Set would not guarantee that order.
    // Streams + toList(): declarative entity-to-DTO mapping instead of a manual for-loop building
    // an ArrayList by hand.
    @Transactional(readOnly = true)
    public List<CustodianResponse> listCustodians(UUID caseId) {
        requireCaseExists(caseId);
        return custodians.findByIdCaseIdOrderByAddedAtAsc(caseId).stream()
                .map(CustodianResponse::from)
                .toList();
    }

    private void requireCaseExists(UUID caseId) {
        if (!cases.existsById(caseId)) {
            throw new NotFoundException("Case", caseId.toString());
        }
    }

    /** Best-effort audit publish (FR-7.1: "custodian added") — never fails the request. */
    private void publishAudit(UUID caseId, CaseCustodianEntity entity, String action) {
        try {
            audit.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    Actor.current(),
                    action,
                    "CUSTODIAN",
                    entity.id.getCustodianId(),
                    caseId.toString(),
                    null,
                    custodianJson(entity),
                    CorrelationId.current()), caseId.toString());
        } catch (Exception e) {
            log.warn("Failed to publish {} audit event for custodian {} on case {}", action, entity.id, caseId, e);
        }
    }

    /**
     * Best-effort audit publish for {@code CUSTODIAN_REMOVED} (FR-7.1) — never
     * fails the request. Mirrors {@code EVIDENCE_REMOVED}: the detached
     * custodian is recorded in {@code before} (what existed prior to deletion)
     * and {@code after} is null since nothing remains.
     */
    private void publishRemovalAudit(UUID caseId, CaseCustodianEntity entity) {
        try {
            audit.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    Actor.current(),
                    "CUSTODIAN_REMOVED",
                    "CUSTODIAN",
                    entity.id.getCustodianId(),
                    caseId.toString(),
                    custodianJson(entity),
                    null,
                    CorrelationId.current()), caseId.toString());
        } catch (Exception e) {
            log.warn("Failed to publish CUSTODIAN_REMOVED audit event for custodian {} on case {}",
                    entity.id, caseId, e);
        }
    }

    private static String custodianJson(CaseCustodianEntity entity) {
        return "{\"custodianId\":\"" + entity.id.getCustodianId() + "\",\"custodianName\":\""
                + (entity.custodianName == null ? "" : entity.custodianName.replace("\"", "'")) + "\"}";
    }
}
