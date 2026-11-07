package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.smarsh.discoveryhub.archive.domain.port.DispositionStorePort;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoDispositionStoreAdapterTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> collection = mock(MongoCollection.class);
    @SuppressWarnings("unchecked")
    private final FindIterable<Document> iterable = mock(FindIterable.class);
    private final MongoDispositionStoreAdapter adapter = new MongoDispositionStoreAdapter(mongo);

    private void stubDocs(Document... docs) {
        when(mongo.getCollection("messages")).thenReturn(collection);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any(Bson.class))).thenReturn(iterable);
        when(iterable.limit(anyIntSafe())).thenReturn(iterable);
        when(iterable.projection(any(Bson.class))).thenReturn(iterable);
        org.mockito.Mockito.doAnswer(inv -> {
            Consumer<Document> consumer = inv.getArgument(0);
            for (Document doc : docs) consumer.accept(doc);
            return null;
        }).when(iterable).forEach(org.mockito.ArgumentMatchers.<Consumer<? super Document>>any());
    }

    private int anyIntSafe() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    @Test
    void findExpiredMapsHeldStatusAndAttachmentKeys() {
        Document held = new Document("_id", "m1")
                .append("holds", List.of("hold-1"))
                .append("attachments", List.of(new Document("s3Key", "att/m1/a1.pdf")));
        Document notHeld = new Document("_id", "m2")
                .append("holds", List.of())
                .append("attachments", List.of());
        stubDocs(held, notHeld);

        List<DispositionStorePort.Candidate> candidates = adapter.findExpired("EMAIL", Instant.now(), null, 100);

        assertThat(candidates).hasSize(2);
        DispositionStorePort.Candidate first = candidates.get(0);
        assertThat(first.messageId()).isEqualTo("m1");
        assertThat(first.onHold()).isTrue();
        assertThat(first.attachmentS3Keys()).containsExactly("att/m1/a1.pdf");
        DispositionStorePort.Candidate second = candidates.get(1);
        assertThat(second.messageId()).isEqualTo("m2");
        assertThat(second.onHold()).isFalse();
        assertThat(second.attachmentS3Keys()).isEmpty();
    }

    @Test
    void findExpiredWithNoResultsReturnsEmptyList() {
        stubDocs();

        assertThat(adapter.findExpired("CHAT", Instant.now(), "prev-id", 50)).isEmpty();
    }
}
