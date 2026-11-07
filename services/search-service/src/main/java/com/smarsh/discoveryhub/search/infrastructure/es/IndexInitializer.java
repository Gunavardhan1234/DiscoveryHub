package com.smarsh.discoveryhub.search.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Ensures the {@code messages} index exists with the contract mapping before the
 * service accepts traffic. Idempotent: skipped if the index already exists, so it
 * is safe across restarts and does not clobber a populated index.
 *
 * <p>The mapping lives in {@code classpath:es/messages-index.json} (architecture §3.4)
 * so the index definition is versioned with the service, not hand-rolled in an init
 * container.
 *
 * <p><b>SOLID — SRP:</b> exactly one responsibility — guarantee the index
 * exists with the right mapping before traffic arrives. Schema bootstrapping is
 * deliberately not mixed into {@code ElasticsearchSearchPort}, whose job is
 * queries and writes. (It is the Elasticsearch analogue of what Flyway
 * migrations do for the Postgres services.)
 *
 * <p><b>SOLID — OCP via externalised configuration:</b> both the index name and
 * the mapping location are injected {@code @Value}s, so the analyzer chain,
 * field boosts or shard settings change by editing the JSON contract — no Java
 * change, and the mapping is reviewable in a pull request.
 *
 * <p><b>OOP — Spring lifecycle hook:</b> implements {@link ApplicationRunner}
 * so creation happens once at startup rather than being checked on every
 * request. It is idempotent (skips when the index exists), which is what makes
 * it safe across restarts and, importantly, means it never clobbers a
 * populated index.
 *
 * <p><b>Fail-fast rationale:</b> a failure here is rethrown as
 * {@code IllegalStateException}, so the service refuses to start rather than
 * booting into a state where every search silently returns zero hits. Failing
 * loudly at startup is far cheaper to diagnose than a mysteriously empty index.
 */
@Component
public class IndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexInitializer.class);

    private final ElasticsearchClient client;
    private final ResourceLoader resourceLoader;
    private final String indexName;
    private final String mappingLocation;

    public IndexInitializer(ElasticsearchClient client, ResourceLoader resourceLoader,
                            @Value("${discoveryhub.search.index-name:messages}") String indexName,
                            @Value("${discoveryhub.search.index-mapping:classpath:es/messages-index.json}") String mappingLocation) {
        this.client = client;
        this.resourceLoader = resourceLoader;
        this.indexName = indexName;
        this.mappingLocation = mappingLocation;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                log.info("ES index '{}' already present — skipping creation", indexName);
                return;
            }
            Resource resource = resourceLoader.getResource(mappingLocation);
            try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                client.indices().create(c -> c.index(indexName).withJson(reader));
            }
            log.info("Created ES index '{}' from {}", indexName, mappingLocation);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Elasticsearch index '" + indexName + "'", e);
        }
    }
}
