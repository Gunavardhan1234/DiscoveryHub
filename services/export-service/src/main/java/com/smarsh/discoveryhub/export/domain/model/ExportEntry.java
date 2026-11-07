package com.smarsh.discoveryhub.export.domain.model;

/** One file destined for the export package: a path inside the zip and its bytes. */
public record ExportEntry(String path, byte[] content) {}
