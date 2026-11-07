package com.smarsh.discoveryhub.testsupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;

/**
 * Base class for any test that needs a real S3-compatible endpoint. Subclasses
 * build their own AWS SDK {@code S3Client}/{@code S3Presigner} against
 * {@link #endpoint()} with the {@link #accessKey()}/{@link #secretKey()}
 * static credentials LocalStack accepts unconditionally — mirroring how
 * {@code S3StorageAdapter} is wired against LocalStack in
 * {@code docker-compose.yml}.
 *
 * <p>Skips the whole class when Docker is not running.</p>
 */
public abstract class LocalStackTestBase {

    protected static LocalStackContainer localstack;

    @BeforeAll
    static void startLocalStack() {
        Assumptions.assumeTrue(DockerChecks.dockerAvailable(),
                "Docker is unavailable; skipping LocalStack integration test");
        localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
                .withServices(LocalStackContainer.Service.S3);
        localstack.start();
    }

    @AfterAll
    static void stopLocalStack() {
        if (localstack != null) {
            localstack.stop();
        }
    }

    protected static URI endpoint() {
        return localstack.getEndpointOverride(LocalStackContainer.Service.S3);
    }

    protected static String region() {
        return localstack.getRegion();
    }

    protected static String accessKey() {
        return localstack.getAccessKey();
    }

    protected static String secretKey() {
        return localstack.getSecretKey();
    }
}
