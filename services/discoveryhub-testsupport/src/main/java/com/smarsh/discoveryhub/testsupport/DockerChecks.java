package com.smarsh.discoveryhub.testsupport;

import org.testcontainers.DockerClientFactory;

/**
 * Shared guard so that integration tests skip (instead of fail) when Docker is
 * unavailable — e.g. on a CI runner without a Docker daemon. Mirrors the
 * assumption used by {@code CaseSchemaIT}.
 */
public final class DockerChecks {
    private DockerChecks() {}

    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
