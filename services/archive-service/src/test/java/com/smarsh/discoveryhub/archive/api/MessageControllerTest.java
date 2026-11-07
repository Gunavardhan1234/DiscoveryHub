package com.smarsh.discoveryhub.archive.api;

import com.smarsh.discoveryhub.archive.application.FakeMessageDeletionStore;
import com.smarsh.discoveryhub.archive.application.RetentionGuard;
import com.smarsh.discoveryhub.archive.domain.port.MessageReadPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wire-level proof of the FR-4.6 contract (docs/api-contracts.md §archive):
 * {@code DELETE /api/messages/{id}} → 204 when unheld, 404
 * {@code MESSAGE_NOT_FOUND}, and 409 {@code MESSAGE_ON_HOLD} with the
 * protecting holds list — the Day-2 gate's "DELETE held message → 409" proof.
 * Plus the FR-1.4 reads: single GET (404 when absent) and the bulk GET the
 * export worker consumes.
 */
class MessageControllerTest {

    /** In-memory {@link MessageReadPort} mirroring the Mongo adapter's normalized shape. */
    private static final class FakeMessageReads implements MessageReadPort {
        private final Map<String, Map<String, Object>> messages = new LinkedHashMap<>();

        void add(String id) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", id);
            message.put("subject", "subject-" + id);
            messages.put(id, message);
        }

        @Override
        public Optional<Map<String, Object>> findById(String id) {
            return Optional.ofNullable(messages.get(id));
        }

        @Override
        public List<Map<String, Object>> findByIds(List<String> ids) {
            return ids.stream().map(messages::get).filter(java.util.Objects::nonNull).toList();
        }
    }

    private final FakeMessageDeletionStore store = new FakeMessageDeletionStore();
    private final FakeMessageReads reads = new FakeMessageReads();
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new MessageController(new RetentionGuard(store), reads))
            .setControllerAdvice(new ArchiveExceptionHandler())
            .build();

    @Test
    void deleteUnheldMessageReturns204() throws Exception {
        store.add("m-1");
        mvc.perform(delete("/api/messages/m-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteMissingMessageReturns404WithCode() throws Exception {
        mvc.perform(delete("/api/messages/m-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MESSAGE_NOT_FOUND"));
    }

    @Test
    void deleteHeldMessageReturns409WithHoldsList() throws Exception {
        store.add("m-2", "h-1", "h-9");
        mvc.perform(delete("/api/messages/m-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MESSAGE_ON_HOLD"))
                .andExpect(jsonPath("$.holds[0]").value("h-1"))
                .andExpect(jsonPath("$.holds[1]").value("h-9"));
    }

    @Test
    void getReturnsStoredMessage() throws Exception {
        reads.add("m-1");
        mvc.perform(get("/api/messages/m-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("m-1"))
                .andExpect(jsonPath("$.subject").value("subject-m-1"));
    }

    @Test
    void getMissingMessageReturns404WithCode() throws Exception {
        mvc.perform(get("/api/messages/m-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MESSAGE_NOT_FOUND"));
    }

    @Test
    void bulkGetReturnsOnlyKnownIds() throws Exception {
        reads.add("m-1");
        reads.add("m-2");
        mvc.perform(get("/api/messages").param("ids", "m-1, m-2, m-missing, m-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].id").value("m-1"))
                .andExpect(jsonPath("$.messages[1].id").value("m-2"));
    }

    @Test
    void bulkGetWithoutIdsReturnsEmptyList() throws Exception {
        mvc.perform(get("/api/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(0));
    }
}
