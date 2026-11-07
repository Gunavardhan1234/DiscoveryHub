package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoMessageReadAdapterTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final MongoMessageReadAdapter adapter = new MongoMessageReadAdapter(mongo);

    @Test
    void findByIdNormalizesTheRawDocumentToTheApiShape() {
        Instant sentAt = Instant.parse("2026-01-01T00:00:00Z");
        Document doc = new Document("_id", "msg-1")
                .append("subject", "hi")
                .append("sentAt", sentAt)
                .append("custodianId", "c-1");
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("messages"))).thenReturn(doc);

        Optional<Map<String, Object>> result = adapter.findById("msg-1");

        assertThat(result).isPresent();
        Map<String, Object> message = result.get();
        assertThat(message.get("id")).isEqualTo("msg-1");
        assertThat(message.get("subject")).isEqualTo("hi");
        assertThat(message.get("timestamp")).isEqualTo(sentAt);
        assertThat(message).doesNotContainKey("sentAt").doesNotContainKey("_id");
        assertThat(message.get("holds")).isEqualTo(List.of());
        assertThat(message.get("onHold")).isEqualTo(false);
        assertThat(message.get("hasAttachment")).isEqualTo(false);
        assertThat(message.get("attachments")).isEqualTo(List.of());
    }

    @Test
    void findByIdReturnsEmptyWhenNotFound() {
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("messages"))).thenReturn(null);

        assertThat(adapter.findById("missing")).isEmpty();
    }

    @Test
    void normalizeDoesNotOverwriteFieldsAlreadyPresent() {
        Document doc = new Document("_id", "msg-1")
                .append("holds", List.of("hold-1"))
                .append("onHold", true)
                .append("hasAttachment", true)
                .append("attachments", List.of(new Document("id", "a1")));
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("messages"))).thenReturn(doc);

        Map<String, Object> message = adapter.findById("msg-1").orElseThrow();

        assertThat(message.get("holds")).isEqualTo(List.of("hold-1"));
        assertThat(message.get("onHold")).isEqualTo(true);
        assertThat(message.get("hasAttachment")).isEqualTo(true);
    }

    @Test
    void findByIdsNormalizesEveryDocument() {
        Document doc1 = new Document("_id", "m1").append("subject", "a");
        Document doc2 = new Document("_id", "m2").append("subject", "b");
        when(mongo.find(any(Query.class), eq(Document.class), eq("messages"))).thenReturn(List.of(doc1, doc2));

        List<Map<String, Object>> messages = adapter.findByIds(List.of("m1", "m2"));

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("id")).isEqualTo("m1");
        assertThat(messages.get(1).get("id")).isEqualTo("m2");
    }

    @Test
    void findByIdsReturnsEmptyListWhenNoneMatch() {
        when(mongo.find(any(Query.class), eq(Document.class), eq("messages"))).thenReturn(List.of());

        assertThat(adapter.findByIds(List.of("missing"))).isEmpty();
    }
}
