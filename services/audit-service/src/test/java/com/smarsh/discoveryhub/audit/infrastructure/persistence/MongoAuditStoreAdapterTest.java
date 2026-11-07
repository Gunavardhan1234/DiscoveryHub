package com.smarsh.discoveryhub.audit.infrastructure.persistence;

import com.smarsh.discoveryhub.audit.application.HashChain;
import com.smarsh.discoveryhub.audit.domain.model.AuditEntry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the {@link MongoAuditStoreAdapter} methods not already exercised by
 * {@link MongoAuditStoreAdapterFilterTest} (which focuses on filter/paging
 * translation): {@code append}, {@code latestHashForCase}, {@code
 * findByCaseId}, {@code findAll}, and {@code verifyChain}. All against a
 * mocked {@link MongoTemplate} — no real Mongo needed.
 */
class MongoAuditStoreAdapterTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final HashChain hashChain = new HashChain();
    private final MongoAuditStoreAdapter adapter = new MongoAuditStoreAdapter(mongo, hashChain);

    private AuditEntry entry(String eventId, String caseId, String prevHash, String hash) {
        return new AuditEntry(eventId, Instant.parse("2026-09-08T00:00:00.000Z"), "alice", "HOLD_PLACED",
                "HOLD", "h-1", caseId, null, null, "corr-1", prevHash, hash);
    }

    @Test
    void appendInsertsTheMongoDocumentFormOfTheEntry() {
        AuditEntry entry = entry("e1", "case-1", AuditEntry.GENESIS, "somehash");

        adapter.append(entry);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mongo).insert(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo("e1");
        assertThat(captor.getValue().caseId()).isEqualTo("case-1");
        assertThat(captor.getValue().hash()).isEqualTo("somehash");
    }

    @Test
    void latestHashForCaseReturnsTheMostRecentEntrysHash() {
        when(mongo.findOne(any(Query.class), eq(AuditEvent.class)))
                .thenReturn(AuditEvent.fromDomain(entry("e2", "case-1", "prev", "latest-hash")));

        String latest = adapter.latestHashForCase("case-1");

        assertThat(latest).isEqualTo("latest-hash");
    }

    @Test
    void latestHashForCaseReturnsGenesisWhenCaseHasNoEntries() {
        when(mongo.findOne(any(Query.class), eq(AuditEvent.class))).thenReturn(null);

        assertThat(adapter.latestHashForCase("brand-new-case")).isEqualTo(AuditEntry.GENESIS);
    }

    @Test
    void findByCaseIdMapsEveryDocumentBackToDomain() {
        when(mongo.find(any(Query.class), eq(AuditEvent.class))).thenReturn(List.of(
                AuditEvent.fromDomain(entry("e1", "case-1", AuditEntry.GENESIS, "h1")),
                AuditEvent.fromDomain(entry("e2", "case-1", "h1", "h2"))));

        List<AuditEntry> entries = adapter.findByCaseId("case-1");

        assertThat(entries).extracting(AuditEntry::eventId).containsExactly("e1", "e2");
    }

    @Test
    void findAllReturnsEveryDocumentAcrossAllCases() {
        when(mongo.find(any(Query.class), eq(AuditEvent.class))).thenReturn(List.of(
                AuditEvent.fromDomain(entry("a1", "case-a", AuditEntry.GENESIS, "ha")),
                AuditEvent.fromDomain(entry("b1", "case-b", AuditEntry.GENESIS, "hb"))));

        List<AuditEntry> entries = adapter.findAll();

        assertThat(entries).extracting(AuditEntry::caseId).containsExactlyInAnyOrder("case-a", "case-b");
    }

    @Test
    void verifyChainIsTrueForACorrectlyLinkedChain() {
        AuditEntry first = entry("e1", "case-1", AuditEntry.GENESIS, null);
        String hash1 = hashChain.hash(AuditEntry.GENESIS, first);
        first = entry("e1", "case-1", AuditEntry.GENESIS, hash1);

        AuditEntry secondUnsigned = entry("e2", "case-1", hash1, null);
        String hash2 = hashChain.hash(hash1, secondUnsigned);
        AuditEntry second = entry("e2", "case-1", hash1, hash2);

        when(mongo.find(any(Query.class), eq(AuditEvent.class)))
                .thenReturn(List.of(AuditEvent.fromDomain(first), AuditEvent.fromDomain(second)));

        assertThat(adapter.verifyChain("case-1")).isTrue();
    }

    @Test
    void verifyChainIsFalseWhenPrevHashLinkIsBroken() {
        AuditEntry first = entry("e1", "case-1", AuditEntry.GENESIS, "h1");
        // e2 claims a prevHash that doesn't match e1's actual hash.
        AuditEntry second = entry("e2", "case-1", "not-h1", "h2");

        when(mongo.find(any(Query.class), eq(AuditEvent.class)))
                .thenReturn(List.of(AuditEvent.fromDomain(first), AuditEvent.fromDomain(second)));

        assertThat(adapter.verifyChain("case-1")).isFalse();
    }

    @Test
    void verifyChainIsFalseWhenAnEntryWasTampered() {
        AuditEntry first = entry("e1", "case-1", AuditEntry.GENESIS, null);
        String realHash = hashChain.hash(AuditEntry.GENESIS, first);
        // Persisted hash doesn't match what re-hashing the entry produces.
        AuditEntry tampered = entry("e1", "case-1", AuditEntry.GENESIS, realHash + "-tampered");

        when(mongo.find(any(Query.class), eq(AuditEvent.class)))
                .thenReturn(List.of(AuditEvent.fromDomain(tampered)));

        assertThat(adapter.verifyChain("case-1")).isFalse();
    }

    @Test
    void verifyChainIsTrueForACaseWithNoEntries() {
        when(mongo.find(any(Query.class), eq(AuditEvent.class))).thenReturn(List.of());

        assertThat(adapter.verifyChain("no-such-case")).isTrue();
    }
}
