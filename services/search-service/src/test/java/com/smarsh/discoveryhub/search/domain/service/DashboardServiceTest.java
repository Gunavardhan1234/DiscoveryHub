package com.smarsh.discoveryhub.search.domain.service;

import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import com.smarsh.discoveryhub.search.domain.port.RemoteCountsPort;
import com.smarsh.discoveryhub.search.domain.port.SearchPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardService}: the ES message count is owned here,
 * remote counts come from the {@link RemoteCountsPort}, and every failure
 * degrades to 0 so the dashboard never blanks (NFR-2).
 */
class DashboardServiceTest {

    private final SearchPort searchPort = mock(SearchPort.class);
    private final RemoteCountsPort remoteCounts = mock(RemoteCountsPort.class);
    private final DashboardService service = new DashboardService(searchPort, remoteCounts);

    @Test
    void aggregatesAllFourCounts() {
        when(searchPort.count(any(QuerySpec.class))).thenReturn(12_345L);
        when(remoteCounts.activeCases()).thenReturn(3L);
        when(remoteCounts.activeHolds()).thenReturn(7L);
        when(remoteCounts.exportsCompleted()).thenReturn(42L);

        DashboardService.DashboardCounts counts = service.counts();

        assertThat(counts.totalMessages()).isEqualTo(12_345L);
        assertThat(counts.activeCases()).isEqualTo(3L);
        assertThat(counts.activeHolds()).isEqualTo(7L);
        assertThat(counts.exportsCompleted()).isEqualTo(42L);
    }

    @Test
    void esFailureDegradesTotalMessagesToZero() {
        when(searchPort.count(any(QuerySpec.class))).thenThrow(new RuntimeException("es down"));
        when(remoteCounts.activeCases()).thenReturn(3L);
        when(remoteCounts.activeHolds()).thenReturn(7L);
        when(remoteCounts.exportsCompleted()).thenReturn(42L);

        DashboardService.DashboardCounts counts = service.counts();

        assertThat(counts.totalMessages()).isZero();
        assertThat(counts.activeCases()).isEqualTo(3L);
    }

    @Test
    void remoteServiceFailureDegradesOnlyThatCardToZero() {
        when(searchPort.count(any(QuerySpec.class))).thenReturn(100L);
        when(remoteCounts.activeCases()).thenThrow(new RuntimeException("case-service down"));
        when(remoteCounts.activeHolds()).thenReturn(2L);
        when(remoteCounts.exportsCompleted()).thenThrow(new RuntimeException("export-service down"));

        DashboardService.DashboardCounts counts = service.counts();

        assertThat(counts.totalMessages()).isEqualTo(100L);
        assertThat(counts.activeCases()).isZero();
        assertThat(counts.activeHolds()).isEqualTo(2L);
        assertThat(counts.exportsCompleted()).isZero();
    }
}
