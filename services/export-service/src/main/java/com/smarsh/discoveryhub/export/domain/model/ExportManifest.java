package com.smarsh.discoveryhub.export.domain.model;

import java.time.Instant;
import java.util.List;

public record ExportManifest(PackageInfo packageInfo, List<Item> items) {
    public record PackageInfo(String id, String caseId, Instant generatedAt, int itemCount, String packageChecksum) {}
    public record Item(String path, long size, String sha256) {}
}
