package com.smarsh.discoveryhub.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link CorpusGenerator#main} end-to-end against a local HTTP server
 * standing in for ingest-service, so the batching/threading/option-parsing
 * logic runs without any infrastructure.
 */
class CorpusGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(202, -1);
        try (OutputStream out = exchange.getResponseBody()) {
            out.flush();
        }
    }

    private String[] args(int messages, int custodians, int attachmentPct) {
        return new String[]{
                "--messages", String.valueOf(messages),
                "--custodians", String.valueOf(custodians),
                "--attachment-pct", String.valueOf(attachmentPct),
                "--ingest-url", "http://localhost:" + server.getAddress().getPort()};
    }

    @Test
    void postsAllMessagesInBatchesAndSucceeds() throws Exception {
        CorpusGenerator.main(args(150, 5, 20));

        // 150 messages → two batches: 100 + 50 (BATCH_SIZE is 100).
        assertThat(bodies).hasSize(2);
        int total = 0;
        for (String body : bodies) {
            JsonNode node = MAPPER.readTree(body);
            assertThat(node.has("messages")).isTrue();
            total += node.get("messages").size();
        }
        assertThat(total).isEqualTo(150);
    }

    @Test
    void producesUniqueExternalIdsAndCyclingCustodians() throws Exception {
        CorpusGenerator.main(args(12, 3, 0));

        JsonNode messages = MAPPER.readTree(bodies.getFirst()).get("messages");
        List<String> ids = new java.util.ArrayList<>();
        List<String> custodians = new java.util.ArrayList<>();
        messages.forEach(m -> {
            ids.add(m.get("externalMessageId").asText());
            custodians.add(m.get("custodian").asText());
        });

        // Unique ids keep re-runs dedupe-friendly; custodians cycle round-robin
        // over the roster slice (--custodians 3 → c-0001, c-0002, c-0003).
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(custodians).containsExactly("c-0001", "c-0002", "c-0003",
                "c-0001", "c-0002", "c-0003",
                "c-0001", "c-0002", "c-0003",
                "c-0001", "c-0002", "c-0003");
    }

    @Test
    void threadsMessagesIntoReplyChains() throws Exception {
        CorpusGenerator.main(args(6, 3, 0));

        JsonNode messages = MAPPER.readTree(bodies.getFirst()).get("messages");
        // First message starts a thread (no inReplyTo); the next four reply into it.
        assertThat(messages.get(0).get("inReplyTo").isNull()).isTrue();
        assertThat(messages.get(0).get("threadId").asText()).isEqualTo("thread-1");
        assertThat(messages.get(1).get("inReplyTo").isNull()).isFalse();
        assertThat(messages.get(1).get("threadId").asText()).isEqualTo("thread-1");
        // i == 5 starts thread-2.
        assertThat(messages.get(5).get("threadId").asText()).isEqualTo("thread-2");
        assertThat(messages.get(5).get("inReplyTo").isNull()).isTrue();
    }

    @Test
    void emitsBothEmailAndChatTypes() throws Exception {
        CorpusGenerator.main(args(20, 5, 0));

        JsonNode messages = MAPPER.readTree(bodies.getFirst()).get("messages");
        List<String> types = new java.util.ArrayList<>();
        messages.forEach(m -> types.add(m.get("type").asText()));
        assertThat(types).contains("EMAIL", "CHAT");
    }
}
