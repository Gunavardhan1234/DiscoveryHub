package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.archive.domain.port.HoldAppliedPublisher;
import com.smarsh.discoveryhub.archive.domain.port.HoldStorePort;
import com.smarsh.discoveryhub.events.HoldApplied;
import com.smarsh.discoveryhub.events.HoldCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signature overlap-hold test (FR-4.5, execution-plan Day 1 M4: "write the
 * failing overlap test first"). Two holds cover the same message; releasing one
 * must <strong>not</strong> unprotect the message while the other still covers
 * it — the hold <em>set</em> is the source of truth, never a lone boolean.
 *
 * <p>Pure unit test: {@link HoldApplierUseCase} driven by an in-memory fake
 * {@link HoldStorePort} so the rule is verified without Mongo/Kafka.
 */
class HoldApplierUseCaseTest {

    private static final String MSG = "m-1001";
    private static final String CUSTODIAN = "c-0007";

    @Test
    void overlappingHoldsSurviveSingleRelease() {
        FakeStore store = new FakeStore();
        store.addMessage(MSG);
        store.bindScope(CUSTODIAN, List.of(MSG));
        CapturingPublisher publisher = new CapturingPublisher();
        HoldApplierUseCase useCase = new HoldApplierUseCase(store, publisher, 100);

        // place hold A over custodian c-0007 -> message is held by A
        useCase.apply(place("hold-A"));
        assertThat(store.isOnHold(MSG)).isTrue();
        assertThat(store.holds(MSG)).containsExactly("hold-A");
        HoldApplied placeA = publisher.last();
        assertThat(placeA.action()).isEqualTo(HoldApplied.PLACE);
        assertThat(placeA.completed()).isTrue();
        assertThat(placeA.progressPct()).isEqualTo(100);

        // place hold B over the same scope -> message is held by A AND B
        useCase.apply(place("hold-B"));
        assertThat(store.isOnHold(MSG)).isTrue();
        assertThat(store.holds(MSG)).containsExactlyInAnyOrder("hold-A", "hold-B");

        // release hold A -> message is STILL held by B (FR-4.5)
        useCase.apply(release("hold-A"));
        assertThat(store.isOnHold(MSG)).isTrue();
        assertThat(store.holds(MSG)).containsExactly("hold-B");

        HoldApplied releaseA = publisher.last();
        assertThat(releaseA.action()).isEqualTo(HoldApplied.RELEASE);
        assertThat(releaseA.onHold()).as("release batch must report still-on-hold for overlapping holds").isTrue();
        assertThat(releaseA.completed()).isTrue();
    }

    @Test
    void releasingLastHoldUnprotectsMessage() {
        FakeStore store = new FakeStore();
        store.addMessage(MSG);
        store.bindScope(CUSTODIAN, List.of(MSG));
        CapturingPublisher publisher = new CapturingPublisher();
        HoldApplierUseCase useCase = new HoldApplierUseCase(store, publisher, 100);

        useCase.apply(place("hold-A"));
        useCase.apply(release("hold-A"));

        assertThat(store.isOnHold(MSG)).isFalse();
        assertThat(store.holds(MSG)).isEmpty();
        assertThat(publisher.last().onHold()).isFalse();
    }

    @Test
    void placeOnEmptyScopeCompletesImmediately() {
        FakeStore store = new FakeStore();
        store.addMessage(MSG);
        store.bindScope("nobody", List.of());
        CapturingPublisher publisher = new CapturingPublisher();
        HoldApplierUseCase useCase = new HoldApplierUseCase(store, publisher, 100);

        useCase.apply(new HoldCommand("hold-empty", "case-1", HoldCommand.PLACE,
                List.of("nobody"), null, null, null, "alice"));

        HoldApplied done = publisher.last();
        assertThat(done.completed()).isTrue();
        assertThat(done.progressPct()).isEqualTo(100);
        assertThat(done.messageIds()).isEmpty();
    }

    private static HoldCommand place(String holdId) {
        return new HoldCommand(holdId, "case-1", HoldCommand.PLACE,
                List.of(CUSTODIAN), null, null, null, "alice");
    }

    private static HoldCommand release(String holdId) {
        return new HoldCommand(holdId, "case-1", HoldCommand.RELEASE,
                List.of(CUSTODIAN), null, null, null, "alice");
    }

    /** In-memory store keyed by message id; onHold derived from the holds set. */
    static final class FakeStore implements HoldStorePort {
        private final Map<String, Set<String>> holdsByMsg = new LinkedHashMap<>();
        private final Map<String, List<String>> scopeMatches = new LinkedHashMap<>();

        void addMessage(String id) {
            holdsByMsg.put(id, new LinkedHashSet<>());
        }

        void bindScope(String custodian, List<String> ids) {
            scopeMatches.put(custodian, ids);
        }

        boolean isOnHold(String id) {
            Set<String> h = holdsByMsg.get(id);
            return h != null && !h.isEmpty();
        }

        Set<String> holds(String id) {
            return new LinkedHashSet<>(holdsByMsg.getOrDefault(id, Set.of()));
        }

        @Override
        public List<String> findIdsByScope(List<String> custodians, Instant from, Instant to, String terms) {
            if (custodians == null || custodians.isEmpty()) {
                return List.copyOf(holdsByMsg.keySet());
            }
            List<String> out = new ArrayList<>();
            for (String c : custodians) {
                out.addAll(scopeMatches.getOrDefault(c, List.of()));
            }
            return out;
        }

        @Override
        public List<String> findIdsByHold(String holdId) {
            return holdsByMsg.entrySet().stream()
                    .filter(e -> e.getValue().contains(holdId))
                    .map(Map.Entry::getKey)
                    .toList();
        }

        @Override
        public boolean addHold(String messageId, String holdId) {
            holdsByMsg.computeIfAbsent(messageId, k -> new LinkedHashSet<>()).add(holdId);
            return true;
        }

        @Override
        public boolean removeHold(String messageId, String holdId) {
            Set<String> holds = holdsByMsg.get(messageId);
            if (holds != null) {
                holds.remove(holdId);
            }
            return isOnHold(messageId);
        }
    }

    static final class CapturingPublisher implements HoldAppliedPublisher {
        private final List<HoldApplied> events = new ArrayList<>();

        @Override
        public void publish(HoldApplied payload, String kafkaKey) {
            events.add(payload);
        }

        HoldApplied last() {
            assertThat(events).isNotEmpty();
            return events.get(events.size() - 1);
        }
    }
}
