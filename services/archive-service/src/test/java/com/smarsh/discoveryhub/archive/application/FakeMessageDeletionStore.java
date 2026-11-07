package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.archive.domain.port.MessageDeletionPort;
import com.smarsh.discoveryhub.archive.infrastructure.persistence.MessageDocument;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory {@link MessageDeletionPort} fake mirroring the Mongo adapter's
 * semantics: {@code deleteIfUnheld} only removes a document whose hold set is
 * empty. {@link #beforeDelete} lets a test mutate state between the guard's
 * read and the delete to simulate the FR-4.6 race the defence-in-depth query
 * exists for.
 */
public class FakeMessageDeletionStore implements MessageDeletionPort {

    private final Map<String, MessageDocument> docs = new LinkedHashMap<>();
    private final Set<String> failingIds = new HashSet<>();
    Runnable beforeDelete = () -> { };

    /** Makes {@code deleteIfUnheld(id)} throw, to exercise error counting. */
    public void failOnDelete(String id) {
        failingIds.add(id);
    }

    public MessageDocument add(String id, String... holds) {
        MessageDocument doc = new MessageDocument();
        doc.setId(id);
        for (String hold : holds) {
            doc.addHold(hold);
        }
        docs.put(id, doc);
        return doc;
    }

    public boolean contains(String id) {
        return docs.containsKey(id);
    }

    public void remove(String id) {
        docs.remove(id);
    }

    @Override
    public Optional<MessageDocument> findById(String messageId) {
        return Optional.ofNullable(docs.get(messageId));
    }

    @Override
    public boolean deleteIfUnheld(String messageId) {
        beforeDelete.run();
        if (failingIds.contains(messageId)) {
            throw new IllegalStateException("Simulated delete failure for " + messageId);
        }
        MessageDocument doc = docs.get(messageId);
        if (doc == null || !doc.getHolds().isEmpty()) {
            return false;
        }
        docs.remove(messageId);
        return true;
    }
}
