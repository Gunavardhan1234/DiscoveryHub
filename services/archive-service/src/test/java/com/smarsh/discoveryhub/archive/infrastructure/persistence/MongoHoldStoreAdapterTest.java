package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoHoldStoreAdapterTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> collection = mock(MongoCollection.class);
    private final MongoHoldStoreAdapter adapter = new MongoHoldStoreAdapter(mongo);

    private void stubFind(Document... docs) {
        @SuppressWarnings("unchecked")
        FindIterable<Document> iterable = mock(FindIterable.class);
        @SuppressWarnings("unchecked")
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        var delegate = List.of(docs).iterator();
        when(cursor.hasNext()).thenAnswer(inv -> delegate.hasNext());
        when(cursor.next()).thenAnswer(inv -> delegate.next());
        when(iterable.iterator()).thenReturn(cursor);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(mongo.getCollection("messages")).thenReturn(collection);
    }

    @Test
    void findIdsByScopeReturnsMatchingIds() {
        stubFind(new Document("_id", "m1"), new Document("_id", "m2"));

        List<String> ids = adapter.findIdsByScope(List.of("c-1"), null, null, null);

        assertThat(ids).containsExactly("m1", "m2");
    }

    @Test
    void findIdsByScopeWithNoFiltersUsesUnconstrainedQuery() {
        stubFind(new Document("_id", "m1"));

        List<String> ids = adapter.findIdsByScope(List.of(), null, null, null);

        assertThat(ids).containsExactly("m1");
    }

    @Test
    void findIdsByScopeAppliesDateRangeAndTerms() {
        stubFind(new Document("_id", "m1"));

        List<String> ids = adapter.findIdsByScope(List.of("c-1"),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-30T00:00:00Z"), "wire transfer");

        assertThat(ids).containsExactly("m1");
    }

    @Test
    void findIdsByHoldReturnsMatchingIds() {
        stubFind(new Document("_id", "m1"), new Document("_id", "m2"), new Document("_id", "m3"));

        List<String> ids = adapter.findIdsByHold("hold-1");

        assertThat(ids).containsExactly("m1", "m2", "m3");
    }

    @Test
    void addHoldReturnsTrueWhenModified() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.wasAcknowledged()).thenReturn(true);
        when(result.getModifiedCount()).thenReturn(1L);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(MessageDocument.class))).thenReturn(result);

        assertThat(adapter.addHold("msg-1", "hold-1")).isTrue();
    }

    @Test
    void addHoldReturnsFalseWhenNothingWasModified() {
        UpdateResult result = mock(UpdateResult.class);
        when(result.wasAcknowledged()).thenReturn(true);
        when(result.getModifiedCount()).thenReturn(0L);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(MessageDocument.class))).thenReturn(result);

        assertThat(adapter.addHold("msg-1", "hold-1")).isFalse();
    }

    @Test
    void removeHoldReturnsTrueWhenAnOverlappingHoldStillProtectsTheMessage() {
        when(mongo.getCollection("messages")).thenReturn(collection);
        Document updated = new Document("_id", "msg-1").append("onHold", true);
        when(collection.findOneAndUpdate(any(Bson.class), org.mockito.ArgumentMatchers.<List<? extends Bson>>any(),
                any(com.mongodb.client.model.FindOneAndUpdateOptions.class)))
                .thenReturn(updated);

        assertThat(adapter.removeHold("msg-1", "hold-1")).isTrue();
    }

    @Test
    void removeHoldReturnsFalseWhenFullyReleased() {
        when(mongo.getCollection("messages")).thenReturn(collection);
        Document updated = new Document("_id", "msg-1").append("onHold", false);
        when(collection.findOneAndUpdate(any(Bson.class), org.mockito.ArgumentMatchers.<List<? extends Bson>>any(),
                any(com.mongodb.client.model.FindOneAndUpdateOptions.class)))
                .thenReturn(updated);

        assertThat(adapter.removeHold("msg-1", "hold-1")).isFalse();
    }

    @Test
    void removeHoldReturnsFalseWhenMessageDoesNotExist() {
        when(mongo.getCollection("messages")).thenReturn(collection);
        when(collection.findOneAndUpdate(any(Bson.class), org.mockito.ArgumentMatchers.<List<? extends Bson>>any(),
                any(com.mongodb.client.model.FindOneAndUpdateOptions.class)))
                .thenReturn(null);

        assertThat(adapter.removeHold("missing", "hold-1")).isFalse();
    }
}
