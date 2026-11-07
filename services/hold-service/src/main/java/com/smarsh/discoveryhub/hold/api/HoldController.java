package com.smarsh.discoveryhub.hold.api;

import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.hold.api.dto.HoldRequest;
import com.smarsh.discoveryhub.hold.api.dto.HoldResponse;
import com.smarsh.discoveryhub.hold.service.HoldService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

/**
 * Legal hold endpoints (FR-4.1/FR-4.3). Placement is asynchronous: the service
 * persists a {@code PENDING} row + a transactional-outbox {@code hold.commands.v1}
 * entry in one transaction and returns 202 immediately; the outbox poller then
 * publishes the command for the archive hold-applier. {@code GET /holds/{id}}
 * reads the persisted hold and its progress (held/applied counts, status).
 */
@RestController
@RequestMapping("/api/holds")
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping
    public ResponseEntity<HoldResponse> place(@Valid @RequestBody HoldRequest request) {
        HoldResponse response = holdService.place(request);
        return ResponseEntity
                .accepted()
                .location(UriComponentsBuilder.fromPath("/api/holds/{id}").build(response.holdId()))
                .body(response);
    }

    @GetMapping
    public List<HoldResponse> list(@RequestParam(required = false) UUID caseId) {
        return holdService.list(caseId);
    }

    @GetMapping("/{holdId}")
    public HoldResponse get(@PathVariable UUID holdId) {
        if (holdId == null) {
            throw new NotFoundException("Hold", "null");
        }
        return holdService.get(holdId);
    }

    /**
     * Releases a hold (FR-4.5). Asynchronous like placement: flips the hold to
     * {@code RELEASING}, writes the {@code RELEASE} outbox command, and returns
     * 202 — the archive hold-applier {@code $pull}s the hold id from each
     * message's hold set, leaving overlapping holds intact.
     */
    @DeleteMapping("/{holdId}")
    public ResponseEntity<HoldResponse> release(@PathVariable UUID holdId) {
        return ResponseEntity.accepted().body(holdService.release(holdId));
    }
}
