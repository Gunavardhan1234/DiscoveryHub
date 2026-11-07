package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import com.mongodb.client.result.UpdateResult;
import com.smarsh.discoveryhub.events.CommunicationEvent;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoMessageStoreAdapterTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final MongoMessageStoreAdapter adapter = new MongoMessageStoreAdapter(mongo);

    private CommunicationEvent event(String messageId) {
        return new CommunicationEvent(messageId, "key-1", "ext-1", "EMAIL", "hi", "body",
                "thread-1", null, "c-1",
                List.of(new CommunicationEvent.Participant("Ada", "ada@fict.example", "FROM")),
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new CommunicationEvent.Attachment("a1", "file.pdf", "application/pdf", 100, "sha", "s3key")),
                true);
    }

    @Test
    void upsertReturnsTrueForANewlyInsertedMessage() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.wasAcknowledged()).thenReturn(true);
        when(result.getUpsertedId()).thenReturn(new BsonString("msg-1"));
        when(mongo.upsert(any(Query.class), any(Update.class), eq(MessageDocument.class))).thenReturn(result);

        boolean created = adapter.upsert(event("msg-1"));

        assertThat(created).isTrue();
    }

    @Test
    void upsertReturnsFalseForARedeliveredDuplicate() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.wasAcknowledged()).thenReturn(true);
        when(result.getUpsertedId()).thenReturn(null);
        when(mongo.upsert(any(Query.class), any(Update.class), eq(MessageDocument.class))).thenReturn(result);

        boolean created = adapter.upsert(event("msg-1"));

        assertThat(created).isFalse();
    }

    @Test
    void upsertReturnsFalseWhenNotAcknowledged() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.wasAcknowledged()).thenReturn(false);
        when(mongo.upsert(any(Query.class), any(Update.class), eq(MessageDocument.class))).thenReturn(result);

        assertThat(adapter.upsert(event("msg-1"))).isFalse();
    }

    @Test
    void streamAllReconstructsEveryStoredMessageAsACommunicationEvent() {
        MessageDocument doc = new MessageDocument();
        doc.setId("msg-1");
        doc.setMessageKey("key-1");
        doc.setType("EMAIL");
        doc.setSubject("hi");
        doc.setBody("body");
        doc.setThreadId("thread-1");
        doc.setCustodianId("c-1");
        doc.setTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
        doc.setParticipants(List.of(new MessageDocument.Participant("Ada", "ada@fict.example", "FROM")));
        doc.setAttachments(List.of(new MessageDocument.Attachment("a1", "file.pdf", "application/pdf", 100, "sha", "s3key")));
        doc.setHasAttachment(true);
        when(mongo.stream(any(Query.class), eq(MessageDocument.class))).thenReturn(Stream.of(doc));

        List<CommunicationEvent> events = adapter.streamAll().toList();

        assertThat(events).hasSize(1);
        CommunicationEvent rebuilt = events.get(0);
        assertThat(rebuilt.messageId()).isEqualTo("msg-1");
        assertThat(rebuilt.messageKey()).isEqualTo("key-1");
        assertThat(rebuilt.sentAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(rebuilt.participants()).hasSize(1);
        assertThat(rebuilt.attachments()).hasSize(1);
        assertThat(rebuilt.hasAttachment()).isTrue();
    }

    @Test
    void streamAllFallsBackToIngestedAtWhenSentAtIsMissing() {
        MessageDocument doc = new MessageDocument();
        doc.setId("msg-2");
        doc.setIngestedAt(Instant.parse("2026-02-01T00:00:00Z"));
        when(mongo.stream(any(Query.class), eq(MessageDocument.class))).thenReturn(Stream.of(doc));

        CommunicationEvent rebuilt = adapter.streamAll().toList().get(0);

        assertThat(rebuilt.sentAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    }
}
