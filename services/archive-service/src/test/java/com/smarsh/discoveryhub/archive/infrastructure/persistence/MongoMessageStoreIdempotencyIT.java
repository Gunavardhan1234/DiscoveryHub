package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import com.smarsh.discoveryhub.archive.domain.port.MessageStorePort;
import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.testsupport.MongoTestBase;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency proof for the archive ingest path (architecture §2.1, FR-1.6,
 * Day-2 hardening). Re-submitting the same {@code comm.ingest.v1} event N times —
 * exactly the redelivery/retry scenario Kafka produces under a broker blip or a
 * consumer restart — must yield exactly one Mongo document, and only the first
 * delivery reports {@code inserted=true}.
 *
 * <p>This is a {@link ParameterizedTest} over N ∈ {1, 2, 3, 5, 10} so the contract
 * is asserted across several retry counts, not just one. Runs against a real
 * MongoDB 7 Testcontainer; skipped when Docker is unavailable.
 */
class MongoMessageStoreIdempotencyIT extends MongoTestBase {

    private static final String DB_NAME = "discoveryhub_archive_test";
    private static MongoTemplate mongo;
    private static MongoClient client;

    @BeforeAll
    static void buildTemplate() {
        if (mongo == null && connectionString() != null) {
            client = MongoClients.create(connectionString());
            mongo = new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, DB_NAME));
        }
    }

    @BeforeEach
    void cleanSlate() {
        if (mongo == null) {
            return;
        }
        mongo.dropCollection(MessageDocument.class);
        // Recreate the unique index on messageKey (the second idempotency guard)
        // that MongoIndexInitializer creates in production.
        IndexOperations ops = mongo.indexOps(MessageDocument.class);
        ops.createIndex(new Index().on("messageKey", Sort.Direction.ASC).unique());
    }

    @ParameterizedTest(name = "N={0} re-submits yield exactly 1 Mongo doc")
    @ValueSource(ints = {1, 2, 3, 5, 10})
    void nResubmitsYieldExactlyOneDocument(int n) {
        MessageStorePort store = new MongoMessageStoreAdapter(mongo);
        CommunicationEvent event = event("msg-A");

        boolean firstResult = store.upsert(event);

        for (int i = 1; i < n; i++) {
            assertThat(store.upsert(event))
                    .as("re-submit #%d must be a no-op (inserted=false)", i + 1)
                    .isFalse();
        }

        assertThat(firstResult).as("first delivery must insert (inserted=true)").isTrue();
        long count = mongo.count(new Query(), MessageDocument.class);
        assertThat(count).as("after %d re-submits the messages collection must hold exactly one document", n).isEqualTo(1);

        // The surviving document must be the originally-inserted one, identity unchanged.
        MessageDocument doc = mongo.findOne(new Query(), MessageDocument.class);
        assertThat(doc).isNotNull();
        assertThat(doc.getId()).isEqualTo("msg-A");
        assertThat(doc.getMessageKey()).isEqualTo("sha256:key-msg-A");
    }

    @ParameterizedTest(name = "distinct messages N={0} each survive once")
    @ValueSource(ints = {2, 5, 10})
    void distinctMessagesEachStoredOnce(int n) {
        MessageStorePort store = new MongoMessageStoreAdapter(mongo);
        for (int i = 0; i < n; i++) {
            assertThat(store.upsert(event("msg-" + i))).isTrue();
        }
        assertThat(mongo.count(new Query(), MessageDocument.class)).isEqualTo(n);
        // Re-delivering every distinct event changes nothing.
        for (int i = 0; i < n; i++) {
            assertThat(store.upsert(event("msg-" + i))).isFalse();
        }
        assertThat(mongo.count(new Query(), MessageDocument.class)).isEqualTo(n);
    }

    private static CommunicationEvent event(String messageId) {
        return new CommunicationEvent(
                messageId, "sha256:key-" + messageId, "ext-" + messageId, "EMAIL", "subject", "body",
                "th-1", null, "c-0007",
                List.of(new CommunicationEvent.Participant("Ada Byron", "ada@fict.example", "FROM")),
                Instant.parse("2026-03-14T09:12:00Z"), List.of(), false);
    }
}
