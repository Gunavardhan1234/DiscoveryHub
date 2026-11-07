package com.smarsh.discoveryhub.ingest.application;

/**
 * Outcome of ingesting one message. {@link Status#ACCEPTED} maps to HTTP 202
 * (published to Kafka); {@link Status#DUPLICATE} maps to HTTP 200 (already
 * ingested, existing id returned, never re-published).
 */
public record IngestResult(String messageId, Status status) {

    public enum Status { ACCEPTED, DUPLICATE }
}
