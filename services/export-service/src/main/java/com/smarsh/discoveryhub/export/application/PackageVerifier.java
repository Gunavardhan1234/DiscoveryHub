package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.export.domain.model.ExportManifest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Server-side package verification (FR-6, {@code POST /exports/{id}/verify}):
 * unzips the stored package, re-hashes every item, and compares against the
 * embedded {@code manifest.json} — item by item, then the package checksum
 * over the canonical item list. A single flipped byte anywhere fails it.
 */
public class PackageVerifier {

    private final ManifestBuilder manifestBuilder = new ManifestBuilder();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public record VerificationResult(boolean valid, String message) {
        static VerificationResult ok(int items) {
            return new VerificationResult(true, "All " + items + " item hashes and the package checksum match");
        }
        static VerificationResult fail(String message) {
            return new VerificationResult(false, message);
        }
    }

    public VerificationResult verify(byte[] packageBytes) {
        Map<String, byte[]> entries;
        try {
            entries = unzip(packageBytes);
        } catch (IOException e) {
            return VerificationResult.fail("Package is not a readable zip: " + e.getMessage());
        }
        byte[] manifestBytes = entries.remove(AbstractExportPackager.MANIFEST_PATH);
        if (manifestBytes == null) {
            return VerificationResult.fail("Package has no " + AbstractExportPackager.MANIFEST_PATH);
        }
        ExportManifest manifest;
        try {
            manifest = objectMapper.readValue(manifestBytes, ExportManifest.class);
        } catch (IOException e) {
            return VerificationResult.fail("Manifest is not parseable: " + e.getMessage());
        }
        if (manifest.items().size() != entries.size()) {
            return VerificationResult.fail("Manifest lists " + manifest.items().size()
                    + " items but the package contains " + entries.size());
        }
        for (ExportManifest.Item item : manifest.items()) {
            byte[] content = entries.get(item.path());
            if (content == null) {
                return VerificationResult.fail("Manifest item missing from package: " + item.path());
            }
            if (content.length != item.size()) {
                return VerificationResult.fail("Size mismatch for " + item.path());
            }
            if (!ManifestBuilder.sha256Hex(content).equals(item.sha256())) {
                return VerificationResult.fail("SHA-256 mismatch for " + item.path());
            }
        }
        String expectedChecksum = manifestBuilder
                .buildFromItems(manifest.packageInfo().id(), manifest.packageInfo().caseId(), manifest.items())
                .packageInfo().packageChecksum();
        if (!expectedChecksum.equals(manifest.packageInfo().packageChecksum())) {
            return VerificationResult.fail("Package checksum does not match the item list");
        }
        return VerificationResult.ok(manifest.items().size());
    }

    private static Map<String, byte[]> unzip(byte[] packageBytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zip.transferTo(out);
                entries.put(entry.getName(), out.toByteArray());
            }
        }
        return entries;
    }
}
