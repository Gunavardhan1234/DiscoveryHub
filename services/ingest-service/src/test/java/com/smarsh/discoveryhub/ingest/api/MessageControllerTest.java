package com.smarsh.discoveryhub.ingest.api;

import com.smarsh.discoveryhub.ingest.application.IngestResult;
import com.smarsh.discoveryhub.ingest.application.IngestMessageUseCase;
import com.smarsh.discoveryhub.ingest.application.CorpusGenerationUseCase;
import com.smarsh.discoveryhub.ingest.application.IngestMessageCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MessageController.class)
class MessageControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private IngestMessageUseCase ingestUseCase;

    @MockBean
    private CorpusGenerationUseCase generateUseCase;

    private static final String VALID = """
            {"externalMessageId":"ext-1","type":"EMAIL","subject":"Q2 wire",
             "body":"Please process","threadId":"th-1","inReplyTo":null,"custodian":"c-0007",
             "participants":[{"name":"Ada Byron","address":"ada@fict.example","role":"FROM"},
                             {"name":"Charlie Babbage","address":"charlie@fict.example","role":"TO"}],
             "sentAt":"2026-03-14T09:12:00Z","attachments":[]}""";

    @Test
    void acceptedReturns202() throws Exception {
        when(ingestUseCase.execute(any(IngestMessageCommand.class)))
                .thenReturn(new IngestResult("msg-1", IngestResult.Status.ACCEPTED));

        mvc.perform(post("/api/messages/ingest").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messageId").value("msg-1"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void duplicateReturns200() throws Exception {
        when(ingestUseCase.execute(any(IngestMessageCommand.class)))
                .thenReturn(new IngestResult("msg-1", IngestResult.Status.DUPLICATE));

        mvc.perform(post("/api/messages/ingest").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }

    @Test
    void invalidRequestReturns422() throws Exception {
        String missingBody = """
                {"externalMessageId":"ext-1","type":"EMAIL","subject":"Q2 wire",
                 "body":"","threadId":"th-1","custodian":"c-0007",
                 "participants":[{"name":"A","address":"a@x","role":"FROM"}],
                 "sentAt":"2026-03-14T09:12:00Z","attachments":[]}""";

        mvc.perform(post("/api/messages/ingest").contentType(MediaType.APPLICATION_JSON).content(missingBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void invalidTypeReturns422() throws Exception {
        String badType = VALID.replace("\"EMAIL\"", "\"FAX\"");

        mvc.perform(post("/api/messages/ingest").contentType(MediaType.APPLICATION_JSON).content(badType))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void batchIngestReturns202WithPerMessageStatuses() throws Exception {
        when(ingestUseCase.executeBatch(anyList())).thenReturn(List.of(
                new IngestResult("msg-1", IngestResult.Status.ACCEPTED),
                new IngestResult("msg-2", IngestResult.Status.DUPLICATE)));
        String batch = """
                {"messages":[
                  {"externalMessageId":"ext-1","type":"EMAIL","subject":"Q2 wire",
                   "body":"Please process","threadId":"th-1","inReplyTo":null,"custodian":"c-0007",
                   "participants":[{"name":"Ada Byron","address":"ada@fict.example","role":"FROM"},
                                   {"name":"Charlie Babbage","address":"charlie@fict.example","role":"TO"}],
                   "sentAt":"2026-03-14T09:12:00Z",
                   "attachments":[{"id":"a-1","filename":"instructions.pdf","contentType":"application/pdf",
                                   "size":1024,"content":null}]},
                  {"externalMessageId":"ext-2","type":"CHAT","subject":null,
                   "body":"ship it","threadId":"th-2","inReplyTo":null,"custodian":"c-0008",
                   "participants":[{"name":"Grace Hopper","address":"grace@fict.example","role":"FROM"},
                                   {"name":"Alan Turing","address":"alan@fict.example","role":"TO"}],
                   "sentAt":"2026-03-14T09:13:00Z","attachments":[]}]}""";

        mvc.perform(post("/api/messages/ingest:batch").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$[0].messageId").value("msg-1"))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$[1].messageId").value("msg-2"))
                .andExpect(jsonPath("$[1].status").value("DUPLICATE"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IngestMessageCommand>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(ingestUseCase).executeBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        // The attachment reference is mapped through to the command (claim-check input).
        assertThat(captor.getValue().get(0).attachments()).hasSize(1);
        assertThat(captor.getValue().get(0).attachments().get(0).filename()).isEqualTo("instructions.pdf");
    }

    @Test
    void emptyBatchIsRejectedWith422() throws Exception {
        mvc.perform(post("/api/messages/ingest:batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void generateKicksOffCorpusJobAndReturns202() throws Exception {
        when(generateUseCase.start(25, 40))
                .thenReturn(new CorpusGenerationUseCase.GenerationStarted("gen-1", 25, "RUNNING"));

        mvc.perform(post("/api/admin/generate").queryParam("count", "25")
                        .queryParam("attachmentPct", "40"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("gen-1"))
                .andExpect(jsonPath("$.count").value(25))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void generateUsesDefaultCountAndAttachmentPct() throws Exception {
        when(generateUseCase.start(10000, 8))
                .thenReturn(new CorpusGenerationUseCase.GenerationStarted("gen-2", 10000, "RUNNING"));

        mvc.perform(post("/api/admin/generate"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("gen-2"))
                .andExpect(jsonPath("$.count").value(10000));
    }
}
