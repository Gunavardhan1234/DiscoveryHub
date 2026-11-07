package com.smarsh.discoveryhub.search.infrastructure.kafka;

import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.search.domain.model.IndexedMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the event -> ES projection mapping (no Kafka/ES required). */
class MessageIndexerTest {

    @Test
    void mapsIngestEventToSearchableProjection() {
        CommunicationEvent event = new CommunicationEvent(
                "msg-1", "sha256:abc", "ext-1", "EMAIL", "Q2 wire instructions",
                "Please confirm the wire routing", null, null, "c-0007",
                List.of(new CommunicationEvent.Participant("Ada Byron", "ada@fict.example", "FROM")),
                Instant.parse("2026-03-14T09:12:00Z"),
                List.of(new CommunicationEvent.Attachment("a-1", "instructions.pdf", "application/pdf", 184223, null, null)),
                true);

        IndexedMessage doc = MessageIndexer.toIndexedMessage(event);

        assertThat(doc.messageId()).isEqualTo("msg-1");
        assertThat(doc.type()).isEqualTo("EMAIL");
        assertThat(doc.custodianId()).isEqualTo("c-0007");
        assertThat(doc.timestamp()).isEqualTo("2026-03-14T09:12:00Z");
        assertThat(doc.participantsText()).contains("Ada Byron", "ada@fict.example");
        assertThat(doc.hasAttachment()).isTrue();
        assertThat(doc.onHold()).isFalse();
        assertThat(doc.holds()).isEmpty();
        assertThat(doc.participants()).hasSize(1);
        assertThat(doc.participants().get(0).name()).isEqualTo("Ada Byron");
        assertThat(doc.participants().get(0).address()).isEqualTo("ada@fict.example");
        assertThat(doc.participants().get(0).role()).isEqualTo("FROM");
        assertThat(doc.attachments()).hasSize(1);
        assertThat(doc.attachments().get(0).filename()).isEqualTo("instructions.pdf");
        assertThat(doc.attachments().get(0).contentType()).isEqualTo("application/pdf");
        assertThat(doc.attachments().get(0).size()).isEqualTo(184223L);
    }

    @Test
    void derivesHasAttachmentFromAttachments() {
        CommunicationEvent noAttachment = new CommunicationEvent(
                "msg-2", "k", "ext-2", "CHAT", "hi", "body", null, null, "c-1",
                List.of(), Instant.now(), List.of(), false);
        assertThat(MessageIndexer.toIndexedMessage(noAttachment).hasAttachment()).isFalse();
    }

    @Test
    void derivesHasAttachmentFromNonEmptyAttachmentList() {
        CommunicationEvent withAttachmentsButFlagFalse = new CommunicationEvent(
                "msg-3", "k", "ext-3", "EMAIL", "hi", "body", null, null, "c-1",
                List.of(), Instant.now(),
                List.of(new CommunicationEvent.Attachment("a-2", "report.xlsx", "application/vnd.ms-excel", 5000, null, null)),
                false);
        assertThat(MessageIndexer.toIndexedMessage(withAttachmentsButFlagFalse).hasAttachment()).isTrue();
    }

    @Test
    void emptyParticipantsAndAttachmentsProduceEmptyLists() {
        CommunicationEvent event = new CommunicationEvent(
                "msg-4", "k", "ext-4", "CHAT", "hi", "body", null, null, "c-1",
                null, Instant.now(), null, false);
        IndexedMessage doc = MessageIndexer.toIndexedMessage(event);
        assertThat(doc.participants()).isEmpty();
        assertThat(doc.attachments()).isEmpty();
        assertThat(doc.participantsText()).isEmpty();
    }
}
