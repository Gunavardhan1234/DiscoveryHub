package com.smarsh.discoveryhub.search.domain.service;

import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import com.smarsh.discoveryhub.search.domain.model.SearchResult;
import com.smarsh.discoveryhub.search.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.search.domain.port.ReindexPort;
import com.smarsh.discoveryhub.search.domain.port.SearchPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchService#reindex()} — the ES-rebuild orchestration
 * that drops + recreates the index, then replays {@code comm.stored.v1} from
 * offset 0 (Day-3 M1: "Kafka offset rewind for ES rebuild"). Proves the order
 * of operations and that the replayed count is returned without needing Kafka.
 * Also covers {@link SearchService#search(QuerySpec)}'s {@code SEARCH_EXECUTED}
 * audit emission (FR-7.1).
 */
class SearchServiceTest {

    private final SearchPort searchPort = mock(SearchPort.class);
    private final ReindexPort reindexPort = mock(ReindexPort.class);
    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);
    private final SearchService service = new SearchService(searchPort, reindexPort, audit);

    @Test
    void reindexDropsRecreatesThenReplaysFromBeginning() {
        when(reindexPort.replayFromBeginning()).thenReturn(42L);

        long rebuilt = service.reindex();

        assertThat(rebuilt).isEqualTo(42L);
        // The index must be dropped + recreated BEFORE the replay begins so the
        // replayed events populate a clean index rather than merging into stale data.
        var inOrder = org.mockito.Mockito.inOrder(searchPort, reindexPort);
        inOrder.verify(searchPort).dropAndRecreateIndex();
        inOrder.verify(reindexPort).replayFromBeginning();
    }

    @Test
    void countDelegatesToPort() {
        when(searchPort.count(org.mockito.ArgumentMatchers.any(QuerySpec.class))).thenReturn(99L);

        assertThat(service.count(QuerySpec.builder().build())).isEqualTo(99L);
        verifyNoMoreInteractions(reindexPort);
    }

    @Test
    void searchPublishesSearchExecutedAuditKeyedByCaseId() {
        QuerySpec spec = QuerySpec.builder().q("wire transfer").caseId("case-42").build();
        when(searchPort.search(spec)).thenReturn(new SearchResult(3, 0, 25, List.of()));

        SearchResult result = service.search(spec);

        assertThat(result.total()).isEqualTo(3);
        var captor = org.mockito.ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq("case-42"));
        assertThat(captor.getValue().action()).isEqualTo("SEARCH_EXECUTED");
        assertThat(captor.getValue().caseId()).isEqualTo("case-42");
        assertThat(captor.getValue().after()).contains("\"resultCount\":3");
    }

    @Test
    void searchWithoutCaseIdStillPublishesAuditWithNullCaseId() {
        QuerySpec spec = QuerySpec.builder().q("hello").build();
        when(searchPort.search(spec)).thenReturn(SearchResult.empty(spec));

        service.search(spec);

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), any());
        assertThat(captor.getValue().caseId()).isNull();
    }
}
