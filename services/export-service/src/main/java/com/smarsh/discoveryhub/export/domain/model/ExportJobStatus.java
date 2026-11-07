package com.smarsh.discoveryhub.export.domain.model;

/**
 * Export job lifecycle states (events.md §5):
 * QUEUED → RUNNING → COMPLETED or FAILED.
 */
public enum ExportJobStatus { QUEUED, RUNNING, COMPLETED, FAILED }
