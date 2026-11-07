package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import com.smarsh.discoveryhub.archive.domain.port.HoldStorePort;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Mongo adapter for {@link HoldStorePort}. Scope resolution queries the
 * authoritative message store (the source of truth — architecture &sect;2.1) by
 * custodian, date range, and an optional case-insensitive term match on subject
 * or body. Hold application is atomic:
 * <ul>
 *   <li>{@code addHold}: {@code $addToSet holds + $set onHold=true} in one
 *       update — idempotent under at-least-once delivery of
 *       {@code hold.commands.v1}.</li>
 *   <li>{@code removeHold}: an aggregation-pipeline update that filters the
 *       hold id out of {@code holds} and re-derives {@code onHold} as
 *       {@code holds.size > 0}, so an overlapping hold keeps the message
 *       protected (FR-4.5). Only {@code holds}/{@code onHold} are touched — no
 *       other field is rewritten, so the ingest document shape is preserved.</li>
 * </ul>
 *
 * <p>Field names match the keys written by the ingest upsert
 * ({@code MongoMessageStoreAdapter}): {@code custodianId}, {@code sentAt},
 * {@code subject}, {@code body}.
 */
@Component
public class MongoHoldStoreAdapter implements HoldStorePort {

    private static final String COLLECTION = "messages";

    private final MongoTemplate mongo;

    public MongoHoldStoreAdapter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<String> findIdsByScope(List<String> custodians, Instant from, Instant to, String terms) {
        List<Criteria> criteria = new ArrayList<>();
        if (custodians != null && !custodians.isEmpty()) {
            criteria.add(Criteria.where("custodianId").in(custodians));
        }
        if (from != null || to != null) {
            Criteria range = Criteria.where("sentAt");
            if (from != null) range.gte(from);
            if (to != null) range.lte(to);
            criteria.add(range);
        }
        if (terms != null && !terms.isBlank()) {
            Pattern p = Pattern.compile(Pattern.quote(terms), Pattern.CASE_INSENSITIVE);
            criteria.add(new Criteria().orOperator(
                    Criteria.where("subject").regex(p),
                    Criteria.where("body").regex(p)));
        }
        Query query = criteria.isEmpty() ? new Query() : Query.query(new Criteria().andOperator(criteria));
        query.fields().include("_id");
        List<String> ids = new ArrayList<>();
        for (Document doc : mongo.getCollection(COLLECTION).find(query.getQueryObject())) {
            ids.add(String.valueOf(doc.get("_id")));
        }
        return ids;
    }

    @Override
    public List<String> findIdsByHold(String holdId) {
        Query query = Query.query(Criteria.where("holds").is(holdId));
        query.fields().include("_id");
        List<String> ids = new ArrayList<>();
        for (Document doc : mongo.getCollection(COLLECTION).find(query.getQueryObject())) {
            ids.add(String.valueOf(doc.get("_id")));
        }
        return ids;
    }

    @Override
    public boolean addHold(String messageId, String holdId) {
        Query query = Query.query(Criteria.where("_id").is(messageId));
        Update update = new Update().addToSet("holds", holdId).set("onHold", true);
        var result = mongo.updateFirst(query, update, MessageDocument.class);
        return result.wasAcknowledged() && result.getModifiedCount() > 0;
    }

    @Override
    public boolean removeHold(String messageId, String holdId) {
        // Pipeline update: filter the hold id out of `holds`, then re-derive
        // onHold = holds.size > 0. Atomic and touches only holds/onHold.
        List<Document> pipeline = List.of(
                new Document("$set", new Document("holds",
                        new Document("$filter", new Document()
                                .append("input", "$holds")
                                .append("as", "h")
                                .append("cond", new Document("$ne", List.of("$$h", holdId)))))),
                new Document("$set", new Document("onHold",
                        new Document("$gt", List.of(new Document("$size",
                                new Document("$ifNull", List.of("$holds", List.of()))), 0)))));
        Document updated = mongo.getCollection(COLLECTION).findOneAndUpdate(
                new Document("_id", messageId),
                pipeline,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return updated != null && Boolean.TRUE.equals(updated.getBoolean("onHold", false));
    }
}
