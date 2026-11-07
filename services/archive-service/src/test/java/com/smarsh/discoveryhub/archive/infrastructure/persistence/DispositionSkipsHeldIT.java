package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.smarsh.discoveryhub.archive.application.DispositionExecutor;
import com.smarsh.discoveryhub.archive.application.RetentionGuard;
import com.smarsh.discoveryhub.testsupport.MongoTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-boundary IT for FR-5 "disposition-skips-held" (execution-plan Day 4
 * test matrix). A full disposition pass against a real MongoDB proves a
 * message under an active legal hold survives a retention run even though it
 * is past its retention cutoff, while an unheld expired message of the same
 * type is actually deleted — the guarantee the Day-3 demo narrates ("set
 * email retention to 2 min, trigger, show deleted: N, skippedOnHold: M").
 *
 * <p>Exercises the real {@link MongoDispositionStoreAdapter} (paging) and the
 * real {@link RetentionGuard} over the real {@link MongoMessageDeletionAdapter}
 * — the same guard {@code DELETE /api/messages/{id}} runs through — so the
 * guarantee is proven at the exact seam a held message is protected
 * everywhere else, not re-implemented for this test. Runs against a real
 * MongoDB 7 Testcontainer; skipped when Docker is unavailable.
 */
class DispositionSkipsHeldIT extends MongoTestBase {

    private static final String DB_NAME = "discoveryhub_archive_disposition_test";
    private static final Instant CUTOFF = Instant.parse("2026-06-01T00:00:00Z");

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
        if (mongo != null) {
            mongo.dropCollection(MessageDocument.class);
        }
    }

    @Test
    void heldMessageSurvivesUnheldExpiredMessageIsDeleted() {
        insert("m-held", "EMAIL", CUTOFF.minusSeconds(3600), Set.of("h-1"), List.of());
        insert("m-expired", "EMAIL", CUTOFF.minusSeconds(3600), Set.of(), List.of("s3/a.pdf"));
        insert("m-not-yet-due", "EMAIL", CUTOFF.plusSeconds(3600), Set.of(), List.of());
        insert("m-other-type", "CHAT", CUTOFF.minusSeconds(3600), Set.of(), List.of());

        List<String> cleanedKeys = new ArrayList<>();
        DispositionExecutor executor = new DispositionExecutor(
                new MongoDispositionStoreAdapter(mongo),
                new RetentionGuard(new MongoMessageDeletionAdapter(mongo)),
                cleanedKeys::addAll,
                500);

        DispositionExecutor.Result result = executor.run(
                List.of(new DispositionExecutor.TypeCutoff("EMAIL", CUTOFF)));

        // Only the two EMAIL messages past the cutoff are candidates at all —
        // the not-yet-due message and the CHAT message never enter the scan.
        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.skippedOnHold()).isEqualTo(1);
        assertThat(result.errors()).isZero();
        assertThat(result.deletedByType()).containsExactly(org.assertj.core.api.Assertions.entry("EMAIL", 1L));

        assertThat(exists("m-held")).as("held message survives a retention pass it is otherwise eligible for").isTrue();
        assertThat(exists("m-expired")).as("unheld expired message is actually deleted").isFalse();
        assertThat(exists("m-not-yet-due")).as("message not yet past retention is untouched").isTrue();
        assertThat(exists("m-other-type")).as("a different comm type with no matching cutoff is untouched").isTrue();
        assertThat(cleanedKeys).as("attachment blobs of the deleted message are handed to the cleaner")
                .containsExactly("s3/a.pdf");
    }

    @Test
    void holdReleasedBeforeTheRunNoLongerProtectsTheMessage() {
        insert("m-released", "EMAIL", CUTOFF.minusSeconds(3600), Set.of(), List.of());

        DispositionExecutor executor = new DispositionExecutor(
                new MongoDispositionStoreAdapter(mongo),
                new RetentionGuard(new MongoMessageDeletionAdapter(mongo)),
                keys -> { }, 500);

        DispositionExecutor.Result result = executor.run(
                List.of(new DispositionExecutor.TypeCutoff("EMAIL", CUTOFF)));

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.skippedOnHold()).isZero();
        assertThat(exists("m-released")).isFalse();
    }

    private boolean exists(String id) {
        return mongo.exists(Query.query(Criteria.where("_id").is(id)), MessageDocument.class);
    }

    private void insert(String id, String type, Instant sentAt, Set<String> holds, List<String> attachmentKeys) {
        MessageDocument doc = new MessageDocument();
        doc.setId(id);
        doc.setMessageKey("sha256:" + id);
        doc.setType(type);
        doc.setSubject("subject-" + id);
        doc.setBody("body-" + id);
        doc.setTimestamp(sentAt);
        doc.setCustodianId("c-0007");
        doc.setHolds(new LinkedHashSet<>(holds));
        doc.setIngestedAt(Instant.now());
        List<MessageDocument.Attachment> attachments = new ArrayList<>();
        for (String key : attachmentKeys) {
            attachments.add(new MessageDocument.Attachment(
                    "a-" + key, "file.pdf", "application/pdf", 1024L, "sha256:x", key));
        }
        doc.setAttachments(attachments);
        doc.setHasAttachment(!attachments.isEmpty());
        mongo.save(doc);
    }
}
