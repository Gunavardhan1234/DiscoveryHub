package com.smarsh.discoveryhub.export.domain.port;

import com.smarsh.discoveryhub.export.domain.model.ExportJob;

/**
 * Observer port (execution-plan M5 Day 3): the worker publishes every job
 * state change; the SSE adapter fans it out to subscribed
 * {@code GET /api/exports/{id}/stream} clients. Implementations must never
 * throw — progress display must not fail the packaging pipeline.
 */
public interface ExportProgressPublisher {

    void publish(ExportJob job);
}
