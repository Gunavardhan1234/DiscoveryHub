package com.smarsh.discoveryhub.generator.model;

import java.time.Instant;
import java.util.List;

/**
 * Request body for {@code POST /messages/ingest} (api-contracts &sect;ingest),
 * as serialized by the corpus generator. Attachment bytes are not carried —
 * the ingest request carries only the reference metadata.
 */
public record IngestRequest(
        String externalMessageId,
        String type,
        String subject,
        String body,
        String threadId,
        String inReplyTo,
        String custodian,
        List<Participant> participants,
        Instant sentAt,
        List<Attachment> attachments) {

    public record Participant(String name, String address, String role) {}

    public record Attachment(String id, String filename, String contentType, long size) {}
}
