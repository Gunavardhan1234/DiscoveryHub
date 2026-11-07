package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.archive.domain.port.DispositionStorePort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disposition executor tests (FR-5, Day 3 M4): expired unheld messages are
 * deleted (through the {@link RetentionGuard} proxy), held ones are skipped —
 * never deleted — and the counts/per-type breakdown the demo shows
 * ("deleted: N, skippedOnHold: M") are exact. Includes the paging frontier and
 * the mid-run race where a hold lands between the candidate query and the
 * delete.
 */
class DispositionExecutorTest {

    private static final Instant CUTOFF = Instant.parse("2026-09-01T00:00:00Z");

    private final FakeMessageDeletionStore deletionStore = new FakeMessageDeletionStore();
    private final FakeDispositionStore candidates = new FakeDispositionStore();
    private final List<String> cleanedKeys = new ArrayList<>();
    private final FakeMessageDeletionEventPublisher events = new FakeMessageDeletionEventPublisher();
    private final DispositionExecutor executor = new DispositionExecutor(
            candidates, new RetentionGuard(deletionStore, events), cleanedKeys::addAll, 2);

    @Test
    void deletesExpiredUnheldSkipsHeldAndCounts() {
        deletionStore.add("m-1");
        deletionStore.add("m-2", "h-1");           // held -> skipped
        deletionStore.add("m-3");
        deletionStore.add("c-1");
        candidates.expired("EMAIL", candidate("m-1", false, "s3/a.pdf"), candidate("m-2", true),
                candidate("m-3", false));
        candidates.expired("CHAT", candidate("c-1", false));

        DispositionExecutor.Result result = executor.run(List.of(
                new DispositionExecutor.TypeCutoff("EMAIL", CUTOFF),
                new DispositionExecutor.TypeCutoff("CHAT", CUTOFF)));

        assertThat(result.scanned()).isEqualTo(4);
        assertThat(result.deleted()).isEqualTo(3);
        assertThat(result.skippedOnHold()).isEqualTo(1);
        assertThat(result.errors()).isZero();
        assertThat(result.deletedByType()).containsExactlyInAnyOrderEntriesOf(
                Map.of("EMAIL", 2L, "CHAT", 1L));
        assertThat(deletionStore.contains("m-2")).as("held message survives the pass").isTrue();
        assertThat(deletionStore.contains("m-1")).isFalse();
        assertThat(cleanedKeys).containsExactly("s3/a.pdf");
        assertThat(events.published).as("every actually-deleted message publishes message.deleted.v1, attributed to disposition")
                .extracting(FakeMessageDeletionEventPublisher.Published::messageId, FakeMessageDeletionEventPublisher.Published::reason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("m-1", "DISPOSITION"),
                        org.assertj.core.groups.Tuple.tuple("m-3", "DISPOSITION"),
                        org.assertj.core.groups.Tuple.tuple("c-1", "DISPOSITION"));
    }

    @Test
    void holdLandingMidRunIsSkippedNotDeleted() {
        deletionStore.add("m-race");
        deletionStore.beforeDelete = () -> deletionStore.findById("m-race")
                .ifPresent(doc -> doc.addHold("h-late"));
        candidates.expired("EMAIL", candidate("m-race", false));

        DispositionExecutor.Result result = executor.run(List.of(
                new DispositionExecutor.TypeCutoff("EMAIL", CUTOFF)));

        assertThat(result.deleted()).isZero();
        assertThat(result.skippedOnHold()).isEqualTo(1);
        assertThat(deletionStore.contains("m-race")).isTrue();
    }

    @Test
    void pagesThroughMoreCandidatesThanOnePage() {
        for (int i = 1; i <= 5; i++) {
            deletionStore.add("m-" + i);
            candidates.expired("EMAIL", candidate("m-" + i, false));
        }

        DispositionExecutor.Result result = executor.run(List.of(
                new DispositionExecutor.TypeCutoff("EMAIL", CUTOFF)));

        assertThat(result.scanned()).isEqualTo(5);
        assertThat(result.deleted()).isEqualTo(5);
    }

    @Test
    void deleteFailureCountsAsErrorAndRunContinues() {
        deletionStore.add("m-bad");
        deletionStore.add("m-ok");
        deletionStore.failOnDelete("m-bad");
        candidates.expired("EMAIL", candidate("m-bad", false), candidate("m-ok", false));

        DispositionExecutor.Result result = executor.run(List.of(
                new DispositionExecutor.TypeCutoff("EMAIL", CUTOFF)));

        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(deletionStore.contains("m-ok")).isFalse();
    }

    private static DispositionStorePort.Candidate candidate(String id, boolean onHold, String... s3Keys) {
        return new DispositionStorePort.Candidate(id, onHold, List.of(s3Keys));
    }

    /** In-memory candidate pages keyed by comm type; honours afterId + limit. */
    private static final class FakeDispositionStore implements DispositionStorePort {
        private final Map<String, List<Candidate>> byType = new LinkedHashMap<>();

        void expired(String commType, Candidate... rows) {
            byType.computeIfAbsent(commType, k -> new ArrayList<>()).addAll(List.of(rows));
        }

        @Override
        public List<Candidate> findExpired(String commType, Instant cutoff, String afterId, int limit) {
            return byType.getOrDefault(commType, List.of()).stream()
                    .filter(c -> afterId == null || c.messageId().compareTo(afterId) > 0)
                    .sorted((a, b) -> a.messageId().compareTo(b.messageId()))
                    .limit(limit)
                    .toList();
        }
    }
}
