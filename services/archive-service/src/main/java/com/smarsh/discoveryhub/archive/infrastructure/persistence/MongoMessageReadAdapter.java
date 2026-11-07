package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import com.smarsh.discoveryhub.archive.domain.port.MessageReadPort;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mongo read adapter for the {@code messages} collection. Reads raw
 * {@link Document}s (not {@code MessageDocument}) so every stored field —
 * including ones written before a mapping change — survives into the response,
 * then normalizes to the api-contracts.md §archive shape: {@code _id} becomes
 * {@code id}, and {@code timestamp} falls back to the ingest-written
 * {@code sentAt}.
 *
 * <p><b>OOP — Adapter pattern + SOLID — DIP:</b> the only place that knows
 * Mongo is behind the {@code MessageReadPort} abstraction; callers (REST
 * controller, export worker's bulk fetch) stay storage-agnostic. The
 * {@code normalize} boundary translation is static and pure, so the wire-shape
 * contract is unit-testable without a database.
 */
@Component
public class MongoMessageReadAdapter implements MessageReadPort {

    private final MongoTemplate mongo;

    public MongoMessageReadAdapter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Map<String, Object>> findById(String id) {
        Document document = mongo.findOne(
                Query.query(Criteria.where("_id").is(id)), Document.class, "messages");
        return Optional.ofNullable(document).map(MongoMessageReadAdapter::normalize);
    }

    @Override
    public List<Map<String, Object>> findByIds(List<String> ids) {
        List<Document> documents = mongo.find(
                Query.query(Criteria.where("_id").in(ids)), Document.class, "messages");
        return documents.stream().map(MongoMessageReadAdapter::normalize).toList();
    }

    private static Map<String, Object> normalize(Document document) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", String.valueOf(document.get("_id")));
        for (Map.Entry<String, Object> field : document.entrySet()) {
            String key = field.getKey();
            if ("_id".equals(key) || "_class".equals(key) || "sentAt".equals(key)) continue;
            message.put(key, field.getValue());
        }
        message.putIfAbsent("timestamp", document.get("sentAt"));
        message.putIfAbsent("holds", new ArrayList<>());
        message.putIfAbsent("onHold", Boolean.FALSE);
        message.putIfAbsent("hasAttachment", Boolean.FALSE);
        message.putIfAbsent("attachments", new ArrayList<>());
        return message;
    }
}
