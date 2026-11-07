package com.smarsh.discoveryhub.export.application;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ManifestBuilderTest {
    @Test void computesKnownItemChecksum() {
        var manifest = new ManifestBuilder().build("j-1", "case-1",
                List.of(new ManifestBuilder.Entry("a.txt", "hello".getBytes(StandardCharsets.UTF_8))));
        assertThat(manifest.items().getFirst().sha256()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }
    @Test void tamperingChangesPackageChecksum() {
        var builder = new ManifestBuilder();
        var original = builder.build("j-1", "case-1", List.of(new ManifestBuilder.Entry("a.txt", "hello".getBytes(StandardCharsets.UTF_8))));
        var tampered = builder.build("j-1", "case-1", List.of(new ManifestBuilder.Entry("a.txt", "tampered".getBytes(StandardCharsets.UTF_8))));
        assertThat(tampered.packageInfo().packageChecksum()).isNotEqualTo(original.packageInfo().packageChecksum());
    }
}
