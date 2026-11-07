package com.smarsh.discoveryhub.export.infrastructure.http;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Failures here must propagate (not fail-open) — a missing evidence list
 * means the export has nothing to package, which must fail the job rather
 * than silently produce an empty package.
 */
class CaseEvidenceHttpAdapterTest {

    @Test
    void evidenceMessageIdsPropagatesTransportFailuresWhenCaseServiceIsUnreachable() {
        CaseEvidenceHttpAdapter adapter = new CaseEvidenceHttpAdapter("http://localhost:1");

        assertThatThrownBy(() -> adapter.evidenceMessageIds("case-1"))
                .isInstanceOf(ResourceAccessException.class);
    }
}
