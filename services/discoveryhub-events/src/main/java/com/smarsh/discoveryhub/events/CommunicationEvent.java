package com.smarsh.discoveryhub.events;

import java.time.Instant;
import java.util.List;

public record CommunicationEvent(
        String messageId,
        String messageKey,
        String externalMessageId,
        String type,
        String subject,
        String body,
        String threadId,
        String inReplyTo,
        String custodianId,
        List<Participant> participants,
        Instant sentAt,
        List<Attachment> attachments,
        boolean hasAttachment) {

    public CommunicationEvent {
        participants = participants == null ? List.of() : List.copyOf(participants);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public record Participant(String name, String address, String role) {}

    public record Attachment(String id, String filename, String contentType, long size, String sha256, String s3Key) {}
}
