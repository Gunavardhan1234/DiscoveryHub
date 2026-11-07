package com.smarsh.discoveryhub.caseapp.service;

import com.smarsh.discoveryhub.caseapp.api.annotation.ClosedCaseGuard;
import com.smarsh.discoveryhub.caseapp.api.dto.SavedSearchRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.SavedSearchResponse;
import com.smarsh.discoveryhub.caseapp.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseRepository;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.SavedSearchEntity;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.SavedSearchRepository;
import com.smarsh.discoveryhub.common.Actor;
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
public class SavedSearchService {

    private static final Logger log = LoggerFactory.getLogger(SavedSearchService.class);

    private final SavedSearchRepository savedSearches;
    private final CaseRepository cases;
    private final AuditEventPublisher audit;

    public SavedSearchService(SavedSearchRepository savedSearches, CaseRepository cases, AuditEventPublisher audit) {
        this.savedSearches = savedSearches;
        this.cases = cases;
        this.audit = audit;
    }

    // FR-3.5: persists the query+filters as an opaque JSON string (SavedSearchEntity.spec) rather
    // than a search-service-specific type — case-service owns saved searches but has zero
    // knowledge of search-service's QuerySpec shape, keeping the two services decoupled (NFR-1).
    @ClosedCaseGuard(0)
    public SavedSearchResponse create(UUID caseId, SavedSearchRequest request) {
        requireCaseExists(caseId);
        SavedSearchEntity entity = new SavedSearchEntity();
        entity.id = UUID.randomUUID();
        entity.caseId = caseId;
        entity.name = request.name();
        entity.spec = request.spec();
        entity.createdAt = OffsetDateTime.now();
        savedSearches.save(entity);
        publishAudit(caseId, entity);
        return SavedSearchResponse.from(entity);
    }

    @Transactional(readOnly = true)
    public List<SavedSearchResponse> list(UUID caseId) {
        requireCaseExists(caseId);
        return savedSearches.findByCaseIdOrderByCreatedAtDesc(caseId).stream()
                .map(SavedSearchResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public SavedSearchResponse get(UUID id) {
        return SavedSearchResponse.from(savedSearches.findById(id)
                .orElseThrow(() -> new NotFoundException("SavedSearch", id.toString())));
    }

    /**
     * Deletes a saved search (FR-7.1 {@code SAVED_SEARCH_DELETED}), rejecting
     * with {@code 409 CASE_CLOSED} if the case is closed (FR-2.5: a closed
     * case is read-only — no new evidence, custodians, holds, exports, or
     * saved-search mutations). The audit event is published only after the
     * delete succeeds, so a failed deletion leaves no audit trail claiming
     * otherwise.
     */
    @ClosedCaseGuard(0)
    public void delete(UUID caseId, UUID id) {
        SavedSearchEntity entity = savedSearches.findById(id)
                .orElseThrow(() -> new NotFoundException("SavedSearch", id.toString()));
        // Don't leak cross-case existence: a search id from another case is
        // reported as not found for this case (same rule as removeEvidence).
        if (!caseId.equals(entity.caseId)) {
            throw new NotFoundException("SavedSearch", id.toString());
        }
        savedSearches.delete(entity);
        publishRemovalAudit(entity);
    }

    private void requireCaseExists(UUID caseId) {
        if (!cases.existsById(caseId)) {
            throw new NotFoundException("Case", caseId.toString());
        }
    }

    /** Best-effort audit publish (FR-7.1: "search saved") — never fails the request. */
    private void publishAudit(UUID caseId, SavedSearchEntity entity) {
        try {
            audit.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    Actor.current(),
                    "SAVED_SEARCH_CREATED",
                    "SAVED_SEARCH",
                    entity.id.toString(),
                    caseId.toString(),
                    null,
                    savedSearchJson(entity),
                    CorrelationId.current()), caseId.toString());
        } catch (Exception e) {
            log.warn("Failed to publish SAVED_SEARCH_CREATED audit event for case {}", caseId, e);
        }
    }

    /**
     * Best-effort audit publish for {@code SAVED_SEARCH_DELETED} (FR-7.1) —
     * never fails the request. Mirrors {@code EVIDENCE_REMOVED}: the deleted
     * search is recorded in {@code before} (what existed prior to deletion) and
     * {@code after} is null since nothing remains.
     */
    private void publishRemovalAudit(SavedSearchEntity entity) {
        String caseId = entity.caseId == null ? null : entity.caseId.toString();
        try {
            audit.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    Actor.current(),
                    "SAVED_SEARCH_DELETED",
                    "SAVED_SEARCH",
                    entity.id.toString(),
                    caseId,
                    savedSearchJson(entity),
                    null,
                    CorrelationId.current()), caseId);
        } catch (Exception e) {
            log.warn("Failed to publish SAVED_SEARCH_DELETED audit event for saved search {} on case {}",
                    entity.id, caseId, e);
        }
    }

    private static String savedSearchJson(SavedSearchEntity entity) {
        return "{\"name\":\"" + (entity.name == null ? "" : entity.name.replace("\"", "'")) + "\"}";
    }
}
