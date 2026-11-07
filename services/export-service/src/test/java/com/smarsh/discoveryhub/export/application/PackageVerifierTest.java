package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.export.domain.model.ExportEntry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-6 verification: a genuine package PASSes; tamper with any item (or the
 * manifest itself) and it FAILs — the Day-2 gate's `verify-export` demo, now
 * server-side.
 */
class PackageVerifierTest {

    private final PackageVerifier verifier = new PackageVerifier();

    private byte[] sampleZip() {
        return new ZipExportPackager(new ManifestBuilder()).buildPackage("job-1", "case-42",
                List.of(
                        new ExportEntry("messages/m-1.json", "{\"id\":\"m-1\"}".getBytes(StandardCharsets.UTF_8)),
                        new ExportEntry("attachments/m-1/a.txt", "hello".getBytes(StandardCharsets.UTF_8))),
                null).bytes();
    }

    @Test
    void genuinePackagePasses() {
        PackageVerifier.VerificationResult result = verifier.verify(sampleZip());
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).contains("2 item");
    }

    @Test
    void tamperedItemFailsWithShaMismatch() {
        byte[] tampered = rezipWith(sampleZip(), "attachments/m-1/a.txt",
                "hellp".getBytes(StandardCharsets.UTF_8));
        PackageVerifier.VerificationResult result = verifier.verify(tampered);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("attachments/m-1/a.txt");
    }

    @Test
    void tamperedManifestFailsPackageChecksum() {
        Map<String, byte[]> entries = unzip(sampleZip());
        String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8)
                .replace("case-42", "case-43");
        byte[] tampered = rezipWith(sampleZip(), "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(tampered).valid()).isFalse();
    }

    @Test
    void extraSmuggledItemFails() {
        byte[] tampered = rezipWith(sampleZip(), "messages/m-99.json",
                "{\"id\":\"m-99\"}".getBytes(StandardCharsets.UTF_8));
        PackageVerifier.VerificationResult result = verifier.verify(tampered);
        assertThat(result.valid()).isFalse();
    }

    @Test
    void garbageBytesFail() {
        byte[] notAPackage = "not a zip at all".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(notAPackage).valid()).isFalse();
    }

    /** Rebuilds the zip, replacing (or adding) one entry's bytes without touching the others. */
    private static byte[] rezipWith(byte[] zipBytes, String path, byte[] newContent) {
        Map<String, byte[]> entries = unzip(zipBytes);
        entries.put(path, newContent);
        try {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(sink)) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            return sink.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, byte[]> unzip(byte[] zipBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zip.transferTo(out);
                entries.put(entry.getName(), out.toByteArray());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }
}
