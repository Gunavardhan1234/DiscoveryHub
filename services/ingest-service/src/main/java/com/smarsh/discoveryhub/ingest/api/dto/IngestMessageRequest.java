package com.smarsh.discoveryhub.ingest.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

/**
 * Request body for {@code POST /messages/ingest} (see {@code docs/api-contracts.md}).
 * The ingest endpoint only validates and publishes — it never writes to Mongo.
 */
public record IngestMessageRequest(
        @NotBlank String externalMessageId,
        @NotBlank @Pattern(regexp = "EMAIL|CHAT", message = "type must be EMAIL or CHAT") String type,
        String subject,
        @NotBlank String body,
        String threadId,
        String inReplyTo,
        @NotBlank String custodian,
        @NotEmpty List<@Valid ParticipantDto> participants,
        @NotNull Instant sentAt,
        List<@Valid AttachmentDto> attachments) {

    public IngestMessageRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
