package com.smarsh.discoveryhub.export.infrastructure.http;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unlike the fail-open dashboard/case-status adapters, a hold-scope lookup
 * failure here must propagate — a missing scope must fail the export job
 * (Kafka retry/DLT), not silently package zero messages.
 */
class SearchHoldScopeHttpAdapterTest {

    @Test
    void holdMessageIdsPropagatesTransportFailuresWhenSearchServiceIsUnreachable() {
        SearchHoldScopeHttpAdapter adapter = new SearchHoldScopeHttpAdapter("http://localhost:1");

        assertThatThrownBy(() -> adapter.holdMessageIds("hold-1"))
                .isInstanceOf(ResourceAccessException.class);
    }
}
