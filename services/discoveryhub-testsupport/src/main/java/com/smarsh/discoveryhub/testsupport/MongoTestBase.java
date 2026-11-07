package com.smarsh.discoveryhub.testsupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MongoDBContainer;

/**
 * Base class for any test that needs a real MongoDB 7 instance. Subclasses use
 * {@link #connectionString()} to build a {@code MongoClient}.
 *
 * <p>Skips the whole class when Docker is not running.</p>
 */
public abstract class MongoTestBase {

    protected static MongoDBContainer mongo;

    @BeforeAll
    static void startMongo() {
        Assumptions.assumeTrue(DockerChecks.dockerAvailable(),
                "Docker is unavailable; skipping Mongo integration test");
        mongo = new MongoDBContainer("mongo:7");
        mongo.start();
    }

    @AfterAll
    static void stopMongo() {
        if (mongo != null) {
            mongo.stop();
        }
    }

    protected static String connectionString() {
        return mongo.getConnectionString();
    }
}
