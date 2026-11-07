package com.smarsh.discoveryhub.ingest.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MessageIdTest {

    @Test
    void isUuidV5WithIetfVariant() {
        MessageKey key = MessageKey.of("discoveryhub", "ext-1", "body",
                java.util.List.of(new Participant("A", "a@x", "FROM")),
                java.time.Instant.parse("2026-03-14T09:12:00Z"));
        UUID id = MessageId.of(key).value();

        assertThat(id.version()).isEqualTo(5);
        assertThat(id.variant()).isEqualTo(2); // IETF RFC 4122 variant
    }

    @Test
    void isDeterministicForSameKey() {
        MessageKey key = MessageKey.of("discoveryhub", "ext-1", "body",
                java.util.List.of(new Participant("A", "a@x", "FROM")),
                java.time.Instant.parse("2026-03-14T09:12:00Z"));
        UUID a = MessageId.of(key).value();
        UUID b = MessageId.of(key).value();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differsAcrossNamespaces() {
        MessageKey key = MessageKey.of("discoveryhub", "ext-1", "body",
                java.util.List.of(new Participant("A", "a@x", "FROM")),
                java.time.Instant.parse("2026-03-14T09:12:00Z"));
        UUID withNs = MessageId.of(MessageId.NAMESPACE, key.value()).value();
        UUID otherNs = MessageId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"), key.value()).value();
        assertThat(withNs).isNotEqualTo(otherNs);
    }
}
