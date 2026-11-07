package com.smarsh.discoveryhub.hold.api;

import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.hold.api.dto.HoldRequest;
import com.smarsh.discoveryhub.hold.api.dto.HoldResponse;
import com.smarsh.discoveryhub.hold.service.HoldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves {@link HoldController} delegates to {@link HoldService} and maps its responses/errors correctly. */
@WebMvcTest(HoldController.class)
class HoldControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private HoldService holdService;

    private HoldResponse sample(String holdId, String caseId, String status) {
        return new HoldResponse(holdId, caseId, status, List.of("c-1"), null, null, null,
                0, 0, 0, "alice", Instant.now(), null);
    }

    @Test
    void placeReturns202WithLocationHeader() throws Exception {
        UUID holdId = UUID.randomUUID();
        when(holdService.place(any(HoldRequest.class))).thenReturn(sample(holdId.toString(), "case-1", "PENDING"));

        mvc.perform(post("/api/holds").contentType(MediaType.APPLICATION_JSON).content("""
                        {"caseId":"case-1","custodians":["c-1"],"placedBy":"alice"}"""))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/holds/" + holdId))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void placeOnClosedCaseReturns409() throws Exception {
        when(holdService.place(any(HoldRequest.class)))
                .thenThrow(new ConflictException("CASE_CLOSED", "Case is closed"));

        mvc.perform(post("/api/holds").contentType(MediaType.APPLICATION_JSON).content("""
                        {"caseId":"case-1","custodians":["c-1"],"placedBy":"alice"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_CLOSED"));
    }

    @Test
    void listDelegatesOptionalCaseIdFilter() throws Exception {
        UUID caseId = UUID.randomUUID();
        when(holdService.list(caseId)).thenReturn(List.of(sample("h-1", caseId.toString(), "ACTIVE")));

        mvc.perform(get("/api/holds").param("caseId", caseId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].holdId").value("h-1"));
    }

    @Test
    void listWithoutCaseIdReturnsEveryHold() throws Exception {
        when(holdService.list(null)).thenReturn(List.of(sample("h-1", "case-1", "ACTIVE")));

        mvc.perform(get("/api/holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].holdId").value("h-1"));
    }

    @Test
    void getReturnsTheHold() throws Exception {
        UUID holdId = UUID.randomUUID();
        when(holdService.get(holdId)).thenReturn(sample(holdId.toString(), "case-1", "ACTIVE"));

        mvc.perform(get("/api/holds/" + holdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getUnknownHoldReturns404() throws Exception {
        UUID holdId = UUID.randomUUID();
        when(holdService.get(holdId)).thenThrow(new NotFoundException("Hold", holdId.toString()));

        mvc.perform(get("/api/holds/" + holdId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void releaseReturns202WithReleasingStatus() throws Exception {
        UUID holdId = UUID.randomUUID();
        when(holdService.release(holdId)).thenReturn(sample(holdId.toString(), "case-1", "RELEASING"));

        mvc.perform(delete("/api/holds/" + holdId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RELEASING"));
    }
}
