package com.smarsh.discoveryhub.ingest.application;

import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.ingest.domain.model.BrokerUnavailableException;
import com.smarsh.discoveryhub.ingest.domain.model.MessageId;
import com.smarsh.discoveryhub.ingest.domain.model.MessageKey;
import com.smarsh.discoveryhub.ingest.domain.model.Participant;
import com.smarsh.discoveryhub.ingest.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.ingest.domain.port.ClaimCheckPort;
import com.smarsh.discoveryhub.ingest.domain.port.EventPublisher;
import com.smarsh.discoveryhub.ingest.domain.port.IngestDedupePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestMessageUseCaseTest {

    private static final Instant SENT_AT = Instant.parse("2026-03-14T09:12:00Z");
    private static final List<Participant> PARTICIPANTS = List.of(
            new Participant("Ada Byron", "ada@fict.example", "FROM"),
            new Participant("Charlie Babbage", "charlie@fict.example", "TO"));

    private final IngestDedupePort dedupePort = mock(IngestDedupePort.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);
    private final ClaimCheckPort claimCheckPort = mock(ClaimCheckPort.class);
    private final IngestMessageUseCase useCase =
            new IngestMessageUseCase(dedupePort, publisher, auditPublisher, claimCheckPort, "discoveryhub");

    private IngestMessageCommand command(String externalId, String body) {
        return new IngestMessageCommand(externalId, "EMAIL", "subject", body,
                "th-1", null, "c-0007", PARTICIPANTS, SENT_AT, List.of());
    }

    @Test
    void publishesAndReturnsAcceptedForNewMessage() {
        MessageKey key = MessageKey.of("discoveryhub", "ext-1", "body", PARTICIPANTS, SENT_AT);
        UUID messageId = MessageId.of(key).value();
        when(dedupePort.dedupe(eq(key.value()), eq(messageId), eq("ext-1")))
                .thenReturn(new IngestDedupePort.DedupeResult(messageId, true));

        IngestResult result = useCase.execute(command("ext-1", "body"));

        assertThat(result.status()).isEqualTo(IngestResult.Status.ACCEPTED);
        assertThat(result.messageId()).isEqualTo(messageId.toString());

        ArgumentCaptor<CommunicationEvent> eventCaptor = ArgumentCaptor.forClass(CommunicationEvent.class);
        verify(publisher).publishIngested(eventCaptor.capture(), eq(key.value()));
        CommunicationEvent event = eventCaptor.getValue();
        assertThat(event.messageId()).isEqualTo(messageId.toString());
        assertThat(event.messageKey()).isEqualTo(key.value());
        assertThat(event.externalMessageId()).isEqualTo("ext-1");
        assertThat(event.hasAttachment()).isFalse();
    }

    @Test
    void doesNotRepublishOnDuplicate() {
        MessageKey key = MessageKey.of("discoveryhub", "ext-1", "body", PARTICIPANTS, SENT_AT);
        UUID existing = MessageId.of(key).value();
        when(dedupePort.dedupe(eq(key.value()), any(), eq("ext-1")))
                .thenReturn(new IngestDedupePort.DedupeResult(existing, false));

        IngestResult result = useCase.execute(command("ext-1", "body"));

        assertThat(result.status()).isEqualTo(IngestResult.Status.DUPLICATE);
        assertThat(result.messageId()).isEqualTo(existing.toString());
        verify(publisher, never()).publishIngested(any(), any());
    }

    @Test
    void wrapsBrokerFailureAsBrokerUnavailable() {
        MessageKey key = MessageKey.of("discoveryhub", "ext-1", "body", PARTICIPANTS, SENT_AT);
        UUID messageId = MessageId.of(key).value();
        when(dedupePort.dedupe(any(), any(), any())).thenReturn(new IngestDedupePort.DedupeResult(messageId, true));
        doThrow(new RuntimeException("kafka down")).when(publisher).publishIngested(any(), any());

        assertThatThrownBy(() -> useCase.execute(command("ext-1", "body")))
                .isInstanceOf(BrokerUnavailableException.class)
                .hasMessageContaining("Failed to publish");
    }

    @Test
    void largeAttachmentIsExternalizedAndOnlyTheClaimCheckIsPublished() {
        MessageKey key = MessageKey.of("discoveryhub", "ext-att", "body", PARTICIPANTS, SENT_AT);
        UUID messageId = MessageId.of(key).value();
        when(dedupePort.dedupe(eq(key.value()), eq(messageId), eq("ext-att")))
                .thenReturn(new IngestDedupePort.DedupeResult(messageId, true));
        when(claimCheckPort.externalize(eq("a-1"), eq("big.pdf"), eq("application/pdf"), eq(2_000_000L), eq("YmFy")))
                .thenReturn(new ClaimCheckPort.ClaimCheck("attachments/a-1/abc-big.pdf", "sha256:def"));

        IngestMessageCommand.AttachmentRef big =
                new IngestMessageCommand.AttachmentRef("a-1", "big.pdf", "application/pdf", 2_000_000L, "YmFy");
        IngestMessageCommand cmd = new IngestMessageCommand("ext-att", "EMAIL", "subject", "body",
                "th-1", null, "c-0007", PARTICIPANTS, SENT_AT, List.of(big));

        useCase.execute(cmd);

        ArgumentCaptor<CommunicationEvent> captor = ArgumentCaptor.forClass(CommunicationEvent.class);
        verify(publisher).publishIngested(captor.capture(), eq(key.value()));
        CommunicationEvent event = captor.getValue();
        assertThat(event.hasAttachment()).isTrue();
        assertThat(event.attachments()).hasSize(1);
        CommunicationEvent.Attachment published = event.attachments().getFirst();
        assertThat(published.s3Key()).isEqualTo("attachments/a-1/abc-big.pdf");
        assertThat(published.sha256()).isEqualTo("sha256:def");
        assertThat(published.size()).isEqualTo(2_000_000L);
    }

    @Test
    void smallAttachmentWithoutContentStaysInlineWithNoClaimCheck() {
        MessageKey key = MessageKey.of("discoveryhub", "ext-sm", "body", PARTICIPANTS, SENT_AT);
        UUID messageId = MessageId.of(key).value();
        when(dedupePort.dedupe(eq(key.value()), eq(messageId), eq("ext-sm")))
                .thenReturn(new IngestDedupePort.DedupeResult(messageId, true));
        when(claimCheckPort.externalize(eq("a-2"), eq("small.txt"), eq("text/plain"), eq(12L), eq(null)))
                .thenReturn(ClaimCheckPort.ClaimCheck.empty());

        IngestMessageCommand.AttachmentRef small =
                new IngestMessageCommand.AttachmentRef("a-2", "small.txt", "text/plain", 12L, null);
        IngestMessageCommand cmd = new IngestMessageCommand("ext-sm", "EMAIL", "subject", "body",
                "th-1", null, "c-0007", PARTICIPANTS, SENT_AT, List.of(small));

        useCase.execute(cmd);

        ArgumentCaptor<CommunicationEvent> captor = ArgumentCaptor.forClass(CommunicationEvent.class);
        verify(publisher).publishIngested(captor.capture(), eq(key.value()));
        CommunicationEvent.Attachment published = captor.getValue().attachments().getFirst();
        assertThat(published.s3Key()).isNull();
        assertThat(published.sha256()).isNull();
        verify(claimCheckPort).externalize("a-2", "small.txt", "text/plain", 12L, null);
    }
}
