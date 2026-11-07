package com.smarsh.discoveryhub.caseapp.api;

import com.smarsh.discoveryhub.caseapp.api.dto.SavedSearchRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.SavedSearchResponse;
import com.smarsh.discoveryhub.caseapp.service.SavedSearchService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/cases/{caseId}/saved-searches")
public class SavedSearchController {

    private final SavedSearchService savedSearchService;

    public SavedSearchController(SavedSearchService savedSearchService) {
        this.savedSearchService = savedSearchService;
    }

    @PostMapping
    public ResponseEntity<SavedSearchResponse> create(
            @PathVariable UUID caseId,
            @Valid @RequestBody SavedSearchRequest request) {
        SavedSearchResponse created = savedSearchService.create(caseId, request);
        return ResponseEntity.created(
                java.net.URI.create("/api/cases/" + caseId + "/saved-searches/" + created.id()))
                .body(created);
    }

    @GetMapping
    public List<SavedSearchResponse> list(@PathVariable UUID caseId) {
        return savedSearchService.list(caseId);
    }

    @GetMapping("/{searchId}")
    public SavedSearchResponse get(@PathVariable UUID caseId, @PathVariable UUID searchId) {
        return savedSearchService.get(searchId);
    }

    @DeleteMapping("/{searchId}")
    public ResponseEntity<Void> delete(@PathVariable UUID caseId, @PathVariable UUID searchId) {
        savedSearchService.delete(caseId, searchId);
        return ResponseEntity.noContent().build();
    }
}
