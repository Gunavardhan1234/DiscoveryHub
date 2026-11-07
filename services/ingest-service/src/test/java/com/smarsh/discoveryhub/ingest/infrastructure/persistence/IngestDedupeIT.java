package com.smarsh.discoveryhub.ingest.infrastructure.persistence;

import com.smarsh.discoveryhub.ingest.domain.port.IngestDedupePort;
import com.smarsh.discoveryhub.testsupport.PostgresTestBase;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Flyway migration applies and the {@code INSERT ... ON CONFLICT DO
 * NOTHING} dedupe is effectively-once: a second insert of the same
 * {@code message_key} returns the existing {@code messageId} with
 * {@code created=false}. Skipped when Docker is unavailable.
 */
class IngestDedupeIT extends PostgresTestBase {

    private final IngestDedupeAdapter adapter = new IngestDedupeAdapter(jdbc());

    @Test
    void newKeyIsCreatedAndDuplicateReturnsExisting() {
        String messageKey = "sha256:test-key-" + UUID.randomUUID();
        UUID firstId = UUID.randomUUID();

        IngestDedupePort.DedupeResult first = adapter.dedupe(messageKey, firstId, "ext-1");
        assertThat(first.created()).isTrue();
        assertThat(first.messageId()).isEqualTo(firstId);

        // A retry/redelivery with the same key — even with a different proposed id —
        // must return the existing id and never create a second row.
        IngestDedupePort.DedupeResult second = adapter.dedupe(messageKey, UUID.randomUUID(), "ext-1");
        assertThat(second.created()).isFalse();
        assertThat(second.messageId()).isEqualTo(firstId);
    }

    @Test
    void distinctKeysAreBothCreated() {
        IngestDedupePort.DedupeResult a = adapter.dedupe("sha256:distinct-a", UUID.randomUUID(), "ext-a");
        IngestDedupePort.DedupeResult b = adapter.dedupe("sha256:distinct-b", UUID.randomUUID(), "ext-b");
        assertThat(a.created()).isTrue();
        assertThat(b.created()).isTrue();
        assertThat(a.messageId()).isNotEqualTo(b.messageId());
    }
}
