package com.smarsh.discoveryhub.caseapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseCreateRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseUpdateRequest;
import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import com.smarsh.discoveryhub.caseapp.domain.model.MatterType;
import com.smarsh.discoveryhub.caseapp.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseEntity;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseRepository;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.OutboxEntity;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.OutboxRepository;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * FR-7.1: case-service must audit "case created" and "case
 * transitioned" — this was previously a silent gap (case-service published
 * no audit events at all). Constructs {@link CaseService} directly (rather
 * than {@code @InjectMocks}) so a real {@link ObjectMapper} backs outbox JSON
 * serialization.
 */
// @ExtendWith(MockitoExtension.class): wires JUnit 5 to Mockito so @Mock fields are initialized
// automatically before each test — no manual MockitoAnnotations.openMocks() boilerplate.
@ExtendWith(MockitoExtension.class)
class CaseServiceTest {

    // Mockito mocks: CaseRepository/OutboxRepository/AuditEventPublisher are all interfaces this
    // class depends on (Dependency Inversion again pays off) — mocking them means this test
    // exercises ONLY CaseService's own logic, with zero real database or Kafka broker involved, so
    // it runs in milliseconds and never flakes due to infrastructure.
    @Mock CaseRepository cases;
    @Mock OutboxRepository outbox;
    @Mock AuditEventPublisher audit;

    // A REAL ObjectMapper, not mocked: CaseService.serialize() needs actual JSON output to verify
    // against (mocking it would just return null/empty and the test would prove nothing about
    // whether the outbox payload is real, valid JSON).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    // Manual `new CaseService(...)` instead of @InjectMocks: gives explicit control over which
    // dependency is a mock vs the real ObjectMapper above — @InjectMocks would try to mock every
    // constructor argument, which isn't what this test wants for objectMapper.
    private CaseService service() {
        return new CaseService(cases, outbox, objectMapper, audit);
    }

    @Test
    void createPublishesCaseCreatedAudit() {
        CaseService service = service();
        // Stubbing: tells the cases mock exactly how to respond to save(...) — returns whatever
        // entity was passed in, mimicking a real repository without touching a database.
        when(cases.save(any(CaseEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.create(new CaseCreateRequest(
                "Project Phoenix", "desc", MatterType.INVESTIGATION, "investigator@demo"));

        assertThat(response.status()).isEqualTo(CaseStatus.DRAFT);

        // ArgumentCaptor + verify(): captures the actual AuditEventPayload CaseService built and
        // passed to audit.publish(...), so the test can assert on its fields — verify() alone
        // would only prove publish() was called, not that the payload content is correct.
        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq(response.id().toString()));
        assertThat(captor.getValue().action()).isEqualTo("CASE_CREATED");
        assertThat(captor.getValue().targetType()).isEqualTo("CASE");
        assertThat(captor.getValue().caseId()).isEqualTo(response.id().toString());
    }

    @Test
    void transitionPublishesCaseTransitionedAuditWithBeforeAfter() {
        CaseService service = service();
        UUID caseId = UUID.randomUUID();
        CaseEntity entity = new CaseEntity();
        entity.id = caseId;
        entity.name = "Project Phoenix";
        entity.matterType = MatterType.INVESTIGATION;
        entity.owner = "investigator@demo";
        entity.status = CaseStatus.DRAFT;
        entity.createdAt = java.time.OffsetDateTime.now();
        entity.updatedAt = entity.createdAt;
        when(cases.findById(caseId)).thenReturn(Optional.of(entity));

        service.transition(caseId, CaseStatus.ACTIVE);

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq(caseId.toString()));
        assertThat(captor.getValue().action()).isEqualTo("CASE_TRANSITIONED");
        assertThat(captor.getValue().before()).contains("DRAFT");
        assertThat(captor.getValue().after()).contains("ACTIVE");
    }

    @Test
    void closingCaseWritesOutboxRowAndPublishesAudit() {
        CaseService service = service();
        UUID caseId = UUID.randomUUID();
        CaseEntity entity = new CaseEntity();
        entity.id = caseId;
        entity.name = "Project Phoenix";
        entity.matterType = MatterType.INVESTIGATION;
        entity.owner = "investigator@demo";
        // CLOSED is only reachable from UNDER_REVIEW (ACTIVE -> CLOSED is illegal).
        entity.status = CaseStatus.UNDER_REVIEW;
        entity.createdAt = java.time.OffsetDateTime.now();
        entity.updatedAt = entity.createdAt;
        when(cases.findById(caseId)).thenReturn(Optional.of(entity));

        service.transition(caseId, CaseStatus.CLOSED);

        verify(outbox).save(any(OutboxEntity.class));
        verify(audit).publish(any(AuditEventPayload.class), eq(caseId.toString()));
    }

    @Test
    void getUnknownCaseThrowsNotFound() {
        CaseService service = service();
        UUID caseId = UUID.randomUUID();
        when(cases.findById(caseId)).thenReturn(Optional.empty());

        // Exception testing + verifyNoInteractions: proves both that the failure path throws the
        // right custom exception AND that a failed lookup never touches the audit publisher —
        // an edge case a happy-path-only test suite would miss.
        assertThatThrownBy(() -> service.get(caseId)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void updateAppliesPartialChangesAndPublishesCaseUpdatedAudit() {
        CaseService service = service();
        UUID caseId = UUID.randomUUID();
        CaseEntity entity = new CaseEntity();
        entity.id = caseId;
        entity.name = "Old Name";
        entity.description = "old desc";
        entity.matterType = MatterType.INVESTIGATION;
        entity.owner = "old-owner";
        entity.status = CaseStatus.ACTIVE;
        entity.createdAt = java.time.OffsetDateTime.now();
        entity.updatedAt = entity.createdAt;
        when(cases.findById(caseId)).thenReturn(Optional.of(entity));
        when(cases.save(any(CaseEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.update(caseId, new CaseUpdateRequest(
                "New Name", null, MatterType.LITIGATION, null));

        assertThat(response.name()).isEqualTo("New Name");
        assertThat(response.description()).isEqualTo("old desc"); // unchanged
        assertThat(response.matterType()).isEqualTo(MatterType.LITIGATION);
        assertThat(response.owner()).isEqualTo("old-owner"); // unchanged

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq(caseId.toString()));
        assertThat(captor.getValue().action()).isEqualTo("CASE_UPDATED");
        assertThat(captor.getValue().targetType()).isEqualTo("CASE");
        assertThat(captor.getValue().before()).contains("Old Name");
        assertThat(captor.getValue().after()).contains("New Name");
        assertThat(captor.getValue().after()).contains("LITIGATION");
    }

    @Test
    void updateBlankNameIsTreatedAsLeaveUnchanged() {
        CaseService service = service();
        UUID caseId = UUID.randomUUID();
        CaseEntity entity = new CaseEntity();
        entity.id = caseId;
        entity.name = "Keep Me";
        entity.matterType = MatterType.INVESTIGATION;
        entity.owner = "owner";
        entity.status = CaseStatus.DRAFT;
        entity.createdAt = java.time.OffsetDateTime.now();
        entity.updatedAt = entity.createdAt;
        when(cases.findById(caseId)).thenReturn(Optional.of(entity));
        when(cases.save(any(CaseEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.update(caseId, new CaseUpdateRequest("  ", null, null, null));

        assertThat(response.name()).isEqualTo("Keep Me");
    }

    @Test
    void updateClosedCaseThrowsConflict() {
        CaseService service = service();
        UUID caseId = UUID.randomUUID();
        CaseEntity entity = new CaseEntity();
        entity.id = caseId;
        entity.name = "Closed";
        entity.matterType = MatterType.INVESTIGATION;
        entity.owner = "owner";
        entity.status = CaseStatus.CLOSED;
        entity.createdAt = java.time.OffsetDateTime.now();
        entity.updatedAt = entity.createdAt;
        when(cases.findById(caseId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(caseId,
                new CaseUpdateRequest("New", null, null, null)))
                .isInstanceOf(com.smarsh.discoveryhub.common.ConflictException.class)
                .satisfies(ex -> assertThat(((com.smarsh.discoveryhub.common.ConflictException) ex).code())
                        .isEqualTo("CASE_CLOSED"));
        verify(cases, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void updateUnknownCaseThrowsNotFound() {
        CaseService service = service();
        UUID caseId = UUID.randomUUID();
        when(cases.findById(caseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(caseId,
                new CaseUpdateRequest("New", null, null, null)))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(audit);
    }
}
