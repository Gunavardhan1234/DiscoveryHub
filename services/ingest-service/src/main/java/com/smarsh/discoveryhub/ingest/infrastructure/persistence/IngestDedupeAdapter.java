package com.smarsh.discoveryhub.ingest.infrastructure.persistence;

import com.smarsh.discoveryhub.ingest.domain.port.IngestDedupePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * First idempotency guard (architecture &sect;2.1): an {@code INSERT ... ON
 * CONFLICT (message_key) DO NOTHING}. A conflict means the message was already
 * accepted; the existing {@code messageId} is returned with {@code created=false}
 * so the use case answers {@code 200 DUPLICATE} and does not re-publish.
 */
@Component
public class IngestDedupeAdapter implements IngestDedupePort {

    private final JdbcTemplate jdbc;

    public IngestDedupeAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DedupeResult dedupe(String messageKey, UUID messageId, String externalMessageId) {
        List<UUID> inserted = jdbc.queryForList(
                "INSERT INTO ingest_dedupe (message_key, message_id, external_message_id) " +
                        "VALUES (?, ?, ?) " +
                        "ON CONFLICT (message_key) DO NOTHING RETURNING message_id",
                UUID.class, messageKey, messageId, externalMessageId);
        if (!inserted.isEmpty()) {
            return new DedupeResult(inserted.getFirst(), true);
        }
        UUID existing = jdbc.queryForObject(
                "SELECT message_id FROM ingest_dedupe WHERE message_key = ?",
                UUID.class, messageKey);
        return new DedupeResult(existing, false);
    }
}
