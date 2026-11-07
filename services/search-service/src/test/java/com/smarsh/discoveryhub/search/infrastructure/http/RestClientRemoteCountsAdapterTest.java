package com.smarsh.discoveryhub.search.infrastructure.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the JSON-shape contracts in
 * {@link RestClientRemoteCountsAdapter}. The parse helpers are static so the
 * dashboard's "live counts" logic is verified without an HTTP server: each
 * owning service returns a different list shape (paged {@code PageResponse} for
 * cases, a bare JSON array for holds), and the adapter must derive the
 * <em>active</em> count (non-CLOSED cases, non-terminal holds) from it.
 */
class RestClientRemoteCountsAdapterTest {

    @Test
    void countActiveCasesFromPageContentExcludingClosed() throws Exception {
        String json = """
                {"content":[
                    {"id":"c1","status":"DRAFT"},
                    {"id":"c2","status":"ACTIVE"},
                    {"id":"c3","status":"UNDER_REVIEW"},
                    {"id":"c4","status":"CLOSED"}
                ],"totalElements":4,"totalPages":1,"page":0,"size":10000}""";

        assertThat(RestClientRemoteCountsAdapter.countActiveCases(json)).isEqualTo(3L);
    }

    @Test
    void countActiveCasesFallsBackToTotalElementsWhenNoContent() throws Exception {
        String json = "{\"totalElements\":7}";
        assertThat(RestClientRemoteCountsAdapter.countActiveCases(json)).isEqualTo(7L);
    }

    @Test
    void countActiveCasesZeroOnEmptyContent() throws Exception {
        assertThat(RestClientRemoteCountsAdapter.countActiveCases("{\"content\":[],\"totalElements\":0}")).isZero();
    }

    @Test
    void countActiveHoldsFromBareArrayExcludingTerminal() throws Exception {
        String json = """
                [
                    {"holdId":"h1","status":"PENDING"},
                    {"holdId":"h2","status":"ACTIVE"},
                    {"holdId":"h3","status":"RELEASING"},
                    {"holdId":"h4","status":"RELEASED"},
                    {"holdId":"h5","status":"FAILED"}
                ]""";

        assertThat(RestClientRemoteCountsAdapter.countActiveHolds(json)).isEqualTo(3L);
    }

    @Test
    void countActiveHoldsFallsBackToTotalWhenNotArray() throws Exception {
        assertThat(RestClientRemoteCountsAdapter.countActiveHolds("{\"total\":5}")).isEqualTo(5L);
    }

    @Test
    void countActiveHoldsZeroOnEmptyArray() throws Exception {
        assertThat(RestClientRemoteCountsAdapter.countActiveHolds("[]")).isZero();
    }

    // --- instance methods: each degrades to 0 (never throws) when its
    // owning service is unreachable, so one down service blanks only its own
    // dashboard card (NFR-2). Port 1 is reserved/unassigned, so this
    // deterministically fails fast without a real HTTP server. ---

    private RestClientRemoteCountsAdapter unreachableAdapter() {
        return new RestClientRemoteCountsAdapter("http://localhost:1", "http://localhost:1", "http://localhost:1");
    }

    @Test
    void activeCasesDegradesToZeroWhenCaseServiceIsUnreachable() {
        assertThat(unreachableAdapter().activeCases()).isZero();
    }

    @Test
    void activeHoldsDegradesToZeroWhenHoldServiceIsUnreachable() {
        assertThat(unreachableAdapter().activeHolds()).isZero();
    }

    @Test
    void exportsCompletedDegradesToZeroWhenExportServiceIsUnreachable() {
        assertThat(unreachableAdapter().exportsCompleted()).isZero();
    }
}
