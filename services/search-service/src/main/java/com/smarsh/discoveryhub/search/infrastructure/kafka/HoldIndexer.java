package com.smarsh.discoveryhub.search.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.HoldApplied;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.search.domain.service.SearchService;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumes {@code hold.applied.v1} and keeps the {@code onHold} flag (and the
 * reference-counted hold-id set) searchable in Elasticsearch.
 *
 * <p>Batch listener: multiple {@code hold.applied.v1} events per poll are coalesced
 * — all message ids for the same {@code (holdId, onHold)} pair are merged into a
 * single ES bulk update, so overlapping events from the same hold produce one
 * painless-script execution per message.
 *
 * <p>{@code HoldApplied.onHold()} is the resulting state: {@code true} when a hold
 * is placed, {@code false} when released. The ES update uses a painless script that
 * adds/removes the hold id from {@code holds[]} and recomputes {@code onHold}, so a
 * message covered by two overlapping holds stays protected when only one is released
 * (FR-4.5 / architecture §2.3).
 *
 * <p><b>Design pattern — Observer:</b> a second, independent observer of
 * archive-service's events. hold-service and archive-service know nothing
 * about search-service; this class simply subscribes to
 * {@code hold.applied.v1} and keeps the projection consistent. That decoupling
 * is why hold placement stays an asynchronous 202 (FR-4.3) — the publisher
 * never waits for the index to catch up.
 *
 * <p><b>SOLID — SRP:</b> one responsibility — keep hold state searchable. The
 * hold <em>decision</em> belongs to hold-service and the authoritative hold
 * <em>set</em> lives in Mongo; this class only mirrors the outcome into ES.
 *
 * <p><b>Correctness note worth being able to defend (FR-4.5):</b> the events
 * carry the resulting state ({@code onHold} true on place, false on release)
 * and are applied through a reference-counting painless script, never a
 * boolean overwrite. Releasing one of two overlapping holds therefore leaves
 * the message still {@code onHold} in search results.
 *
 * <p><b>Efficiency — coalescing:</b> a poll may contain many events for the
 * same hold; they are merged per {@code (holdId, onHold)} pair so each message
 * takes one script execution rather than one per event. This is why a
 * corpus-wide hold does not melt the index.
 *
 * <p><b>Safety caveat:</b> ES is only a projection, so a stale {@code onHold}
 * flag here can never cause a wrong deletion — deletion protection is enforced
 * in Mongo by archive-service's {@code RetentionGuard}, the authoritative
 * store. That separation is deliberate and is the right answer to
 * "what if the index is out of date?"
 */
@Component
public class HoldIndexer {

    private static final Logger log = LoggerFactory.getLogger(HoldIndexer.class);
    private static final TypeReference<EventEnvelope<HoldApplied>> EVENT_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final SearchService searchService;

    public HoldIndexer(ObjectMapper mapper, SearchService searchService) {
        this.mapper = mapper;
        this.searchService = searchService;
    }

    @KafkaListener(topics = Topics.HOLD_APPLIED_V1, groupId = "${discoveryhub.kafka.search-group:search-indexer}",
            containerFactory = "searchKafkaListenerContainerFactory", batch = "true")
    public void onBatch(List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        // Coalesce: (holdId, onHold) → merged message id list.
        Map<String, List<String>> applyMap = new HashMap<>();
        Map<String, List<String>> releaseMap = new HashMap<>();
        for (String value : values) {
            try {
                EventEnvelope<HoldApplied> envelope = mapper.readValue(value, EVENT_TYPE);
                HoldApplied hold = envelope.payload();
                if (hold == null || hold.holdId() == null) {
                    continue;
                }
                List<String> messageIds = hold.messageIds() == null ? List.of() : hold.messageIds();
                Map<String, List<String>> target = hold.onHold() ? applyMap : releaseMap;
                target.computeIfAbsent(hold.holdId(), k -> new ArrayList<>()).addAll(messageIds);
            } catch (JsonProcessingException e) {
                throw new SerializationException("Cannot parse hold.applied.v1 payload", e);
            }
        }
        applyMap.forEach((holdId, ids) -> searchService.applyHold(holdId, ids, true));
        releaseMap.forEach((holdId, ids) -> searchService.applyHold(holdId, ids, false));
        log.debug("Processed {} hold.applied.v1 event(s): {} apply, {} release",
                values.size(), applyMap.size(), releaseMap.size());
    }
}
