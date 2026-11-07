package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.archive.domain.port.MessageStorePort;
import com.smarsh.discoveryhub.archive.domain.port.StoredEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.stream.Stream;

/**
 * Rebuilds the {@code comm.stored.v1} event stream from Mongo, the system of
 * record (architecture §3.4, Day-3 M1). Every stored message is streamed back
 * and republished, so the search-service indexer (and any other projection)
 * re-indexes from the source of truth — the concrete proof that ES is a
 * rebuildable projection, never the source of truth.
 *
 * <p>Republishing is idempotent downstream: the indexer upserts each projection
 * by {@code messageId}, and a fresh event for an existing doc simply overwrites
 * it. This path is stronger than replaying the Kafka topic alone: it rebuilds
 * even if the topic had retention gaps, because Mongo is the durable source.
 *
 * <p><b>SOLID — SRP + DIP:</b> "rebuild the projection" only — it composes
 * {@code MessageStorePort} (read everything) with {@code StoredEventPublisher}
 * (republish), so the rebuild policy stays independent of both Mongo and
 * Kafka specifics.
 */
@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    private final MessageStorePort messageStore;
    private final StoredEventPublisher publisher;

    public ReindexService(MessageStorePort messageStore, StoredEventPublisher publisher) {
        this.messageStore = messageStore;
        this.publisher = publisher;
    }

    /**
     * Stream every stored message and republish {@code comm.stored.v1}.
     *
     * @return the number of events republished
     */
    public long republishAll() {
        long count = 0;
        try (Stream<CommunicationEvent> messages = messageStore.streamAll()) {
            var it = messages.iterator();
            while (it.hasNext()) {
                CommunicationEvent event = it.next();
                if (event == null || event.messageId() == null) {
                    continue;
                }
                publisher.publishStored(event, event.messageId());
                count++;
            }
        }
        log.info("Reindex from Mongo: republished {} comm.stored.v1 events", count);
        return count;
    }
}
