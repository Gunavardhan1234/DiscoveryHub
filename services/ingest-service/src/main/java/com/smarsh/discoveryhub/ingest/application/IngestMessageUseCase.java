package com.smarsh.discoveryhub.ingest.application;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.ingest.domain.model.BrokerUnavailableException;
import com.smarsh.discoveryhub.ingest.domain.model.MessageId;
import com.smarsh.discoveryhub.ingest.domain.model.MessageKey;
import com.smarsh.discoveryhub.ingest.domain.model.Participant;
import com.smarsh.discoveryhub.ingest.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.ingest.domain.port.ClaimCheckPort;
import com.smarsh.discoveryhub.ingest.domain.port.EventPublisher;
import com.smarsh.discoveryhub.ingest.domain.port.IngestDedupePort;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Accepts a message, computes its deterministic identity, dedupes, and
 * publishes {@code comm.ingest.v1}. Never writes to Mongo — acceptance and
 * durability are deliberately separate components (FR-1.4, architecture
 * &sect;2.1).
 *
 * <p>Idempotency flow: compute {@link MessageKey}, attempt a dedupe insert of
 * {@code (messageKey, messageId)}; if the key already existed, answer
 * {@code DUPLICATE} without publishing. Only a freshly inserted row is
 * published, so a redelivered/retried ingest never produces a second event.
 */
public class IngestMessageUseCase {

    private final IngestDedupePort dedupePort;
    private final EventPublisher publisher;
    private final AuditEventPublisher auditPublisher;
    private final ClaimCheckPort claimCheckPort;
    private final String sourceSystem;

    public IngestMessageUseCase(IngestDedupePort dedupePort, EventPublisher publisher,
                                AuditEventPublisher auditPublisher, ClaimCheckPort claimCheckPort,
                                String sourceSystem) {
        this.dedupePort = dedupePort;
        this.publisher = publisher;
        this.auditPublisher = auditPublisher;
        this.claimCheckPort = claimCheckPort;
        this.sourceSystem = sourceSystem;
    }

    public IngestResult execute(IngestMessageCommand command) {
        MessageKey messageKey = MessageKey.of(
                sourceSystem,
                command.externalMessageId(),
                command.body(),
                command.participants(),
                command.sentAt());
        UUID messageId = MessageId.of(messageKey).value();

        IngestDedupePort.DedupeResult deduped = dedupePort.dedupe(messageKey.value(), messageId, command.externalMessageId());
        if (!deduped.created()) {
            return new IngestResult(deduped.messageId().toString(), IngestResult.Status.DUPLICATE);
        }

        CommunicationEvent event = toEvent(command, messageId, messageKey.value());
        try {
            publisher.publishIngested(event, messageKey.value());
        } catch (RuntimeException ex) {
            throw new BrokerUnavailableException("Failed to publish comm.ingest.v1 for " + messageId, ex);
        }
        emitAudit(messageId.toString(), command.custodianId());
        return new IngestResult(messageId.toString(), IngestResult.Status.ACCEPTED);
    }

    public List<IngestResult> executeBatch(List<IngestMessageCommand> commands) {
        return commands.stream().map(this::execute).toList();
    }

    private CommunicationEvent toEvent(IngestMessageCommand command, UUID messageId, String messageKeyValue) {
        List<CommunicationEvent.Participant> participants = command.participants().stream()
                .map(p -> new CommunicationEvent.Participant(p.name(), p.address(), p.role()))
                .toList();
        List<CommunicationEvent.Attachment> attachments = command.attachments().stream()
                .map(this::toAttachment)
                .toList();
        return new CommunicationEvent(
                messageId.toString(),
                messageKeyValue,
                command.externalMessageId(),
                command.type(),
                command.subject(),
                command.body(),
                command.threadId(),
                command.inReplyTo(),
                command.custodianId(),
                participants,
                command.sentAt(),
                attachments,
                !attachments.isEmpty());
    }

    private CommunicationEvent.Attachment toAttachment(IngestMessageCommand.AttachmentRef ref) {
        ClaimCheckPort.ClaimCheck check = claimCheckPort.externalize(
                ref.id(), ref.filename(), ref.contentType(), ref.size(), ref.contentBase64());
        return new CommunicationEvent.Attachment(
                ref.id(), ref.filename(), ref.contentType(), ref.size(), check.sha256(), check.s3Key());
    }

    private void emitAudit(String messageId, String custodianId) {
        var payload = new AuditEventPayload(
                UUID.randomUUID().toString(),
                Instant.now(),
                Actor.current(),
                "MESSAGE_INGESTED",
                "MESSAGE",
                messageId,
                custodianId,
                null,
                "{\"messageId\":\"" + messageId + "\"}",
                CorrelationId.current());
        auditPublisher.publish(payload, custodianId);
    }
}
