package com.smarsh.discoveryhub.search.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.MessageDeletedEvent;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.search.domain.service.SearchService;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Consumes {@code message.deleted.v1} and retracts the corresponding document
 * from Elasticsearch (FR-5/FR-1.7). Without this, a message deleted from
 * Mongo — whether via the manual hold-guarded endpoint or a disposition pass
 * — stays visible as a "ghost" hit in search results (and dashboard counts)
 * until the index is fully rebuilt, at which point replaying {@code
 * comm.stored.v1} from offset 0 would simply resurrect it, since that topic
 * never learns a message was deleted either.
 *
 * <p>Batch listener, same {@code search-indexer} consumer group and container
 * factory as {@link MessageIndexer}/{@link HoldIndexer} — one more topic in
 * the same group, coalesced into a single ES bulk delete per poll.
 *
 * <p><b>Design pattern — Observer:</b> the third observer in this service, and
 * a good illustration of why the pattern pays off here. Neither the manual
 * delete endpoint nor the scheduled disposition pass in archive-service knows
 * that a search index exists; they emit {@code message.deleted.v1} and this
 * class reacts. Supporting a new deletion source costs nothing on either side.
 *
 * <p><b>SOLID — SRP + OCP:</b> retraction is its own class rather than another
 * branch inside {@link MessageIndexer}. Indexing and de-indexing are separate
 * reasons to change, and the new behaviour was added by <em>adding</em> a
 * listener, not by modifying the existing one — Open/Closed in practice.
 *
 * <p><b>Why this class has to exist (the design insight):</b> deletion is the
 * one event a compacted log cannot express. Replaying {@code comm.stored.v1}
 * from offset 0 would happily resurrect a message that was legitimately
 * disposed of, because that topic never learns about the delete. So the
 * projection needs a dedicated deletion signal; without it, disposed messages
 * linger as "ghost" hits in search and inflate dashboard counts.
 */
@Component
public class MessageDeindexer {

    private static final Logger log = LoggerFactory.getLogger(MessageDeindexer.class);
    private static final TypeReference<EventEnvelope<MessageDeletedEvent>> EVENT_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final SearchService searchService;

    public MessageDeindexer(ObjectMapper mapper, SearchService searchService) {
        this.mapper = mapper;
        this.searchService = searchService;
    }

    @KafkaListener(topics = Topics.MESSAGE_DELETED_V1, groupId = "${discoveryhub.kafka.search-group:search-indexer}",
            containerFactory = "searchKafkaListenerContainerFactory", batch = "true")
    public void onBatch(List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> messageIds = new ArrayList<>(values.size());
        for (String value : values) {
            try {
                EventEnvelope<MessageDeletedEvent> envelope = mapper.readValue(value, EVENT_TYPE);
                MessageDeletedEvent event = envelope.payload();
                if (event == null || event.messageId() == null) {
                    log.warn("Ignoring malformed deleted event (eventId={})", envelope.eventId());
                    continue;
                }
                messageIds.add(event.messageId());
            } catch (JsonProcessingException e) {
                throw new SerializationException("Cannot parse message.deleted.v1 payload", e);
            }
        }
        if (!messageIds.isEmpty()) {
            searchService.deleteByIds(messageIds);
            log.debug("Retracted {} message(s) from ES via {}", messageIds.size(), Topics.MESSAGE_DELETED_V1);
        }
    }
}
