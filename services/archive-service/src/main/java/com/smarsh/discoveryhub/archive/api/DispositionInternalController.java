package com.smarsh.discoveryhub.archive.api;

import com.smarsh.discoveryhub.archive.application.DispositionExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Internal service-to-service endpoint for the disposition pass (FR-5). Called
 * by hold-service's orchestrator (the {@code @Scheduled} job / manual trigger
 * under {@code /api/holds/disposition}), which owns cutoff computation, the
 * {@code disposition_runs} ledger and audit emission. Deliberately mounted
 * under {@code /internal} — the api-gateway has no route for it, so it is not
 * reachable from outside the compose network and needs no gateway change.
 */
@RestController
@RequestMapping("/internal/disposition")
public class DispositionInternalController {

    public record TypeCutoffDto(String commType, Instant cutoff) {
    }

    public record RunRequest(List<TypeCutoffDto> cutoffs) {
    }

    public record RunResponse(long scanned, long deleted, long skippedOnHold, long errors,
                              Map<String, Long> deletedByType) {
    }

    private final DispositionExecutor executor;

    public DispositionInternalController(DispositionExecutor executor) {
        this.executor = executor;
    }

    @PostMapping("/run")
    public RunResponse run(@RequestBody RunRequest request) {
        List<DispositionExecutor.TypeCutoff> cutoffs = request.cutoffs() == null
                ? List.of()
                : request.cutoffs().stream()
                        .map(c -> new DispositionExecutor.TypeCutoff(c.commType(), c.cutoff()))
                        .toList();
        DispositionExecutor.Result result = executor.run(cutoffs);
        return new RunResponse(result.scanned(), result.deleted(), result.skippedOnHold(),
                result.errors(), result.deletedByType());
    }
}
