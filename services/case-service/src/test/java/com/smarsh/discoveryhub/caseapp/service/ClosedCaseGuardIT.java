package com.smarsh.discoveryhub.caseapp.service;

import com.smarsh.discoveryhub.caseapp.api.dto.CaseCreateRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseUpdateRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.CustodianRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceAddRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceBulkAddRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.SavedSearchRequest;
import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import com.smarsh.discoveryhub.caseapp.domain.model.MatterType;
import com.smarsh.discoveryhub.caseapp.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.OutboxRepository;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.testsupport.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Integration test proving FR-2.5 (closed case is read-only) end-to-end
 * through real Postgres + JPA + AOP weaving. Verifies:
 * <ul>
 *   <li>Adding custodians to a CLOSED case → 409 CASE_CLOSED</li>
 *   <li>Adding evidence (single + bulk) to a CLOSED case → 409 CASE_CLOSED</li>
 *   <li>Removing evidence from a CLOSED case → 409 CASE_CLOSED</li>
 *   <li>Deleting a saved search from a CLOSED case → 409 CASE_CLOSED</li>
 *   <li>Updating a CLOSED case's metadata → 409 CASE_CLOSED</li>
 *   <li>Transitioning to CLOSED writes a {@code case.closed.v1} outbox row</li>
 * </ul>
 *
 * <p>Kafka autoconfiguration is excluded (no broker in this test), so the
 * real {@code KafkaAuditEventPublisher} is swapped for a {@code @MockBean}.
 */
// Integration test, not a unit test: @SpringBootTest boots the REAL Spring context (real AOP
// weaving for @ClosedCaseGuard, real JPA/Hibernate, a real Postgres via PostgresTestBase's
// Testcontainers container) — this is the only place that proves the ClosedCaseGuardAspect proxy
// actually intercepts CustodianService/EvidenceService/SavedSearchService calls at runtime, which
// a mocked unit test could never verify (mocking bypasses Spring's proxy machinery entirely).
@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
class ClosedCaseGuardIT extends PostgresTestBase {

    // Still mocked: Kafka itself is excluded from this test context (see the annotation above), so
    // the real KafkaAuditEventPublisher would fail to construct — swapping in a @MockBean here
    // keeps this an integration test of persistence/AOP without also requiring a running broker.
    @MockBean
    private AuditEventPublisher auditEventPublisher;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::dbUsername);
        registry.add("spring.datasource.password", PostgresTestBase::dbPassword);
    }

    @Autowired CaseService caseService;
    @Autowired CustodianService custodianService;
    @Autowired EvidenceService evidenceService;
    @Autowired SavedSearchService savedSearchService;
    @Autowired OutboxRepository outbox;

    @Test
    void closedCaseBlocksCustodianAndEvidenceMutations() {
        var created = caseService.create(new CaseCreateRequest(
                "Closed Matter", "FR-2.5 test", MatterType.LITIGATION, "investigator@demo"));
        caseService.transition(created.id(), CaseStatus.ACTIVE);
        caseService.transition(created.id(), CaseStatus.UNDER_REVIEW);
        caseService.transition(created.id(), CaseStatus.CLOSED);

        // custodian add is blocked
        assertThatThrownBy(() -> custodianService.addCustodian(created.id(),
                new CustodianRequest("c-0007", "Ada Byron")))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).code()).isEqualTo("CASE_CLOSED"));

        // single evidence add is blocked
        assertThatThrownBy(() -> evidenceService.addEvidence(created.id(),
                new EvidenceAddRequest("msg-1", "MANUAL"), "investigator@demo"))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).code()).isEqualTo("CASE_CLOSED"));

        // bulk evidence add is blocked
        assertThatThrownBy(() -> evidenceService.addEvidenceBulk(created.id(),
                new EvidenceBulkAddRequest(List.of("msg-1", "msg-2"), "SEARCH"), "investigator@demo"))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).code()).isEqualTo("CASE_CLOSED"));
    }

    @Test
    void openCaseAllowsCustodianAndEvidenceMutations() {
        var created = caseService.create(new CaseCreateRequest(
                "Active Matter", "FR-2.5 positive test", MatterType.INVESTIGATION, "investigator@demo"));
        caseService.transition(created.id(), CaseStatus.ACTIVE);

        // custodian add works on active case
        var custodian = custodianService.addCustodian(created.id(),
                new CustodianRequest("c-0007", "Ada Byron"));
        assertThat(custodian.custodianId()).isEqualTo("c-0007");

        // evidence add works on active case
        var evidence = evidenceService.addEvidence(created.id(),
                new EvidenceAddRequest("msg-1", "MANUAL"), "investigator@demo");
        assertThat(evidence.messageId()).isEqualTo("msg-1");
    }

    @Test
    void closedCaseBlocksEvidenceRemovalAndCaseUpdate() {
        var created = caseService.create(new CaseCreateRequest(
                "Closed Matter", "FR-2.5 removal/update test", MatterType.LITIGATION, "investigator@demo"));
        caseService.transition(created.id(), CaseStatus.ACTIVE);
        // seed an evidence item so removal is a real operation, not a 404
        evidenceService.addEvidence(created.id(),
                new EvidenceAddRequest("msg-removable", "MANUAL"), "investigator@demo");
        caseService.transition(created.id(), CaseStatus.UNDER_REVIEW);
        caseService.transition(created.id(), CaseStatus.CLOSED);

        // evidence removal is blocked by the @ClosedCaseGuard aspect
        UUID evidenceId = evidenceService.listEvidence(created.id()).get(0).id();
        assertThatThrownBy(() -> evidenceService.removeEvidence(created.id(), evidenceId, "investigator@demo"))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).code()).isEqualTo("CASE_CLOSED"));

        // case metadata update is blocked by the inline check in CaseService.update
        assertThatThrownBy(() -> caseService.update(created.id(),
                new CaseUpdateRequest("Renamed", null, null, null)))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).code()).isEqualTo("CASE_CLOSED"));
    }

    @Test
    void openCaseAllowsEvidenceRemovalAndCaseUpdateAndAudits() {
        var created = caseService.create(new CaseCreateRequest(
                "Active Matter", "FR-7.1 removal/update audit test", MatterType.INVESTIGATION, "investigator@demo"));
        caseService.transition(created.id(), CaseStatus.ACTIVE);
        var added = evidenceService.addEvidence(created.id(),
                new EvidenceAddRequest("msg-to-remove", "SEARCH"), "investigator@demo");

        // removal works on an open case...
        evidenceService.removeEvidence(created.id(), added.id(), "investigator@demo");
        assertThat(evidenceService.listEvidence(created.id())).isEmpty();

        // ...and case metadata update works...
        var updated = caseService.update(created.id(),
                new CaseUpdateRequest("Renamed Matter", null, MatterType.LITIGATION, null));
        assertThat(updated.name()).isEqualTo("Renamed Matter");
        assertThat(updated.matterType()).isEqualTo(MatterType.LITIGATION);

        // ...and both emit their FR-7.1 audit actions (mock is reset before each test).
        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(auditEventPublisher, atLeastOnce()).publish(captor.capture(), eq(created.id().toString()));
        Set<String> actions = captor.getAllValues().stream()
                .map(AuditEventPayload::action).collect(Collectors.toSet());
        assertThat(actions).contains("EVIDENCE_REMOVED", "CASE_UPDATED");
    }

    @Test
    void closedCaseBlocksSavedSearchDeletion() {
        var created = caseService.create(new CaseCreateRequest(
                "Closed Matter", "FR-2.5 saved-search deletion test", MatterType.LITIGATION, "investigator@demo"));
        caseService.transition(created.id(), CaseStatus.ACTIVE);
        var savedSearch = savedSearchService.create(created.id(),
                new SavedSearchRequest("wire transfers", "{\"q\":\"wire transfer\"}"));
        caseService.transition(created.id(), CaseStatus.UNDER_REVIEW);
        caseService.transition(created.id(), CaseStatus.CLOSED);

        // saved-search deletion is blocked by the @ClosedCaseGuard aspect
        assertThatThrownBy(() -> savedSearchService.delete(created.id(), savedSearch.id()))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).code()).isEqualTo("CASE_CLOSED"));
    }

    @Test
    void openCaseAllowsSavedSearchDeletionAndAudits() {
        var created = caseService.create(new CaseCreateRequest(
                "Active Matter", "FR-7.1 saved-search deletion audit test", MatterType.INVESTIGATION, "investigator@demo"));
        caseService.transition(created.id(), CaseStatus.ACTIVE);
        var savedSearch = savedSearchService.create(created.id(),
                new SavedSearchRequest("wire transfers", "{\"q\":\"wire transfer\"}"));

        savedSearchService.delete(created.id(), savedSearch.id());
        assertThat(savedSearchService.list(created.id())).isEmpty();

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(auditEventPublisher, atLeastOnce()).publish(captor.capture(), eq(created.id().toString()));
        Set<String> actions = captor.getAllValues().stream()
                .map(AuditEventPayload::action).collect(Collectors.toSet());
        assertThat(actions).contains("SAVED_SEARCH_DELETED");
    }

    @Test
    void closingCaseEmitsOutboxRow() {
        var created = caseService.create(new CaseCreateRequest(
                "Outbox Test", "case.closed.v1 outbox", MatterType.REGULATORY_INQUIRY, "investigator@demo"));
        caseService.transition(created.id(), CaseStatus.ACTIVE);
        caseService.transition(created.id(), CaseStatus.UNDER_REVIEW);

        long outboxBefore = outbox.count();
        caseService.transition(created.id(), CaseStatus.CLOSED);

        var unpublished = outbox.findByPublishedAtIsNullOrderByCreatedAtAsc();
        assertThat(unpublished).hasSize((int) (outboxBefore + 1));

        var closedEvent = unpublished.stream()
                .filter(e -> Topics.CASE_CLOSED_V1.equals(e.topic))
                .filter(e -> created.id().toString().equals(e.key))
                .findFirst()
                .orElseThrow();
        assertThat(closedEvent.key).isEqualTo(created.id().toString());
        assertThat(closedEvent.payload).contains(created.id().toString());
        assertThat(closedEvent.publishedAt).isNull();
    }
}
