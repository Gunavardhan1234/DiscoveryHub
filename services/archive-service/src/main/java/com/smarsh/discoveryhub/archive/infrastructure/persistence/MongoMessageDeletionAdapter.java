package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import com.smarsh.discoveryhub.archive.domain.port.MessageDeletionPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Mongo adapter for {@link MessageDeletionPort}. {@code deleteIfUnheld} is the
 * defence-in-depth delete (FR-4.6): the filter {@code {_id, holds:{$size:0}}}
 * makes the hold check and the delete a single atomic Mongo operation, so a
 * hold applied after the {@code RetentionGuard} read but before the delete
 * still protects the message — the delete simply matches nothing.
 */
@Component
public class MongoMessageDeletionAdapter implements MessageDeletionPort {

    private final MongoTemplate mongo;

    public MongoMessageDeletionAdapter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<MessageDocument> findById(String messageId) {
        return Optional.ofNullable(mongo.findById(messageId, MessageDocument.class));
    }

    @Override
    public boolean deleteIfUnheld(String messageId) {
        Query query = Query.query(Criteria.where("_id").is(messageId).and("holds").size(0));
        var result = mongo.remove(query, MessageDocument.class);
        return result.wasAcknowledged() && result.getDeletedCount() > 0;
    }
}
