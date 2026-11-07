package com.smarsh.discoveryhub.events;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeTest {
    @Test
    void suppliesSafeDefaults() {
        EventEnvelope<String> envelope = new EventEnvelope<>(null, null, 0, null, null, "c", null, "payload");
        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.occurredAt()).isNotNull();
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.actor()).isEqualTo("system");
        assertThat(envelope.correlationId()).isEqualTo("c");
    }
}
