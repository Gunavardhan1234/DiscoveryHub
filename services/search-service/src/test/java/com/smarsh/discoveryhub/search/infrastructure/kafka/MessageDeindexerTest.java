package com.smarsh.discoveryhub.search.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.MessageDeletedEvent;
import com.smarsh.discoveryhub.search.domain.model.FacetResult;
import com.smarsh.discoveryhub.search.domain.model.IndexedMessage;
import com.smarsh.discoveryhub.search.domain.model.QuerySpec;
import com.smarsh.discoveryhub.search.domain.model.SearchResult;
import com.smarsh.discoveryhub.search.domain.port.SearchPort;
import com.smarsh.discoveryhub.search.domain.service.SearchService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MessageDeindexer} unit tests (FR-5/FR-1.7): a batch of
 * {@code message.deleted.v1} events retracts exactly those message ids from
 * the search index, with no Kafka/ES required — a fake {@link SearchPort}
 * captures the delete calls.
 */
class MessageDeindexerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RecordingSearchPort port = new RecordingSearchPort();
    private final SearchService searchService = new SearchService(port, () -> 0, (payload, key) -> { });
    private final MessageDeindexer deindexer = new MessageDeindexer(mapper, searchService);

    @Test
    void retractsAllMessageIdsFromABatch() throws Exception {
        deindexer.onBatch(List.of(envelope("m-1", "CHAT", "DISPOSITION"), envelope("m-2", "EMAIL", "MANUAL")));

        assertThat(port.deleted).containsExactly("m-1", "m-2");
    }

    @Test
    void ignoresMalformedEventsWithoutFailingTheBatch() throws Exception {
        deindexer.onBatch(List.of(envelope("m-1", "CHAT", "DISPOSITION"), "{\"payload\":{}}"));

        assertThat(port.deleted).containsExactly("m-1");
    }

    @Test
    void emptyOrNullBatchIsANoOp() {
        deindexer.onBatch(List.of());
        deindexer.onBatch(null);
        assertThat(port.deleted).isEmpty();
    }

    private String envelope(String messageId, String commType, String reason) throws Exception {
        EventEnvelope<MessageDeletedEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID(), "MESSAGE_DELETED", 1, Instant.now(), messageId, "", "system",
                new MessageDeletedEvent(messageId, commType, reason));
        return mapper.writeValueAsString(envelope);
    }

    /** Fake {@link SearchPort}: only {@code deleteByIds} is exercised here. */
    private static final class RecordingSearchPort implements SearchPort {
        final List<String> deleted = new ArrayList<>();

        @Override public SearchResult search(QuerySpec spec) { throw new UnsupportedOperationException(); }
        @Override public long count(QuerySpec spec) { throw new UnsupportedOperationException(); }
        @Override public List<String> scopeIds(QuerySpec spec) { throw new UnsupportedOperationException(); }
        @Override public FacetResult facets(QuerySpec spec) { throw new UnsupportedOperationException(); }
        @Override public void index(IndexedMessage message) { throw new UnsupportedOperationException(); }
        @Override public void indexBatch(List<IndexedMessage> messages) { throw new UnsupportedOperationException(); }
        @Override public void applyHold(String holdId, List<String> messageIds, boolean onHold) { throw new UnsupportedOperationException(); }
        @Override public void deleteByIds(List<String> messageIds) { deleted.addAll(messageIds); }
        @Override public void dropAndRecreateIndex() { throw new UnsupportedOperationException(); }
    }
}
