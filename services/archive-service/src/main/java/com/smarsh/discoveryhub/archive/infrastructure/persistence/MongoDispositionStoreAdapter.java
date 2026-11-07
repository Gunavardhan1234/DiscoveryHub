package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import com.smarsh.discoveryhub.archive.domain.port.DispositionStorePort;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Sorts.ascending;

/**
 * Mongo adapter for {@link DispositionStorePort}. Queries the raw {@code sentAt}
 * key (the field the ingest upsert writes — see {@code MongoMessageStoreAdapter})
 * and pages by ascending {@code _id} with an exclusive {@code afterId} cursor,
 * so a run sees a stable frontier even while it deletes. Only {@code _id},
 * {@code holds} and {@code attachments.s3Key} are projected — the executor
 * never loads message bodies.
 */
@Component
public class MongoDispositionStoreAdapter implements DispositionStorePort {

    private static final String COLLECTION = "messages";

    private final MongoTemplate mongo;

    public MongoDispositionStoreAdapter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<Candidate> findExpired(String commType, Instant cutoff, String afterId, int limit) {
        List<Bson> clauses = new ArrayList<>(List.of(
                eq("type", commType),
                lt("sentAt", cutoff)));
        if (afterId != null) {
            clauses.add(gt("_id", afterId));
        }
        List<Candidate> out = new ArrayList<>();
        mongo.getCollection(COLLECTION)
                .find(and(clauses))
                .sort(ascending("_id"))
                .limit(Math.max(1, limit))
                .projection(new Document("_id", 1).append("holds", 1).append("attachments.s3Key", 1))
                .forEach(doc -> out.add(toCandidate(doc)));
        return out;
    }

    private Candidate toCandidate(Document doc) {
        List<?> holds = doc.getList("holds", Object.class, List.of());
        List<String> s3Keys = new ArrayList<>();
        for (Document attachment : doc.getList("attachments", Document.class, List.of())) {
            String key = attachment.getString("s3Key");
            if (key != null && !key.isBlank()) {
                s3Keys.add(key);
            }
        }
        return new Candidate(String.valueOf(doc.get("_id")), !holds.isEmpty(), s3Keys);
    }
}
