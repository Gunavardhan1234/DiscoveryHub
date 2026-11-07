package com.smarsh.discoveryhub.archive.api;

import com.smarsh.discoveryhub.archive.application.DispositionExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DispositionInternalController.class)
class DispositionInternalControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DispositionExecutor executor;

    @Test
    void runMapsCutoffsAndReturnsTheExecutorResult() throws Exception {
        when(executor.run(any())).thenReturn(new DispositionExecutor.Result(
                1000, 613, 4182, 0, Map.of("EMAIL", 613L)));

        mvc.perform(post("/internal/disposition/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cutoffs":[{"commType":"EMAIL","cutoff":"2026-01-01T00:00:00Z"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(1000))
                .andExpect(jsonPath("$.deleted").value(613))
                .andExpect(jsonPath("$.skippedOnHold").value(4182));
    }

    @Test
    void runWithNullCutoffsPassesAnEmptyList() throws Exception {
        when(executor.run(List.of())).thenReturn(new DispositionExecutor.Result(0, 0, 0, 0, Map.of()));

        mvc.perform(post("/internal/disposition/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(0));
    }
}
