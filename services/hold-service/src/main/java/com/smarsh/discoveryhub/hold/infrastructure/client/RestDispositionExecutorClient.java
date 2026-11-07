package com.smarsh.discoveryhub.hold.infrastructure.client;

import com.smarsh.discoveryhub.hold.domain.disposition.DispositionExecutorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * HTTP adapter for {@link DispositionExecutorClient}: calls archive-service's
 * {@code POST /internal/disposition/run} directly (service-to-service, not via
 * the gateway — same pattern as the archive hold-applier calling search's
 * scope endpoint). The base URL defaults per profile
 * ({@code http://localhost:8082} local, {@code http://archive-service:8082}
 * docker), so no compose or gateway change is needed.
 */
@Component
public class RestDispositionExecutorClient implements DispositionExecutorClient {

    private final RestClient restClient;

    public RestDispositionExecutorClient(
            RestClient.Builder builder,
            @Value("${discoveryhub.archive.base-url:http://localhost:8082}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public ExecutorResult run(List<TypeCutoff> cutoffs) {
        return restClient.post()
                .uri("/internal/disposition/run")
                .body(Map.of("cutoffs", cutoffs))
                .retrieve()
                .body(ExecutorResult.class);
    }
}
