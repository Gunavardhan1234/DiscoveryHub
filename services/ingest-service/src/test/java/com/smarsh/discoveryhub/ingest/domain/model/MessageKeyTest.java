package com.smarsh.discoveryhub.ingest.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageKeyTest {

    private static final Instant SENT_AT = Instant.parse("2026-03-14T09:12:00Z");
    private static final List<Participant> PARTICIPANTS = List.of(
            new Participant("Ada Byron", "ada@fict.example", "FROM"),
            new Participant("Charlie Babbage", "charlie@fict.example", "TO"));

    @Test
    void isDeterministicForSameInputs() {
        MessageKey a = MessageKey.of("discoveryhub", "ext-1", "body", PARTICIPANTS, SENT_AT);
        MessageKey b = MessageKey.of("discoveryhub", "ext-1", "body", PARTICIPANTS, SENT_AT);
        assertThat(a.value()).isEqualTo(b.value());
        assertThat(a.value()).startsWith("sha256:");
    }

    @Test
    void changesWhenBodyChanges() {
        MessageKey a = MessageKey.of("discoveryhub", "ext-1", "body-a", PARTICIPANTS, SENT_AT);
        MessageKey b = MessageKey.of("discoveryhub", "ext-1", "body-b", PARTICIPANTS, SENT_AT);
        assertThat(a.value()).isNotEqualTo(b.value());
    }

    @Test
    void isIndependentOfParticipantOrder() {
        List<Participant> reversed = List.of(PARTICIPANTS.get(1), PARTICIPANTS.get(0));
        MessageKey a = MessageKey.of("discoveryhub", "ext-1", "body", PARTICIPANTS, SENT_AT);
        MessageKey b = MessageKey.of("discoveryhub", "ext-1", "body", reversed, SENT_AT);
        assertThat(a.value()).isEqualTo(b.value());
    }

    @Test
    void changesWhenExternalIdOrTimestampChanges() {
        MessageKey base = MessageKey.of("discoveryhub", "ext-1", "body", PARTICIPANTS, SENT_AT);
        MessageKey diffId = MessageKey.of("discoveryhub", "ext-2", "body", PARTICIPANTS, SENT_AT);
        MessageKey diffTime = MessageKey.of("discoveryhub", "ext-1", "body", PARTICIPANTS, SENT_AT.plusSeconds(1));
        assertThat(base.value()).isNotEqualTo(diffId.value());
        assertThat(base.value()).isNotEqualTo(diffTime.value());
    }
}
