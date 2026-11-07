package com.smarsh.discoveryhub.caseapp.infrastructure.http;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RestHoldSummaryAdapter} fails open (FR-4.4 + NFR-2): if
 * hold-service is unreachable, {@code heldCount} must return {@code 0}
 * rather than throwing, so a transient outage never breaks the Case Detail
 * screen.
 */
// No mocking here, deliberately: a real (unreachable) network call is the most faithful way to
// exercise the actual try/catch fail-open path — a mocked RestClient wouldn't prove the real
// java.net connection-refused exception is caught, only that the code compiles.
class RestHoldSummaryAdapterTest {

    @Test
    void returnsZeroWhenHoldServiceIsUnreachable() {
        // Port 1 is a reserved/unassigned port — nothing listens there, so
        // this deterministically fails fast with a connection error.
        RestHoldSummaryAdapter adapter = new RestHoldSummaryAdapter("http://localhost:1");

        assertThat(adapter.heldCount(UUID.randomUUID())).isZero();
    }
}
