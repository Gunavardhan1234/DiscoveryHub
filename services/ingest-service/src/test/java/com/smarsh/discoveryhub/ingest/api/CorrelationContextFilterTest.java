package com.smarsh.discoveryhub.ingest.api;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationContextFilterTest {

    private final CorrelationContextFilter filter = new CorrelationContextFilter();

    @AfterEach
    void cleanThreadLocals() {
        CorrelationId.clear();
        Actor.clear();
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    @Test
    void seedsCorrelationIdAndActorFromHeadersAndClearsAfterwards() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-Correlation-Id", "corr-42");
        request.addHeader("X-Actor", " investigator@fict.example ");
        AtomicReference<String> inChainCorrelation = new AtomicReference<>();
        AtomicReference<String> inChainActor = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            inChainCorrelation.set(CorrelationId.current());
            inChainActor.set(Actor.current());
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(inChainCorrelation.get()).isEqualTo("corr-42");
        assertThat(inChainActor.get()).isEqualTo(" investigator@fict.example ");
        // Thread-locals are cleared so they never leak into pooled threads.
        assertThat(CorrelationId.current()).isNotEqualTo("corr-42");
        assertThat(Actor.current()).isEqualTo("system");
    }

    @Test
    void correlationHeaderAloneSeedsBoth() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-Correlation-Id", "corr-43");
        AtomicReference<String> inChainCorrelation = new AtomicReference<>();
        AtomicReference<String> inChainActor = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            inChainCorrelation.set(CorrelationId.current());
            inChainActor.set(Actor.current());
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(inChainCorrelation.get()).isEqualTo("corr-43");
        // Actor.set(null) removes the value, so the default "system" applies.
        assertThat(inChainActor.get()).isEqualTo("system");
    }

    @Test
    void actorHeaderAloneSeedsActor() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-Actor", "auditor");
        AtomicReference<String> inChainActor = new AtomicReference<>();
        FilterChain chain = (req, res) -> inChainActor.set(Actor.current());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(inChainActor.get()).isEqualTo("auditor");
        assertThat(Actor.current()).isEqualTo("system");
    }

    @Test
    void noHeadersLeavesDefaultsUntouched() throws Exception {
        AtomicReference<String> inChainCorrelation = new AtomicReference<>();
        AtomicReference<String> inChainActor = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            inChainCorrelation.set(CorrelationId.current());
            inChainActor.set(Actor.current());
        };

        filter.doFilter(request(), new MockHttpServletResponse(), chain);

        // Not seeded: the correlation id is a fresh UUID and actor stays default.
        assertThat(inChainCorrelation.get()).doesNotStartWith("corr");
        assertThat(inChainActor.get()).isEqualTo("system");
    }
}
