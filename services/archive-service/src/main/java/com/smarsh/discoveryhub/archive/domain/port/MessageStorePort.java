package com.smarsh.discoveryhub.archive.domain.port;

import com.smarsh.discoveryhub.events.CommunicationEvent;

import java.util.stream.Stream;

/**
 * Outbound port for durably storing an ingested message in Mongo (FR-1.5).
 * The adapter does an idempotent {@code $setOnInsert} upsert on
 * {@code {_id: messageId}} with a unique index on {@code messageKey}, so
 * at-least-once Kafka delivery is effectively-once (architecture §2.1).
 *
 * <p>Mongo is the system of record for messages. {@link #streamAll()} streams
 * every stored message back as a {@link CommunicationEvent} so the
 * {@code comm.stored.v1} event stream can be regenerated from the source of
 * truth — the concrete proof that ES (and even Kafka) is a rebuildable
 * projection, never the source of truth (architecture §3.4, Day-3 M1).
 */
public interface MessageStorePort {

    /** Returns true if a new document was inserted, false if it already existed. */
    boolean upsert(CommunicationEvent event);

    /**
     * Streams every stored message back as a {@link CommunicationEvent}, in a
     * server-side cursor so the full corpus is never materialised in memory.
     * The caller <em>must</em> close the returned stream (try-with-resources).
     *
     * @return a closeable stream of all stored messages
     */
    Stream<CommunicationEvent> streamAll();
}
