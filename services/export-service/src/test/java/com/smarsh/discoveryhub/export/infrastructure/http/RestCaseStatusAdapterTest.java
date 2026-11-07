package com.smarsh.discoveryhub.export.infrastructure.http;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RestCaseStatusAdapter} must fail open (allow the export) on any
 * transport error — a case-service hiccup must never block export requests.
 */
class RestCaseStatusAdapterTest {

    @Test
    void isClosedFailsOpenWhenCaseServiceIsUnreachable() {
        RestCaseStatusAdapter adapter = new RestCaseStatusAdapter("http://localhost:1");

        assertThat(adapter.isClosed(UUID.randomUUID())).isFalse();
    }
}
