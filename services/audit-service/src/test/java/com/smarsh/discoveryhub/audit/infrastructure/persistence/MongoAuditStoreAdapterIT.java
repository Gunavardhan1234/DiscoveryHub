package com.smarsh.discoveryhub.audit.infrastructure.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.smarsh.discoveryhub.audit.application.AuditConsumeUseCase;
import com.smarsh.discoveryhub.audit.application.HashChain;
import com.smarsh.discoveryhub.audit.domain.model.AuditEntry;
import com.smarsh.discoveryhub.audit.domain.model.AuditQuery;
import com.smarsh.discoveryhub.audit.domain.port.AuditStorePort;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.testsupport.MongoTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Audit-append-only" cross-boundary IT (execution-plan Day 4 M5) against a
 * real MongoDB 7 Testcontainer.
 *
 * <p>This is the regression test for the hash-chain bug found during
 * end-to-end verification: {@link AuditConsumeUseCase} used to hash
 * {@code Instant.now()} at full (sub-millisecond) precision, but MongoDB's
 * BSON {@code Date} only stores millisecond precision, so
 * {@link AuditStorePort#verifyChain} always reported {@code false} for chains
 * that had actually round-tripped through Mongo correctly. Running this
 * against a real Mongo container (not a mock) is the only way to catch that
 * class of bug — every prior unit test computed and verified the hash in the
 * same JVM call without ever going through BSON.</p>
 *
 * <p>Also proves the "append-only" structural contract: {@link AuditStorePort}
 * exposes no update/delete method at all, and the interface reflectively has
 * exactly the four read methods plus {@code append}.</p>
 *
 * <p>Skipped when Docker is unavailable (see AGENTS.md docker-java note).</p>
 */
class MongoAuditStoreAdapterIT extends MongoTestBase {

    private static final String DB_NAME = "discoveryhub_audit_test";
    private static MongoTemplate mongo;
    private AuditConsumeUseCase consumeUseCase;
    private MongoAuditStoreAdapter store;

    @BeforeAll
    static void buildTemplate() {
        if (mongo == null && connectionString() != null) {
            MongoClient client = MongoClients.create(connectionString());
            mongo = new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, DB_NAME));
        }
    }

    @BeforeEach
    void cleanSlate() {
        if (mongo == null) {
            return;
        }
        mongo.dropCollection(AuditEvent.class);
        store = new MongoAuditStoreAdapter(mongo, new HashChain());
        consumeUseCase = new AuditConsumeUseCase(store, new HashChain());
    }

    @Test
    void chainOfRealKafkaShapedEventsVerifiesAfterARealMongoRoundTrip() {
        String caseId = "case-it-1";
        consumeUseCase.accept(payload("e1", caseId, "system", "EXPORT_REQUESTED", "EXPORT", "job-1",
                null, "{\"status\":\"QUEUED\"}"));
        consumeUseCase.accept(payload("e2", caseId, "system", "EXPORT_COMPLETED", "EXPORT", "job-1",
                null, "{\"items\":1}"));
        consumeUseCase.accept(payload("e3", caseId, "investigator@demo", "HOLD_PLACED", "HOLD", "hold-1",
                null, "{\"status\":\"PENDING\"}"));

        // Read the entries back through a real Mongo query (not the same object
        // graph that was written) — this is what actually exercises the BSON
        // Date truncation the fix accounts for.
        boolean valid = store.verifyChain(caseId);

        assertThat(valid)
                .as("a chain of correctly-published events must verify after a real Mongo round trip")
                .isTrue();
        assertThat(store.findByCaseId(caseId)).hasSize(3);
    }

    @Test
    void tamperedEntryFailsVerification() {
        String caseId = "case-it-2";
        consumeUseCase.accept(payload("e1", caseId, "system", "EXPORT_REQUESTED", "EXPORT", "job-2",
                null, "{\"status\":\"QUEUED\"}"));

        // Simulate an operator directly editing the Mongo document (which the
        // production role can't do — insert+find only — but the hash must
        // still catch it if it somehow happened).
        mongo.getCollection("audit_events").updateOne(
                org.bson.Document.parse("{\"_id\":\"e1\"}"),
                org.bson.Document.parse("{\"$set\":{\"action\":\"EXPORT_FAILED\"}}"));

        assertThat(store.verifyChain(caseId)).isFalse();
    }

    @Test
    void chainStaysContiguousAcrossMultipleCases() {
        consumeUseCase.accept(payload("a1", "case-a", "system", "HOLD_PLACED", "HOLD", "h-a", null, null));
        consumeUseCase.accept(payload("b1", "case-b", "system", "HOLD_PLACED", "HOLD", "h-b", null, null));
        consumeUseCase.accept(payload("a2", "case-a", "system", "HOLD_RELEASED", "HOLD", "h-a", null, null));

        assertThat(store.verifyChain("case-a")).isTrue();
        assertThat(store.verifyChain("case-b")).isTrue();
        List<AuditEntry> caseA = store.findByCaseId("case-a");
        assertThat(caseA).extracting(AuditEntry::action).containsExactly("HOLD_PLACED", "HOLD_RELEASED");
        assertThat(caseA.get(1).prevHash()).isEqualTo(caseA.get(0).hash());
    }

    @Test
    void filteredFindHonoursActorAndActionAgainstRealMongo() {
        consumeUseCase.accept(payload("f1", "case-f", "alice", "HOLD_PLACED", "HOLD", "h-1", null, null));
        consumeUseCase.accept(payload("f2", "case-f", "bob", "HOLD_RELEASED", "HOLD", "h-1", null, null));

        List<AuditEntry> aliceOnly = store.find(new AuditQuery("case-f", "alice", null, null, null, null, 0, 50));

        assertThat(aliceOnly).extracting(AuditEntry::eventId).containsExactly("f1");
    }

    @Test
    void portExposesNoUpdateOrDeleteMethod() {
        List<String> methodNames = Arrays.stream(AuditStorePort.class.getMethods())
                .map(Method::getName)
                .toList();
        assertThat(methodNames)
                .as("AuditStorePort must never grow an update/delete method (FR-7: append-only by construction)")
                .containsExactlyInAnyOrder("append", "latestHashForCase", "findByCaseId", "findAll", "find", "count", "verifyChain");
    }

    @Test
    void countIgnoresPagingSoTheUiCanShowATrueTotal() {
        for (int i = 1; i <= 7; i++) {
            consumeUseCase.accept(payload("c" + i, "case-c", "alice", "HOLD_PLACED", "HOLD", "h-" + i, null, null));
        }
        AuditQuery firstPageOfTwo = new AuditQuery("case-c", null, null, null, null, null, 0, 2);

        assertThat(store.find(firstPageOfTwo)).hasSize(2);
        assertThat(store.count(firstPageOfTwo)).isEqualTo(7);
        // The filter still applies to the total — it is not a collection count.
        assertThat(store.count(new AuditQuery("case-c", "bob", null, null, null, null, 0, 2))).isZero();
    }

    private static AuditEventPayload payload(String eventId, String caseId, String actor, String action,
                                              String targetType, String targetId, String before, String after) {
        return new AuditEventPayload(eventId, Instant.now(), actor, action, targetType, targetId, caseId,
                before, after, "corr-" + eventId);
    }
}
