package com.smarsh.discoveryhub.ingest.domain.port;

import java.util.UUID;

/**
 * Outbound port for the ingest dedupe table (the first of the two idempotency
 * guards in architecture &sect;2.1). The infrastructure adapter does an
 * {@code INSERT ... ON CONFLICT (message_key) DO NOTHING}; if the row already
 * existed the existing {@code messageId} is returned with {@code created=false}
 * so the use case can answer {@code 200 DUPLICATE} without re-publishing.
 */
public interface IngestDedupePort {

    DedupeResult dedupe(String messageKey, UUID messageId, String externalMessageId);

    record DedupeResult(UUID messageId, boolean created) {}
}
