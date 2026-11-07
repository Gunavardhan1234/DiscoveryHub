package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.export.domain.model.ExportManifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the tamper-evident {@link ExportManifest} and the canonical
 * SHA-256 helpers shared with the verifier.
 *
 * <p><b>SOLID — SRP:</b> this class has exactly one reason to change — the
 * manifest's canonical form (what fields, what sort order, what separators).
 * Packaging mechanics live in {@code AbstractExportPackager}; verification
 * lives in {@code PackageVerifier}; both merely call into the hashing rules
 * defined here, so the digest contract has a single owner.
 */
public class ManifestBuilder {
    public ExportManifest build(String id, String caseId, List<Entry> entries) {
        List<ExportManifest.Item> items = entries.stream()
                .sorted(Comparator.comparing(Entry::path))
                .map(entry -> new ExportManifest.Item(entry.path(), entry.size(), sha256Hex(entry.content())))
                .toList();
        return buildFromItems(id, caseId, items);
    }

    /**
     * Builds the manifest from items whose SHA-256 was already computed while
     * streaming through the packager's {@code DigestOutputStream} decorator —
     * no second pass over the content. The package checksum covers the package
     * id, the case id, and the sorted canonical item list, so tampering with
     * any item (or add/remove) or the package identity changes it.
     */
    public ExportManifest buildFromItems(String id, String caseId, List<ExportManifest.Item> items) {
        List<ExportManifest.Item> sorted = items.stream()
                .sorted(Comparator.comparing(ExportManifest.Item::path))
                .toList();
        String canonical = sorted.stream()
                .map(item -> item.path() + "\n" + item.size() + "\n" + item.sha256() + "\n")
                .reduce(id + "\n" + caseId + "\n", String::concat);
        return new ExportManifest(
                new ExportManifest.PackageInfo(id, caseId, Instant.now(), sorted.size(),
                        sha256Hex(canonical.getBytes(StandardCharsets.UTF_8))),
                sorted);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append("%02x".formatted(value));
        return result.toString();
    }

    public record Entry(String path, byte[] content) {
        public long size() { return content.length; }
    }
}
