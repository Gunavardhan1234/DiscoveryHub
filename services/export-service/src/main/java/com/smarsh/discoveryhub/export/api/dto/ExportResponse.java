package com.smarsh.discoveryhub.export.api.dto;

import java.time.Instant;

public record ExportResponse(
        String jobId,
        String caseId,
        String holdId,
        String status,
        int progressPct,
        String packageS3Key,
        String error,
        Instant createdAt) {}
