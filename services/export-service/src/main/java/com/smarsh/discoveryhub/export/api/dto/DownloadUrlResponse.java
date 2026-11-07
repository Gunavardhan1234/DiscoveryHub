package com.smarsh.discoveryhub.export.api.dto;

import java.time.Instant;

/** Pre-signed S3 GET link (15-min TTL) for a COMPLETED export package. */
public record DownloadUrlResponse(String url, Instant expiresAt) {}
