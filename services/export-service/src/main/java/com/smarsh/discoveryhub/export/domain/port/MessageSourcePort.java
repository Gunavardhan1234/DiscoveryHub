package com.smarsh.discoveryhub.export.domain.port;

import com.smarsh.discoveryhub.export.domain.model.ArchiveMessage;

import java.util.List;

/**
 * Outbound port fetching authoritative message documents from archive-service
 * ({@code GET /api/messages?ids=} — the durable source of truth). Attachment
 * bytes are NOT fetched here; the worker pulls them from the shared
 * attachments bucket via {@code BlobStore} using each {@code s3Key}.
 *
 * <p><b>SOLID — DIP:</b> the worker depends on this abstraction, never on
 * {@code RestClient} — the HTTP adapter is swappable (test double, gRPC stub,
 * cache) without touching the workflow.
 */
public interface MessageSourcePort {

    List<ArchiveMessage> fetchMessages(List<String> messageIds);
}
