package com.smarsh.discoveryhub.archive.api;

import com.smarsh.discoveryhub.archive.application.DltReplayService;
import com.smarsh.discoveryhub.archive.application.ReindexService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ReindexService reindexService;
    @MockitoBean private DltReplayService dltReplayService;

    @Test
    void reindexReturns202WithRepublishedCount() throws Exception {
        when(reindexService.republishAll()).thenReturn(10_000L);

        mvc.perform(post("/admin/reindex"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.republished").value(10000));
    }

    @Test
    void replayDltReturns202WithReplayedCount() throws Exception {
        when(dltReplayService.replayAll()).thenReturn(3L);

        mvc.perform(post("/admin/dlt/replay"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.replayed").value(3));
    }
}
