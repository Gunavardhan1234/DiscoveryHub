package com.smarsh.discoveryhub.export.api.dto;

/** Result of server-side package verification (FR-6). */
public record VerifyResponse(String jobId, boolean valid, String message) {}
