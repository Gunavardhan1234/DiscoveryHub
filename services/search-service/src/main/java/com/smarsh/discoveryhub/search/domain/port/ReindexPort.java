package com.smarsh.discoveryhub.search.domain.port;

/**
 * Outbound port that rebuilds the ES index by replaying the durable event log
 * ({@code comm.stored.v1}) from offset 0. ES is a projection, never the source
 * of truth — this port is what makes that claim testable (architecture §3.4).
 *
 * <p><b>SOLID — ISP done properly:</b> one method, one reason to exist. The
 * rebuild capability is kept out of {@link SearchPort} precisely so that
 * {@code SearchService} can depend on "I can replay the log" without also
 * depending on the Kafka consumer API, and so a test can supply a one-line
 * lambda ({@code () -> 7L}) instead of stubbing a whole interface.
 *
 * <p><b>SOLID — DIP:</b> the implementation
 * ({@code infrastructure/kafka/KafkaReindexPort}) lives outside the domain, so
 * the rebuild <em>policy</em> ("drop the index, then replay from offset 0",
 * in {@code SearchService#reindex}) is separated from the rebuild
 * <em>mechanism</em> (a fresh Kafka consumer group seeking to the beginning).
 */
public interface ReindexPort {

    /**
     * Replays the durable event log from offset 0 and re-indexes every event.
     *
     * @return number of documents re-indexed
     */
    long replayFromBeginning();
}
