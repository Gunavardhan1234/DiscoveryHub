package com.smarsh.discoveryhub.export.domain.model;

import java.util.List;

/**
 * A message fetched from archive-service for packaging: the canonical JSON
 * document (stored verbatim in the package as {@code messages/{id}.json}) plus
 * the attachment references whose bytes the worker pulls from the attachments
 * bucket via {@code BlobStore} (execution-plan M5 Day 2).
 */
public record ArchiveMessage(String id, byte[] contentJson, List<AttachmentRef> attachments) {

    public record AttachmentRef(String filename, String s3Key) {}
}
