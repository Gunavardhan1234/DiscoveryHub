package com.smarsh.discoveryhub.search.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.search.domain.model.IndexedMessage;
import com.smarsh.discoveryhub.search.domain.service.SearchService;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Consumes {@code comm.stored.v1} in the {@code search-indexer} consumer group and
 * upserts projections into Elasticsearch via a batch {@code BulkRequest}. Own consumer
 * group, independent of the durability (archive) consumer, so the index can be rebuilt
 * by replaying the topic without disturbing the source-of-truth write path.
 *
 * <p>The listener is batch-mode: Spring Kafka delivers a list of records per poll,
 * which are accumulated and flushed in a single ES {@code BulkRequest} (architecture
 * §search-freshness: "flushed on 500 docs or 2 s"). Indexing is idempotent: a
 * redelivered event upserts the same doc by {@code messageId}, so retries never
 * duplicate.
 *
 * <p>{@code comm.stored.v1} is the production-correct, compacted source — replaying
 * it from offset 0 rebuilds the full ES index (see {@code POST /admin/reindex}).
 *
 * <p><b>Design pattern — Observer:</b> this is the Observer pattern at
 * infrastructure scale. archive-service (the subject) publishes
 * {@code comm.stored.v1} and has no knowledge of its observers; this indexer
 * subscribes and reacts. Adding another observer of the same event — as
 * {@link HoldIndexer} and {@link MessageDeindexer} do for their topics —
 * requires zero change to the publisher.
 *
 * <p><b>SOLID — SRP:</b> this class does exactly one thing: get events off the
 * topic and turn them into projections. It holds no ES code (that is behind
 * {@code SearchPort}) and no query logic. Deserialization, validation and
 * mapping are all it owns.
 *
 * <p><b>SOLID — DIP + testability:</b> it depends on {@code SearchService}, and
 * the event→projection mapping is deliberately extracted as the
 * <em>pure static function</em> {@link #toIndexedMessage(CommunicationEvent)}.
 * That extraction is what lets {@code MessageIndexerTest} verify the mapping
 * with no Kafka, no ES and no Spring, and it is reused verbatim by
 * {@code KafkaReindexPort} so a rebuilt index cannot drift from a live one.
 *
 * <p><b>Resiliency (NFR-2) — idempotency and poison pills:</b> indexing upserts
 * by {@code messageId}, so at-least-once redelivery is harmless — this is what
 * makes the "stop search-service, keep ingesting, restart" proof
 * ({@code scripts/resiliency-ingest.sh}) safe rather than duplicate-producing.
 * An unparseable payload throws {@code SerializationException}, which
 * {@code SearchKafkaConfig} registers as non-retryable so it short-circuits
 * straight to {@code comm.stored.v1.DLT} instead of blocking the partition
 * forever. A merely malformed-but-parseable event is logged and skipped so one
 * bad record cannot fail an otherwise good batch.
 */
@Component
public class MessageIndexer {

    private static final Logger log = LoggerFactory.getLogger(MessageIndexer.class);
    private static final TypeReference<EventEnvelope<CommunicationEvent>> EVENT_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final SearchService searchService;

    public MessageIndexer(ObjectMapper mapper, SearchService searchService) {
        this.mapper = mapper;
        this.searchService = searchService;
    }

    /**
     * Batch listener — receives all records from a single poll and bulk-indexes them.
     * The container factory is configured for batch mode in {@code SearchKafkaConfig}.
     */
    @KafkaListener(topics = Topics.COMM_STORED_V1, groupId = "${discoveryhub.kafka.search-group:search-indexer}",
            containerFactory = "searchKafkaListenerContainerFactory", batch = "true")
    public void onBatch(List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<IndexedMessage> batch = new ArrayList<>(values.size());
        for (String value : values) {
            try {
                EventEnvelope<CommunicationEvent> envelope = mapper.readValue(value, EVENT_TYPE);
                CommunicationEvent event = envelope.payload();
                if (event == null || event.messageId() == null) {
                    log.warn("Ignoring malformed stored event (eventId={})", envelope.eventId());
                    continue;
                }
                batch.add(toIndexedMessage(event));
            } catch (JsonProcessingException e) {
                // Poison pill → do not retry; route straight to the DLT.
                throw new SerializationException("Cannot parse comm.stored.v1 payload", e);
            }
        }
        if (!batch.isEmpty()) {
            searchService.indexBatch(batch);
            log.debug("Batch-indexed {} message(s) from {}", batch.size(), Topics.COMM_STORED_V1);
        }
    }

    /**
     * Single-record processing — kept for the integration test which drives the
     * consumer manually. Production traffic goes through {@link #onBatch(List)}.
     */
    public void onMessage(String value) {
        EventEnvelope<CommunicationEvent> envelope;
        try {
            envelope = mapper.readValue(value, EVENT_TYPE);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Cannot parse comm.stored.v1 payload", e);
        }
        CommunicationEvent event = envelope.payload();
        if (event == null || event.messageId() == null) {
            log.warn("Ignoring malformed stored event (eventId={})", envelope.eventId());
            return;
        }
        IndexedMessage doc = toIndexedMessage(event);
        searchService.index(doc);
        log.debug("Indexed message {} from {}", doc.messageId(), Topics.COMM_STORED_V1);
    }

    /** Pure event → projection mapping, extracted for unit testing. */
    public static IndexedMessage toIndexedMessage(CommunicationEvent e) {
        String timestamp = e.sentAt() == null ? null : e.sentAt().toString();
        String participantsText = e.participants() == null ? "" : e.participants().stream()
                .map(p -> p.name() + " " + p.address())
                .reduce("", (a, b) -> a.isBlank() ? b : a + " " + b);
        boolean hasAttachment = e.hasAttachment() || (e.attachments() != null && !e.attachments().isEmpty());

        List<IndexedMessage.Participant> participants = e.participants() == null ? List.of()
                : e.participants().stream()
                .map(p -> new IndexedMessage.Participant(p.name(), p.address(), p.role()))
                .toList();

        List<IndexedMessage.Attachment> attachments = e.attachments() == null ? List.of()
                : e.attachments().stream()
                .map(a -> new IndexedMessage.Attachment(a.filename(), a.contentType(), a.size()))
                .toList();

        return new IndexedMessage(
                e.messageId(),
                e.type(),
                e.subject(),
                e.body(),
                timestamp,
                e.custodianId(),
                e.threadId(),
                participantsText,
                participants,
                attachments,
                hasAttachment,
                false,
                List.of());
    }
}
