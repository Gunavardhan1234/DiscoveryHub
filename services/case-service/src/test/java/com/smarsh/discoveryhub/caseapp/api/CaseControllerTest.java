package com.smarsh.discoveryhub.caseapp.api;

import com.smarsh.discoveryhub.caseapp.domain.model.CaseStateMachine;
import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import com.smarsh.discoveryhub.caseapp.domain.model.MatterType;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseResponse;
import com.smarsh.discoveryhub.caseapp.service.CaseService;
import com.smarsh.discoveryhub.common.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves {@link CaseController} actually delegates to {@link CaseService}
 * (FR-2.1/2.2) instead of returning hardcoded stub data, and that the
 * State-pattern rejection maps to HTTP 409 {@code INVALID_TRANSITION}.
 */
// @WebMvcTest(CaseController.class): a Spring test "slice" — boots only the web layer (this
// controller, its @RestControllerAdvice, JSON serialization) instead of the full application
// context (no real database, no Kafka), so these tests run fast and isolate what they claim to
// test: does CaseController wire HTTP requests to CaseService and map responses/exceptions
// correctly, independent of whether CaseService's own logic is correct (that's CaseServiceTest's job).
@WebMvcTest(CaseController.class)
class CaseControllerTest {

    // MockMvc: simulates real HTTP requests against the controller without starting an actual
    // servlet container/port — the closest thing to an end-to-end test that stays fast and in-process.
    @Autowired
    private MockMvc mvc;

    // @MockBean: replaces the real CaseService bean in the Spring context with a Mockito mock, so
    // this test controls exactly what the service layer returns/throws without needing real
    // business logic or a database to be present.
    @MockBean
    private CaseService caseService;

    private static CaseResponse sampleCase(UUID id, CaseStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-08T12:00:00Z");
        return new CaseResponse(id, "Project Phoenix", "Q2 review",
                MatterType.INVESTIGATION, "i-harriet", status, now, now, 0L);
    }

    @Test
    void createDelegatesToServiceAndReturns201() throws Exception {
        UUID id = UUID.randomUUID();
        when(caseService.create(any())).thenReturn(sampleCase(id, CaseStatus.DRAFT));

        mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Project Phoenix","description":"Q2 review","matterType":"INVESTIGATION","owner":"i-harriet"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void createWithMissingNameReturns400() throws Exception {
        mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON).content("""
                        {"description":"no name","matterType":"INVESTIGATION","owner":"i-harriet"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listReturnsPagedResponseShape() throws Exception {
        UUID id = UUID.randomUUID();
        var page = new PageImpl<>(List.of(sampleCase(id, CaseStatus.ACTIVE)), PageRequest.of(0, 20), 1);
        when(caseService.list(eq(CaseStatus.ACTIVE), any(), any())).thenReturn(page);

        mvc.perform(get("/api/cases").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void getUnknownCaseReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(caseService.get(id)).thenThrow(new NotFoundException("Case", id.toString()));

        mvc.perform(get("/api/cases/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // Proves the full exception -> HTTP mapping chain within the web slice: CaseService throwing
    // InvalidCaseTransitionException is actually translated to a 409 with the right JSON body by
    // CaseExceptionHandler (which @WebMvcTest DOES load, unlike a plain unit test of the handler).
    @Test
    void invalidTransitionReturns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(caseService.transition(eq(id), eq(CaseStatus.DRAFT)))
                .thenThrow(new CaseStateMachine.InvalidCaseTransitionException(
                        CaseStatus.ACTIVE, CaseStatus.DRAFT, EnumSet.of(CaseStatus.UNDER_REVIEW)));

        mvc.perform(post("/api/cases/" + id + "/transition")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"to":"DRAFT"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    }

    @Test
    void validTransitionDelegatesToService() throws Exception {
        UUID id = UUID.randomUUID();
        when(caseService.transition(id, CaseStatus.ACTIVE)).thenReturn(sampleCase(id, CaseStatus.ACTIVE));

        mvc.perform(post("/api/cases/" + id + "/transition")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"to":"ACTIVE"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void updateDelegatesToServiceAndReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(caseService.update(eq(id), any())).thenReturn(sampleCase(id, CaseStatus.ACTIVE));

        mvc.perform(put("/api/cases/" + id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Renamed Matter","matterType":"LITIGATION"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Project Phoenix"));
    }

    @Test
    void updateOnClosedCaseReturns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(caseService.update(eq(id), any()))
                .thenThrow(new com.smarsh.discoveryhub.common.ConflictException(
                        "CASE_CLOSED", "Case '" + id + "' is closed and read-only"));

        mvc.perform(put("/api/cases/" + id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Renamed Matter"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_CLOSED"));
    }
}
