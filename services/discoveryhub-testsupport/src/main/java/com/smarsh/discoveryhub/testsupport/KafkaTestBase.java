package com.smarsh.discoveryhub.testsupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for any test that needs a real Kafka broker in KRaft mode (no
 * ZooKeeper). Subclasses use {@link #bootstrapServers()} to configure producers
 * and consumers.
 *
 * <p>Skips the whole class when Docker is not running.</p>
 */
public abstract class KafkaTestBase {

    protected static KafkaContainer kafka;

    @BeforeAll
    static void startKafka() {
        Assumptions.assumeTrue(DockerChecks.dockerAvailable(),
                "Docker is unavailable; skipping Kafka integration test");
        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));
        kafka.start();
    }

    @AfterAll
    static void stopKafka() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    protected static String bootstrapServers() {
        return kafka.getBootstrapServers();
    }
}
