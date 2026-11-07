package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.export.domain.model.ExportEntry;
import com.smarsh.discoveryhub.export.domain.model.ExportManifest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Template Method contract: deterministic entry order, per-item SHA-256
 * computed through the DigestOutputStream decorator (must equal an
 * independently computed hash), manifest last and consistent.
 */
class ZipExportPackagerTest {

    private final ManifestBuilder manifestBuilder = new ManifestBuilder();

    private AbstractExportPackager.PackageResult buildSample() {
        var packager = new ZipExportPackager(manifestBuilder);
        List<ExportEntry> entries = List.of(
                new ExportEntry("messages/m-2.json", "{\"id\":\"m-2\"}".getBytes(StandardCharsets.UTF_8)),
                new ExportEntry("messages/m-1.json", "{\"id\":\"m-1\"}".getBytes(StandardCharsets.UTF_8)),
                new ExportEntry("attachments/m-1/a.txt", "hello".getBytes(StandardCharsets.UTF_8)));
        return packager.buildPackage("job-1", "case-42", entries, null);
    }

    @Test
    void packagesEntriesInDeterministicOrderWithManifestLast() throws IOException {
        Map<String, byte[]> unzipped = unzip(buildSample().bytes());
        assertThat(unzipped.keySet()).containsExactly(
                "attachments/m-1/a.txt", "messages/m-1.json", "messages/m-2.json", "manifest.json");
    }

    @Test
    void digestDecoratorHashesMatchIndependentSha256() {
        AbstractExportPackager.PackageResult result = buildSample();
        ExportManifest.Item attachment = result.manifest().items().stream()
                .filter(item -> item.path().equals("attachments/m-1/a.txt"))
                .findFirst().orElseThrow();
        // sha256("hello") — same known vector as ManifestBuilderTest
        assertThat(attachment.sha256())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(attachment.size()).isEqualTo(5);
    }

    @Test
    void embeddedManifestMatchesReturnedManifestAndExcludesItself() throws IOException {
        AbstractExportPackager.PackageResult result = buildSample();
        Map<String, byte[]> unzipped = unzip(result.bytes());
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        ExportManifest embedded = mapper.readValue(unzipped.get("manifest.json"), ExportManifest.class);

        assertThat(embedded.items()).hasSize(3);
        assertThat(embedded.items()).extracting(ExportManifest.Item::path).doesNotContain("manifest.json");
        assertThat(embedded.packageInfo().packageChecksum())
                .isEqualTo(result.manifest().packageInfo().packageChecksum());
        assertThat(embedded.packageInfo().caseId()).isEqualTo("case-42");
        assertThat(embedded.packageInfo().itemCount()).isEqualTo(3);
    }

    @Test
    void reportsProgressPerEntry() {
        var packager = new ZipExportPackager(manifestBuilder);
        List<Integer> observed = new ArrayList<>();
        packager.buildPackage("job-1", "case-42",
                List.of(new ExportEntry("a", new byte[] {1}), new ExportEntry("b", new byte[] {2})),
                observed::add);
        assertThat(observed).containsExactly(1, 2);
    }

    private static Map<String, byte[]> unzip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zip.transferTo(out);
                entries.put(entry.getName(), out.toByteArray());
            }
        }
        return entries;
    }
}
