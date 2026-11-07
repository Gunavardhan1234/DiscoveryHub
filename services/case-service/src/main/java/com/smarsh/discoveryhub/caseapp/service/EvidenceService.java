package com.smarsh.discoveryhub.caseapp.service;

import com.smarsh.discoveryhub.caseapp.api.annotation.ClosedCaseGuard;
import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceAddRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceBulkAddRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceBulkAddResponse;
import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceResponse;
import com.smarsh.discoveryhub.caseapp.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseRepository;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.EvidenceItemEntity;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.EvidenceRepository;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

    private final EvidenceRepository evidence;
    private final CaseRepository cases;
    private final AuditEventPublisher audit;

    public EvidenceService(EvidenceRepository evidence, CaseRepository cases, AuditEventPublisher audit) {
        this.evidence = evidence;
        this.cases = cases;
        this.audit = audit;
    }

    // Idempotency: re-adding a message already attached to the case returns the existing item
    // instead of erroring or creating a duplicate row — calling this endpoint twice (e.g. a
    // retried HTTP request after a dropped response) has the same end state as calling it once,
    // which is what lets the frontend safely retry an "add to case" click without double-adding.
    @ClosedCaseGuard(0)
    public EvidenceResponse addEvidence(UUID caseId, EvidenceAddRequest request, String addedBy) {
        requireCaseExists(caseId);
        if (evidence.existsByCaseIdAndMessageId(caseId, request.messageId())) {
            // Idempotent: duplicate evidence add is a no-op returning the existing item
            return evidence.findByCaseIdOrderByAddedAtAsc(caseId).stream()
                    .filter(e -> e.messageId.equals(request.messageId()))
                    .findFirst()
                    .map(EvidenceResponse::from)
                    .orElseThrow();
        }
        EvidenceItemEntity entity = new EvidenceItemEntity();
        entity.id = UUID.randomUUID();
        entity.caseId = caseId;
        entity.messageId = request.messageId();
        entity.addedAt = OffsetDateTime.now();
        entity.addedBy = addedBy != null ? addedBy : "investigator@demo";
        entity.source = request.source() != null ? request.source() : "MANUAL";
        evidence.save(entity);
        publishAudit(caseId, entity.messageId, entity.addedBy, "EVIDENCE_ADDED",
                "{\"messageId\":\"" + entity.messageId + "\",\"source\":\"" + entity.source + "\"}");
        return EvidenceResponse.from(entity);
    }

    // FR-3.6 / FR-4: bulk "add all results to case" — the same idempotency check as the single-add
    // path runs per message id, so mixing already-attached and new ids in one request is safe.
    // ArrayList, not LinkedList: appended in order, read once at the end, never inserted into the
    // middle — ArrayList's O(1) amortized append and cache-friendly array layout are strictly
    // better here than a LinkedList's O(1) insert-anywhere, which this code never needs.
    @ClosedCaseGuard(0)
    public EvidenceBulkAddResponse addEvidenceBulk(UUID caseId, EvidenceBulkAddRequest request, String addedBy) {
        requireCaseExists(caseId);
        String source = request.source() != null ? request.source() : "SEARCH";
        String actor = addedBy != null ? addedBy : "investigator@demo";
        int added = 0;
        int skipped = 0;
        List<String> addedIds = new ArrayList<>();
        for (String messageId : request.messageIds()) {
            if (evidence.existsByCaseIdAndMessageId(caseId, messageId)) {
                skipped++;
                continue;
            }
            EvidenceItemEntity entity = new EvidenceItemEntity();
            entity.id = UUID.randomUUID();
            entity.caseId = caseId;
            entity.messageId = messageId;
            entity.addedAt = OffsetDateTime.now();
            entity.addedBy = actor;
            entity.source = source;
            evidence.save(entity);
            added++;
            addedIds.add(messageId);
        }
        if (added > 0) {
            publishAudit(caseId, null, actor, "EVIDENCE_ADDED",
                    "{\"added\":" + added + ",\"skipped\":" + skipped + ",\"source\":\"" + source + "\"}");
        }
        return new EvidenceBulkAddResponse(added, skipped, addedIds);
    }

    /**
     * Removes a single evidence item from a case (FR-7.1 {@code EVIDENCE_REMOVED}).
     * Guarded by {@link ClosedCaseGuard} like the add paths — a closed case is
     * read-only (FR-2.5), so removal is rejected with {@code 409 CASE_CLOSED}.
     * The removed item is recorded in the audit {@code before} field (what
     * existed prior to deletion); {@code after} is null since nothing remains.
     */
    @ClosedCaseGuard(0)
    public void removeEvidence(UUID caseId, UUID evidenceId, String removedBy) {
        requireCaseExists(caseId);
        EvidenceItemEntity entity = evidence.findById(evidenceId)
                .orElseThrow(() -> new NotFoundException("Evidence", evidenceId.toString()));
        // Don't leak cross-case existence: an evidence id from another case is
        // reported as not found for this case.
        // Security-conscious error handling: returns the same 404 whether the id genuinely
        // doesn't exist or exists under a different case, so callers can't probe for the
        // existence of evidence ids belonging to cases they weren't given the id for.
        if (!caseId.equals(entity.caseId)) {
            throw new NotFoundException("Evidence", evidenceId.toString());
        }
        String before = "{\"messageId\":\"" + entity.messageId
                + "\",\"source\":\"" + entity.source + "\"}";
        evidence.delete(entity);
        publishRemovalAudit(caseId, entity.messageId, removedBy, before);
    }

    @Transactional(readOnly = true)
    public List<EvidenceResponse> listEvidence(UUID caseId) {
        requireCaseExists(caseId);
        return evidence.findByCaseIdOrderByAddedAtAsc(caseId).stream()
                .map(EvidenceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countByCaseId(UUID caseId) {
        return evidence.countByCaseId(caseId);
    }

    private void requireCaseExists(UUID caseId) {
        if (!cases.existsById(caseId)) {
            throw new NotFoundException("Case", caseId.toString());
        }
    }

    /**
     * Best-effort audit publish (FR-7.1: "evidence added") — never fails the
     * request. {@code targetId} is the message id for a single add, or the
     * case id itself for a bulk-add summary.
     */
    private void publishAudit(UUID caseId, String messageId, String actor, String action, String after) {
        try {
            audit.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    actor != null ? actor : "investigator@demo",
                    action,
                    "EVIDENCE",
                    messageId != null ? messageId : caseId.toString(),
                    caseId.toString(),
                    null,
                    after,
                    CorrelationId.current()), caseId.toString());
        } catch (Exception e) {
            log.warn("Failed to publish {} audit event for case {}", action, caseId, e);
        }
    }

    /** Best-effort audit publish for EVIDENCE_REMOVED — records the removed item in {@code before}. */
    private void publishRemovalAudit(UUID caseId, String messageId, String actor, String before) {
        try {
            audit.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    actor != null ? actor : "investigator@demo",
                    "EVIDENCE_REMOVED",
                    "EVIDENCE",
                    messageId != null ? messageId : caseId.toString(),
                    caseId.toString(),
                    before,
                    null,
                    CorrelationId.current()), caseId.toString());
        } catch (Exception e) {
            log.warn("Failed to publish EVIDENCE_REMOVED audit event for case {}", caseId, e);
        }
    }
}
