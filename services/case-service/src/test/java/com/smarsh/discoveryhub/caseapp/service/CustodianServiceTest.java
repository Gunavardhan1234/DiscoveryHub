package com.smarsh.discoveryhub.caseapp.service;

import com.smarsh.discoveryhub.caseapp.api.dto.CustodianRequest;
import com.smarsh.discoveryhub.caseapp.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseCustodianEntity;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseRepository;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CustodianRepository;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustodianServiceTest {

    @Mock CaseRepository cases;
    @Mock CustodianRepository custodians;
    @Mock AuditEventPublisher audit;
    // @InjectMocks: unlike CaseServiceTest's manual `new CaseService(...)`, this lets Mockito
    // construct CustodianService itself and inject every @Mock field above by matching
    // constructor parameter types — appropriate here since every dependency is safely mockable
    // (no real collaborator like CaseServiceTest's ObjectMapper needs to be threaded through).
    @InjectMocks CustodianService custodianService;

    @Test
    void addCustodianPersistsNewCustodian() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        when(custodians.existsById(any())).thenReturn(false);

        var response = custodianService.addCustodian(caseId,
                new CustodianRequest("c-0007", "Ada Byron"));

        assertThat(response.custodianId()).isEqualTo("c-0007");
        assertThat(response.custodianName()).isEqualTo("Ada Byron");
        verify(custodians).save(any(CaseCustodianEntity.class));

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq(caseId.toString()));
        assertThat(captor.getValue().action()).isEqualTo("CUSTODIAN_ADDED");
        assertThat(captor.getValue().targetId()).isEqualTo("c-0007");
    }

    @Test
    void addDuplicateCustodianThrowsConflict() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        when(custodians.existsById(any())).thenReturn(true);

        assertThatThrownBy(() -> custodianService.addCustodian(caseId,
                new CustodianRequest("c-0007", "Ada Byron")))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).code()).isEqualTo("DUPLICATE_CUSTODIAN"));
        verify(custodians, never()).save(any());
    }

    @Test
    void addCustodianToNonexistentCaseThrowsNotFound() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(false);

        assertThatThrownBy(() -> custodianService.addCustodian(caseId,
                new CustodianRequest("c-0007", "Ada Byron")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void removeCustodianDeletesIfExists() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        when(custodians.findById(any())).thenReturn(Optional.of(custodian(caseId, "c-0007", "Ada Byron")));

        custodianService.removeCustodian(caseId, "c-0007");

        verify(custodians).deleteById(any(CaseCustodianEntity.CaseCustodianId.class));
    }

    /**
     * FR-7.1 {@code CUSTODIAN_REMOVED}: a successful detach must leave an audit
     * trail, mirroring {@code EVIDENCE_REMOVED} — the removed row lands in
     * {@code before} and {@code after} is null since nothing remains.
     */
    @Test
    void removeCustodianPublishesRemovalAudit() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        when(custodians.findById(any())).thenReturn(Optional.of(custodian(caseId, "c-0007", "Ada Byron")));

        custodianService.removeCustodian(caseId, "c-0007");

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq(caseId.toString()));
        AuditEventPayload event = captor.getValue();
        assertThat(event.action()).isEqualTo("CUSTODIAN_REMOVED");
        assertThat(event.targetType()).isEqualTo("CUSTODIAN");
        assertThat(event.targetId()).isEqualTo("c-0007");
        assertThat(event.caseId()).isEqualTo(caseId.toString());
        assertThat(event.before()).contains("c-0007").contains("Ada Byron");
        assertThat(event.after()).isNull();
        assertThat(event.actor()).isNotBlank();
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void removeNonexistentCustodianThrowsNotFound() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        when(custodians.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> custodianService.removeCustodian(caseId, "c-9999"))
                .isInstanceOf(NotFoundException.class);
        verify(custodians, never()).deleteById(any());
        verify(audit, never()).publish(any(), any());
    }

    /** A failed delete must not leave an audit entry claiming the custodian was removed. */
    // doThrow(...).when(mock).method(...): the Mockito idiom for stubbing a void method to throw —
    // when(mock.method()) doesn't compile for void methods, since there's no return value to chain
    // thenThrow() off of. Simulates the repository itself failing (e.g. a DB outage) to prove
    // CustodianService doesn't swallow that failure into a false-positive audit trail.
    @Test
    void removeCustodianDoesNotPublishAuditWhenDeleteFails() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        when(custodians.findById(any())).thenReturn(Optional.of(custodian(caseId, "c-0007", "Ada Byron")));
        doThrow(new RuntimeException("db down")).when(custodians).deleteById(any());

        assertThatThrownBy(() -> custodianService.removeCustodian(caseId, "c-0007"))
                .isInstanceOf(RuntimeException.class);
        verify(audit, never()).publish(any(), any());
    }

    /** A broken audit publisher must not fail an otherwise successful removal. */
    @Test
    void removeCustodianSucceedsWhenAuditPublisherFails() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        when(custodians.findById(any())).thenReturn(Optional.of(custodian(caseId, "c-0007", "Ada Byron")));
        doThrow(new RuntimeException("kafka down")).when(audit).publish(any(), any());

        custodianService.removeCustodian(caseId, "c-0007");

        verify(custodians).deleteById(any(CaseCustodianEntity.CaseCustodianId.class));
    }

    private static CaseCustodianEntity custodian(UUID caseId, String custodianId, String name) {
        CaseCustodianEntity entity = new CaseCustodianEntity();
        entity.id = new CaseCustodianEntity.CaseCustodianId(caseId, custodianId);
        entity.custodianName = name;
        return entity;
    }
}
