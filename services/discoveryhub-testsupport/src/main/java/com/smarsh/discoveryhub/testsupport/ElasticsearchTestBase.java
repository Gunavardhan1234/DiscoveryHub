package com.smarsh.discoveryhub.testsupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for any test that needs a real Elasticsearch 8 node. Subclasses use
 * {@link #httpHost()} to build a client.
 *
 * <p>Skips the whole class when Docker is not running.</p>
 */
public abstract class ElasticsearchTestBase {

    protected static ElasticsearchContainer elasticsearch;

    @BeforeAll
    static void startElasticsearch() {
        Assumptions.assumeTrue(DockerChecks.dockerAvailable(),
                "Docker is unavailable; skipping Elasticsearch integration test");
        elasticsearch = new ElasticsearchContainer(
                DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3")
                        .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"));
        elasticsearch.withEnv("xpack.security.enabled", "false");
        elasticsearch.start();
    }

    @AfterAll
    static void stopElasticsearch() {
        if (elasticsearch != null) {
            elasticsearch.stop();
        }
    }

    protected static String httpHost() {
        return elasticsearch.getHttpHostAddress();
    }
}
