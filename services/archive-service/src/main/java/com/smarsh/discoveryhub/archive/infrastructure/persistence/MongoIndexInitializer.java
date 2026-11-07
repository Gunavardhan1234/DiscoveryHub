package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Creates the unique index on {@code messageKey} — the second idempotency guard
 * (architecture §2.1). Runs on application ready so the index exists before any
 * consumer processes a message.
 */
@Component
public class MongoIndexInitializer {

    private final MongoTemplate mongo;

    public MongoIndexInitializer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        IndexOperations ops = mongo.indexOps(MessageDocument.class);
        ops.ensureIndex(new Index().on("messageKey", org.springframework.data.domain.Sort.Direction.ASC).unique());
    }
}
