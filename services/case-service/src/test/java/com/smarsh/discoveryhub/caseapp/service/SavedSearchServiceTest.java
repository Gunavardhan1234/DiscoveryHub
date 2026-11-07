package com.smarsh.discoveryhub.caseapp.service;

import com.smarsh.discoveryhub.caseapp.api.dto.SavedSearchRequest;
import com.smarsh.discoveryhub.caseapp.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseRepository;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.SavedSearchEntity;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.SavedSearchRepository;
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
class SavedSearchServiceTest {

    @Mock CaseRepository cases;
    @Mock SavedSearchRepository savedSearches;
    @Mock AuditEventPublisher audit;
    @InjectMocks SavedSearchService savedSearchService;

    @Test
    void createPersistsSavedSearch() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(true);

        var response = savedSearchService.create(caseId,
                new SavedSearchRequest("wire transfers", "{\"q\":\"wire transfer\"}"));

        assertThat(response.name()).isEqualTo("wire transfers");
        assertThat(response.spec()).isEqualTo("{\"q\":\"wire transfer\"}");
        verify(savedSearches).save(any(SavedSearchEntity.class));

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq(caseId.toString()));
        assertThat(captor.getValue().action()).isEqualTo("SAVED_SEARCH_CREATED");
    }

    @Test
    void createOnNonexistentCaseThrowsNotFound() {
        UUID caseId = UUID.randomUUID();
        when(cases.existsById(caseId)).thenReturn(false);

        assertThatThrownBy(() -> savedSearchService.create(caseId,
                new SavedSearchRequest("test", "{}")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getThrowsNotFoundForMissingId() {
        UUID id = UUID.randomUUID();
        when(savedSearches.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> savedSearchService.get(id))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRemovesExistingSearch() {
        UUID id = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        SavedSearchEntity entity = savedSearch(id, caseId, "wire transfers");
        when(savedSearches.findById(id)).thenReturn(Optional.of(entity));

        savedSearchService.delete(caseId, id);

        verify(savedSearches).delete(entity);
    }

    /** A search id that exists but belongs to a different case must 404, not
     *  leak the fact that the id exists elsewhere (mirrors removeEvidence). */
    @Test
    void deleteOnMismatchedCaseThrowsNotFoundWithoutDeleting() {
        UUID id = UUID.randomUUID();
        SavedSearchEntity entity = savedSearch(id, UUID.randomUUID(), "wire transfers");
        when(savedSearches.findById(id)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> savedSearchService.delete(UUID.randomUUID(), id))
                .isInstanceOf(NotFoundException.class);
        verify(savedSearches, never()).delete(any());
        verify(audit, never()).publish(any(), any());
    }

    /**
     * FR-7.1 {@code SAVED_SEARCH_DELETED}: a successful delete must leave an
     * audit trail, mirroring {@code EVIDENCE_REMOVED} — the deleted search
     * lands in {@code before} and {@code after} is null since nothing remains.
     */
    @Test
    void deletePublishesRemovalAudit() {
        UUID id = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        when(savedSearches.findById(id)).thenReturn(Optional.of(savedSearch(id, caseId, "wire transfers")));

        savedSearchService.delete(caseId, id);

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq(caseId.toString()));
        AuditEventPayload event = captor.getValue();
        assertThat(event.action()).isEqualTo("SAVED_SEARCH_DELETED");
        assertThat(event.targetType()).isEqualTo("SAVED_SEARCH");
        assertThat(event.targetId()).isEqualTo(id.toString());
        assertThat(event.caseId()).isEqualTo(caseId.toString());
        assertThat(event.before()).contains("wire transfers");
        assertThat(event.after()).isNull();
        assertThat(event.actor()).isNotBlank();
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void deleteThrowsNotFoundAndPublishesNoAuditForMissingId() {
        UUID id = UUID.randomUUID();
        when(savedSearches.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> savedSearchService.delete(UUID.randomUUID(), id))
                .isInstanceOf(NotFoundException.class);
        verify(savedSearches, never()).delete(any());
        verify(audit, never()).publish(any(), any());
    }

    /** A failed delete must not leave an audit entry claiming the search was deleted. */
    @Test
    void deleteDoesNotPublishAuditWhenDeleteFails() {
        UUID id = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        SavedSearchEntity entity = savedSearch(id, caseId, "wire transfers");
        when(savedSearches.findById(id)).thenReturn(Optional.of(entity));
        doThrow(new RuntimeException("db down")).when(savedSearches).delete(entity);

        assertThatThrownBy(() -> savedSearchService.delete(caseId, id))
                .isInstanceOf(RuntimeException.class);
        verify(audit, never()).publish(any(), any());
    }

    /** A broken audit publisher must not fail an otherwise successful delete. */
    @Test
    void deleteSucceedsWhenAuditPublisherFails() {
        UUID id = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        SavedSearchEntity entity = savedSearch(id, caseId, "wire transfers");
        when(savedSearches.findById(id)).thenReturn(Optional.of(entity));
        doThrow(new RuntimeException("kafka down")).when(audit).publish(any(), any());

        savedSearchService.delete(caseId, id);

        verify(savedSearches).delete(entity);
    }

    private static SavedSearchEntity savedSearch(UUID id, UUID caseId, String name) {
        SavedSearchEntity entity = new SavedSearchEntity();
        entity.id = id;
        entity.caseId = caseId;
        entity.name = name;
        return entity;
    }
}
