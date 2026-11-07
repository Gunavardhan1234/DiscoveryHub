package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import com.mongodb.client.result.DeleteResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoMessageDeletionAdapterTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final MongoMessageDeletionAdapter adapter = new MongoMessageDeletionAdapter(mongo);

    @Test
    void findByIdReturnsTheDocumentWhenItExists() {
        MessageDocument doc = new MessageDocument();
        doc.setId("msg-1");
        when(mongo.findById("msg-1", MessageDocument.class)).thenReturn(doc);

        assertThat(adapter.findById("msg-1")).contains(doc);
    }

    @Test
    void findByIdReturnsEmptyWhenMissing() {
        when(mongo.findById("missing", MessageDocument.class)).thenReturn(null);

        assertThat(adapter.findById("missing")).isEmpty();
    }

    @Test
    void deleteIfUnheldReturnsTrueWhenARowWasDeleted() {
        DeleteResult result = mock(DeleteResult.class);
        when(result.wasAcknowledged()).thenReturn(true);
        when(result.getDeletedCount()).thenReturn(1L);
        when(mongo.remove(any(Query.class), eq(MessageDocument.class))).thenReturn(result);

        assertThat(adapter.deleteIfUnheld("msg-1")).isTrue();
    }

    @Test
    void deleteIfUnheldReturnsFalseWhenTheMessageIsHeld() {
        // The {_id, holds: {$size:0}} filter matches nothing for a held message.
        DeleteResult result = mock(DeleteResult.class);
        when(result.wasAcknowledged()).thenReturn(true);
        when(result.getDeletedCount()).thenReturn(0L);
        when(mongo.remove(any(Query.class), eq(MessageDocument.class))).thenReturn(result);

        assertThat(adapter.deleteIfUnheld("held-msg")).isFalse();
    }
}
