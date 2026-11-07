package com.smarsh.discoveryhub.hold.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link HoldService} placement (FR-4.1): a POST persists a
 * PENDING hold row and a {@code hold.commands.v1} outbox row in the same
 * transaction, and returns a 202-shaped response (status PENDING, holdId set).
 */
class HoldServiceTest {

    private final HoldRepository holds = mock(HoldRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);
    private final CaseStatusPort caseStatus = mock(CaseStatusPort.class);
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final HoldService service = new HoldService(holds, outbox, mapper, audit, caseStatus);

    @Test
    void placePersistsPendingHoldAndOutboxCommand() throws Exception {
        // the repository just echoes the saved entity (with the generated id)
        when(holds.save(org.mockito.ArgumentMatchers.any(HoldEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(outbox.save(org.mockito.ArgumentMatchers.any(OutboxEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T00:00:00Z");
        HoldResponse response = service.place(new HoldRequest(
                "11111111-1111-1111-1111-111111111111",
                List.of("c-0001", "c-0002"), from, to, "wire", "alice"));

        assertThat(response.status()).isEqualTo(HoldStatus.PENDING.name());
        assertThat(response.holdId()).isNotBlank();
        assertThat(response.custodians()).containsExactly("c-0001", "c-0002");
        assertThat(response.from()).isEqualTo(from);
        assertThat(response.to()).isEqualTo(to);
        assertThat(response.terms()).isEqualTo("wire");
        assertThat(response.heldCount()).isZero();
        assertThat(response.progressPct()).isZero();

        ArgumentCaptor<HoldEntity> holdCaptor = ArgumentCaptor.forClass(HoldEntity.class);
        verify(holds).save(holdCaptor.capture());
        HoldEntity saved = holdCaptor.getValue();
        assertThat(saved.status).isEqualTo(HoldStatus.PENDING);
        assertThat(saved.caseId.toString()).isEqualTo("11111111-1111-1111-1111-111111111111");

        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outbox).save(outboxCaptor.capture());
        OutboxEntity outboxRow = outboxCaptor.getValue();
        assertThat(outboxRow.topic).isEqualTo(Topics.HOLD_COMMANDS_V1);
        assertThat(outboxRow.key).isEqualTo(saved.id.toString());
        assertThat(outboxRow.publishedAt).isNull();

        HoldCommand command = mapper.readValue(outboxRow.payload, HoldCommand.class);
        assertThat(command.holdId()).isEqualTo(saved.id.toString());
        assertThat(command.action()).isEqualTo(HoldCommand.PLACE);
        assertThat(command.custodians()).containsExactly("c-0001", "c-0002");
        assertThat(command.placedBy()).isEqualTo("alice");

        ArgumentCaptor<AuditEventPayload> auditCaptor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(auditCaptor.capture(), org.mockito.ArgumentMatchers.eq(saved.id.toString()));
        AuditEventPayload auditEvent = auditCaptor.getValue();
        assertThat(auditEvent.action()).isEqualTo("HOLD_PLACED");
        assertThat(auditEvent.targetType()).isEqualTo("HOLD");
        assertThat(auditEvent.targetId()).isEqualTo(saved.id.toString());
        assertThat(auditEvent.caseId()).isEqualTo(saved.caseId.toString());
        assertThat(auditEvent.actor()).isEqualTo("alice");
    }

    @Test
    void placeWithBlankTermsStoresNullTerms() {
        when(holds.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        when(outbox.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        HoldResponse response = service.place(new HoldRequest(
                "22222222-2222-2222-2222-222222222222",
                List.of("c-0003"), null, null, "  ", "bob"));

        assertThat(response.terms()).isNull();
        assertThat(response.custodians()).containsExactly("c-0003");
    }

    @Test
    void placeRejectsHoldOnClosedCaseWithoutWritingAnything() {
        // FR-2.5: a closed case is read-only. The case-status guard runs before
        // any hold/outbox row is written, so neither repository is touched.
        UUID caseId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(caseStatus.isClosed(caseId)).thenReturn(true);

        assertThatThrownBy(() -> service.place(new HoldRequest(
                "11111111-1111-1111-1111-111111111111",
                List.of("c-0001"), null, null, null, "alice")))
                .isInstanceOf(com.smarsh.discoveryhub.common.ConflictException.class)
                .satisfies(ex -> assertThat(((com.smarsh.discoveryhub.common.ConflictException) ex).code())
                        .isEqualTo("CASE_CLOSED"));

        verify(holds, never()).save(any());
        verify(outbox, never()).save(any());
        verify(audit, never()).publish(any(), any());
    }

    @Test
    void placeOnNonUuidCaseIdThrowsIllegalArgument() {
        assertThatThrownBy(() -> service.place(new HoldRequest(
                "not-a-uuid", List.of("c-0001"), null, null, null, "alice")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void releaseFlipsStatusToReleasingAndWritesReleaseOutbox() throws Exception {
        HoldEntity hold = existingHold(HoldStatus.ACTIVE);
        when(holds.findById(hold.id)).thenReturn(Optional.of(hold));
        when(holds.save(any(HoldEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outbox.save(any(OutboxEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        HoldResponse response = service.release(hold.id);

        assertThat(response.status()).isEqualTo(HoldStatus.RELEASING.name());
        ArgumentCaptor<OutboxEntity> captor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outbox).save(captor.capture());
        assertThat(captor.getValue().topic).isEqualTo(Topics.HOLD_COMMANDS_V1);
        HoldCommand command = mapper.readValue(captor.getValue().payload, HoldCommand.class);
        assertThat(command.action()).isEqualTo(HoldCommand.RELEASE);
        assertThat(command.holdId()).isEqualTo(hold.id.toString());

        ArgumentCaptor<AuditEventPayload> auditCaptor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(auditCaptor.capture(), org.mockito.ArgumentMatchers.eq(hold.id.toString()));
        assertThat(auditCaptor.getValue().action()).isEqualTo("HOLD_RELEASED");
        assertThat(auditCaptor.getValue().targetType()).isEqualTo("HOLD");
    }

    @Test
    void releaseIsIdempotentForReleasedAndReleasingHolds() {
        for (HoldStatus terminal : List.of(HoldStatus.RELEASED, HoldStatus.RELEASING)) {
            HoldEntity hold = existingHold(terminal);
            when(holds.findById(hold.id)).thenReturn(Optional.of(hold));

            HoldResponse response = service.release(hold.id);

            assertThat(response.status()).isEqualTo(terminal.name());
        }
        verify(outbox, never()).save(any(OutboxEntity.class));
    }

    @Test
    void releaseAllForCaseReleasesEveryNonTerminalHold() throws Exception {
        UUID caseId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        HoldEntity pending = existingHold(HoldStatus.PENDING);
        HoldEntity active = existingHold(HoldStatus.ACTIVE);
        when(holds.findByCaseIdAndStatusIn(caseId,
                List.of(HoldStatus.PENDING, HoldStatus.ACTIVE, HoldStatus.FAILED)))
                .thenReturn(List.of(pending, active));
        when(holds.save(any(HoldEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outbox.save(any(OutboxEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        int released = service.releaseAllForCase(caseId);

        assertThat(released).isEqualTo(2);
        assertThat(pending.status).isEqualTo(HoldStatus.RELEASING);
        assertThat(active.status).isEqualTo(HoldStatus.RELEASING);
        ArgumentCaptor<OutboxEntity> captor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outbox, org.mockito.Mockito.times(2)).save(captor.capture());
        for (OutboxEntity row : captor.getAllValues()) {
            HoldCommand command = mapper.readValue(row.payload, HoldCommand.class);
            assertThat(command.action()).isEqualTo(HoldCommand.RELEASE);
        }
        verify(audit, org.mockito.Mockito.times(2))
                .publish(org.mockito.ArgumentMatchers.any(AuditEventPayload.class), any());
    }

    private static HoldEntity existingHold(HoldStatus status) {
        HoldEntity hold = new HoldEntity();
        hold.id = UUID.randomUUID();
        hold.caseId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        hold.status = status;
        hold.scopeCustodians = new String[] {"c-0001"};
        hold.heldCount = 5;
        hold.appliedCount = 5;
        hold.placedBy = "alice";
        hold.placedAt = OffsetDateTime.now(ZoneOffset.UTC);
        return hold;
    }
}
