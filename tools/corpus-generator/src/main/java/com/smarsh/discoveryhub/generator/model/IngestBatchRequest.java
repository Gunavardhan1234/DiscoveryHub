package com.smarsh.discoveryhub.generator.model;

import java.util.List;

/**
 * Wrapper for {@code POST /messages/ingest:batch}.
 */
public record IngestBatchRequest(List<IngestRequest> messages) {}
