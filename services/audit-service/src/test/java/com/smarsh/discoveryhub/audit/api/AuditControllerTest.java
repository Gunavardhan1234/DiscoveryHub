package com.smarsh.discoveryhub.audit.api;

import com.smarsh.discoveryhub.audit.domain.model.AuditEntry;
import com.smarsh.discoveryhub.audit.domain.model.AuditQuery;
import com.smarsh.discoveryhub.audit.domain.port.AuditStorePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The audit read API is paged (FR-7.4), so the UI needs the total match count
 * to render "showing 26–50 of 100" and a page count. The body stays a bare
 * array (contract-stable); the total rides in {@code X-Total-Count}.
 */
class AuditControllerTest {

    private final AuditStorePort store = mock(AuditStorePort.class);
    private final AuditController controller = new AuditController(store);

    @Test
    void reportsTheUnpagedTotalInTheHeaderAlongsideThePageBody() {
        when(store.find(any(AuditQuery.class))).thenReturn(List.of(entry("evt-1")));
        when(store.count(any(AuditQuery.class))).thenReturn(100L);

        ResponseEntity<List<AuditEntry>> response =
                controller.find(null, null, null, null, null, null, 1, 25);

        assertThat(response.getHeaders().getFirst(AuditController.TOTAL_COUNT_HEADER)).isEqualTo("100");
        assertThat(response.getBody()).extracting(AuditEntry::eventId).containsExactly("evt-1");
    }

    @Test
    void passesPageSizeAndEveryFilterThroughToTheStore() {
        when(store.find(any(AuditQuery.class))).thenReturn(List.of());
        when(store.count(any(AuditQuery.class))).thenReturn(0L);

        controller.find(" case-42 ", "alice", "HOLD_PLACED", "HOLD",
                "2026-09-08T00:00:00Z", "2026-09-09T00:00:00Z", 2, 50);

        ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass(AuditQuery.class);
        verify(store).find(captor.capture());
        AuditQuery query = captor.getValue();
        assertThat(query.caseId()).isEqualTo("case-42");
        assertThat(query.actor()).isEqualTo("alice");
        assertThat(query.effectivePage()).isEqualTo(2);
        assertThat(query.effectiveSize()).isEqualTo(50);
        assertThat(query.from()).isEqualTo(Instant.parse("2026-09-08T00:00:00Z"));
    }

    @Test
    void rejectsAMalformedTimeWindow() {
        assertThatThrownBy(() -> controller.find(null, null, null, null, "yesterday", null, 0, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }

    private static AuditEntry entry(String eventId) {
        return new AuditEntry(eventId, Instant.parse("2026-09-07T16:00:00Z"), "alice", "CASE_CREATED",
                "Case", "c-1", "case-42", null, null, "corr-1", AuditEntry.GENESIS, "hash-1");
    }
}
