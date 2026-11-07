package com.smarsh.discoveryhub.generator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashesTest {

    @Test
    void sha256HexMatchesKnownVector() {
        assertThat(Hashes.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256HexIsDeterministicAndHexEncoded() {
        assertThat(Hashes.sha256Hex("discoveryhub")).isEqualTo(Hashes.sha256Hex("discoveryhub"));
        assertThat(Hashes.sha256Hex("discoveryhub")).matches("[0-9a-f]{64}");
    }

    @Test
    void externalIdIsPrefixedTruncatedAndDeterministic() {
        String id = Hashes.externalId("c-0001", "thread-1", "2026-09-14T10:00:00Z", "body text");

        assertThat(id).startsWith("ext-").hasSize(20);
        assertThat(id).isEqualTo(Hashes.externalId("c-0001", "thread-1", "2026-09-14T10:00:00Z", "body text"));
        assertThat(id).isNotEqualTo(Hashes.externalId("c-0002", "thread-1", "2026-09-14T10:00:00Z", "body text"));
        assertThat(id).isNotEqualTo(Hashes.externalId("c-0001", "thread-1", "2026-09-14T10:00:00Z", "other body"));
    }
}
