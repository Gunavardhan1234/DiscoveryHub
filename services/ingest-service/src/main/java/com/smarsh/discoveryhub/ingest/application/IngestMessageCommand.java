package com.smarsh.discoveryhub.ingest.application;

import com.smarsh.discoveryhub.ingest.domain.model.Participant;

import java.time.Instant;
import java.util.List;

/**
 * Application-layer input for ingesting one message. The api layer maps its
 * request DTO to this; the use case never touches the HTTP shape.
 */
public record IngestMessageCommand(
        String externalMessageId,
        String type,
        String subject,
        String body,
        String threadId,
        String inReplyTo,
        String custodianId,
        List<Participant> participants,
        Instant sentAt,
        List<AttachmentRef> attachments) {

    public IngestMessageCommand {
        participants = participants == null ? List.of() : List.copyOf(participants);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** Attachment reference as received at ingest time; bytes/sha256/s3Key are
     *  resolved by the claim-check port before publishing. {@code contentBase64}
     *  is null for metadata-only attachments. */
    public record AttachmentRef(String id, String filename, String contentType, long size, String contentBase64) {}
}
