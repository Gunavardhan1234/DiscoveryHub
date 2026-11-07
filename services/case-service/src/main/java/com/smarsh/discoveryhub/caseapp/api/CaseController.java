package com.smarsh.discoveryhub.caseapp.api;

import com.smarsh.discoveryhub.caseapp.api.dto.CaseCreateRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseResponse;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseUpdateRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.PageResponse;
import com.smarsh.discoveryhub.caseapp.api.dto.TransitionRequest;
import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import com.smarsh.discoveryhub.caseapp.service.CaseService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * FR-2: case CRUD and State-pattern lifecycle transitions
 * (docs/api-contracts.md &sect;case-service), backed by {@link CaseService}.
 *
 * <p>Legal holds are placed directly against hold-service
 * ({@code POST /api/holds}, routed by api-gateway to port 8087) rather than
 * through this controller — an earlier {@code POST /api/cases/{id}/holds}
 * design was superseded once hold-service became its own independently
 * deployable service with its own Postgres schema (NFR-1).
 */
// Controller -> Service -> Repository layering (Separation of Concerns): this class only handles
// HTTP concerns (routing, request/response DTOs, status codes) — every business rule (state
// machine, idempotency, audit) lives in CaseService, which knows nothing about HTTP at all. Either
// layer can change independently: a new API shape doesn't touch business logic, and vice versa.
@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService caseService;

    // Constructor injection: CaseService is the controller's only dependency, made explicit and
    // immutable — Spring supplies the real bean; a slice test (@WebMvcTest) supplies a @MockBean.
    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    // POST creates a new case (REST convention); @Valid triggers Bean Validation on
    // CaseCreateRequest's @NotBlank/@NotNull annotations before this method body even runs — a
    // malformed request never reaches business logic. HTTP 201 Created + a Location header
    // pointing at the new resource's URI is the standard REST response shape for a successful create.
    @PostMapping
    public ResponseEntity<CaseResponse> create(@Valid @RequestBody CaseCreateRequest request) {
        CaseResponse created = caseService.create(request);
        return ResponseEntity.created(URI.create("/api/cases/" + created.id())).body(created);
    }

    // Optional query parameters (required = false) let the same endpoint serve "all cases" or
    // "filter by status/name" without duplicating routes; Pageable is bound automatically by
    // Spring Data from ?page=&size=&sort= query params.
    @GetMapping
    public PageResponse<CaseResponse> list(
            @RequestParam(required = false) CaseStatus status,
            @RequestParam(required = false) String q,
            Pageable pageable) {
        return PageResponse.from(caseService.list(status, q, pageable));
    }

    @GetMapping("/{id}")
    public CaseResponse get(@PathVariable UUID id) {
        return caseService.get(id);
    }

    /**
     * FR-2.3 / FR-7.1: partial update of a case's mutable metadata (name,
     * description, matterType, owner). {@code status} is not updatable here.
     * A {@code CLOSED} case is read-only → {@code 409 CASE_CLOSED} (FR-2.5),
     * surfaced from {@link CaseService#update} and mapped by the advice.
     */
    // PUT for a partial update (not strictly REST-pure, where PUT implies a full replace) — chosen
    // to match this API's actual semantics (partial field updates via null = "unchanged") rather
    // than introducing PATCH, which the rest of docs/api-contracts.md doesn't otherwise use.
    @PutMapping("/{id}")
    public CaseResponse update(@PathVariable UUID id, @RequestBody CaseUpdateRequest request) {
        return caseService.update(id, request);
    }

    /**
     * FR-2.2 (State pattern) / FR-2.5 (closed case is terminal): invalid or
     * closed-case transitions are rejected by {@link CaseService#transition}
     * via {@code CaseStateMachine.assertTransition}, which throws
     * {@code ConflictException("INVALID_TRANSITION", ...)} → HTTP 409.
     */
    @PostMapping("/{id}/transition")
    public CaseResponse transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return caseService.transition(id, request.to());
    }
}
