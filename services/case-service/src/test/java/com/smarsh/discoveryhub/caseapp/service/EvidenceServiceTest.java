package com.smarsh.discoveryhub.caseapp.service;

import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceAddRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceBulkAddRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.EvidenceBulkAddResponse;
import com.smarsh.discoveryhub.caseapp.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseRepository;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.EvidenceItemEntity;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.EvidenceRepository;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvidenceServiceTest {

    @Mock CaseRepository cases;
    @Mock EvidenceRepository evidence;
    @Mock AuditEventPublisher audit;
    @InjectMocks EvidenceService evidenceService;

    @Test
    void addEvidencePersistsNewItem() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        when(evidence.existsByCaseIdAndMessageId(caseId, "msg-1")).thenReturn(false);

        var response = evidenceService.addEvidence(caseId,
                new EvidenceAddRequest("msg-1", "MANUAL"), "investigator@demo");

        assertThat(response.messageId()).isEqualTo("msg-1");
        assertThat(response.source()).isEqualTo("MANUAL");
        verify(evidence).save(any(EvidenceItemEntity.class));

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq(caseId.toString()));
        assertThat(captor.getValue().action()).isEqualTo("EVIDENCE_ADDED");
        assertThat(captor.getValue().targetId()).isEqualTo("msg-1");
    }

    @Test
    void addEvidenceIsIdempotentForDuplicateMessage() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        when(evidence.existsByCaseIdAndMessageId(caseId, "msg-1")).thenReturn(true);

        EvidenceItemEntity existing = new EvidenceItemEntity();
        existing.id = UUID.randomUUID();
        existing.caseId = caseId;
        existing.messageId = "msg-1";
        existing.source = "MANUAL";
        when(evidence.findByCaseIdOrderByAddedAtAsc(caseId)).thenReturn(List.of(existing));

        var response = evidenceService.addEvidence(caseId,
                new EvidenceAddRequest("msg-1", "MANUAL"), "investigator@demo");

        assertThat(response.messageId()).isEqualTo("msg-1");
        verify(evidence, never()).save(any());
        verify(audit, never()).publish(any(), any());
    }

    @Test
    void bulkAddSkipsDuplicatesAndCountsCorrectly() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        when(evidence.existsByCaseIdAndMessageId(caseId, "msg-1")).thenReturn(true);
        when(evidence.existsByCaseIdAndMessageId(caseId, "msg-2")).thenReturn(false);
        when(evidence.existsByCaseIdAndMessageId(caseId, "msg-3")).thenReturn(false);

        EvidenceBulkAddResponse response = evidenceService.addEvidenceBulk(caseId,
                new EvidenceBulkAddRequest(List.of("msg-1", "msg-2", "msg-3"), "SEARCH"),
                "investigator@demo");

        assertThat(response.added()).isEqualTo(2);
        assertThat(response.skipped()).isEqualTo(1);
        assertThat(response.addedMessageIds()).containsExactly("msg-2", "msg-3");
        verify(evidence, times(2)).save(any(EvidenceItemEntity.class));

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq(caseId.toString()));
        assertThat(captor.getValue().action()).isEqualTo("EVIDENCE_ADDED");
        assertThat(captor.getValue().after()).contains("\"added\":2");
    }

    @Test
    void addEvidenceToNonexistentCaseThrowsNotFound() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(false);

        assertThatThrownBy(() -> evidenceService.addEvidence(caseId,
                new EvidenceAddRequest("msg-1", "MANUAL"), "investigator@demo"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void removeEvidenceDeletesItemAndPublishesRemovalAudit() {
        UUID caseId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        EvidenceItemEntity item = new EvidenceItemEntity();
        item.id = evidenceId;
        item.caseId = caseId;
        item.messageId = "msg-9";
        item.source = "MANUAL";
        when(evidence.findById(evidenceId)).thenReturn(Optional.of(item));

        evidenceService.removeEvidence(caseId, evidenceId, "investigator@demo");

        verify(evidence).delete(item);
        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq(caseId.toString()));
        assertThat(captor.getValue().action()).isEqualTo("EVIDENCE_REMOVED");
        assertThat(captor.getValue().targetType()).isEqualTo("EVIDENCE");
        assertThat(captor.getValue().targetId()).isEqualTo("msg-9");
        assertThat(captor.getValue().before()).contains("msg-9");
        assertThat(captor.getValue().after()).isNull();
    }

    @Test
    void removeEvidenceForUnknownItemThrowsNotFound() {
        UUID caseId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        when(evidence.findById(evidenceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> evidenceService.removeEvidence(caseId, evidenceId, "investigator@demo"))
                .isInstanceOf(NotFoundException.class);
        verify(evidence, never()).delete(any());
        verify(audit, never()).publish(any(), any());
    }

    @Test
    void removeEvidenceFromOtherCaseThrowsNotFound() {
        UUID caseId = UUID.randomUUID();
        UUID otherCaseId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);
        EvidenceItemEntity item = new EvidenceItemEntity();
        item.id = evidenceId;
        item.caseId = otherCaseId; // belongs to a different case
        item.messageId = "msg-x";
        when(evidence.findById(evidenceId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> evidenceService.removeEvidence(caseId, evidenceId, "investigator@demo"))
                .isInstanceOf(NotFoundException.class);
        verify(evidence, never()).delete(any());
    }
}
