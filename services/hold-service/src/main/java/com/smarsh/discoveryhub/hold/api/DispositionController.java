package com.smarsh.discoveryhub.hold.api;

import com.smarsh.discoveryhub.hold.api.dto.DispositionRunResponse;
import com.smarsh.discoveryhub.hold.service.DispositionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Disposition endpoints (FR-5) for the Disposition screen. Mounted under
 * {@code /api/holds/disposition} so they ride the api-gateway's existing
 * {@code /api/holds/**} route to hold-service — like the retention-policy
 * endpoints, no gateway change required. {@code POST /run} is the manual demo
 * trigger; the {@code @Scheduled} job calls the same service.
 *
 * <p>{@code POST /run} returns {@code 202} with a {@code RUNNING} row
 * immediately — the actual scan-and-delete pass happens on a background
 * thread and can take a while over a large corpus, so the Disposition screen
 * polls {@code GET /runs}/{@code /runs/latest} for completion instead of
 * blocking on this call.
 */
@RestController
@RequestMapping("/api/holds/disposition")
public class DispositionController {

    private final DispositionService disposition;

    public DispositionController(DispositionService disposition) {
        this.disposition = disposition;
    }

    @PostMapping("/run")
    public ResponseEntity<DispositionRunResponse> run() {
        DispositionRunResponse started = disposition.run(DispositionService.Trigger.MANUAL, "operator").orElseThrow();
        return ResponseEntity.accepted().body(started);
    }

    @GetMapping("/runs")
    public List<DispositionRunResponse> runs() {
        return disposition.list();
    }

    @GetMapping("/runs/latest")
    public DispositionRunResponse latest() {
        return disposition.latest();
    }
}
