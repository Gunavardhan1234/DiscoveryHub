package com.smarsh.discoveryhub.ingest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Attachment as received by {@code POST /messages/ingest}. {@code content} is
 * optional base64-encoded bytes; when present and the attachment exceeds the
 * claim-check threshold, the ingest service externalizes the bytes to S3 and
 * publishes only an {@code s3Key}+{@code sha256} reference in the event.
 */
public record AttachmentDto(
        @NotBlank String id,
        @NotBlank String filename,
        @NotBlank String contentType,
        @Positive long size,
        String content) {}
