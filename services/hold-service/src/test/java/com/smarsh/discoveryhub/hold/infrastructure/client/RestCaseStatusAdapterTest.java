package com.smarsh.discoveryhub.hold.infrastructure.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RestCaseStatusAdapter} must fail open (allow the hold) on any
 * transport error, per its own Javadoc contract — a case-service hiccup must
 * never block hold placement.
 */
class RestCaseStatusAdapterTest {

    @Test
    void isClosedFailsOpenWhenCaseServiceIsUnreachable() {
        // Port 1 is reserved/unassigned, so this deterministically fails fast.
        RestCaseStatusAdapter adapter = new RestCaseStatusAdapter("http://localhost:1");

        assertThat(adapter.isClosed(UUID.randomUUID())).isFalse();
    }
}
