package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.archive.domain.model.HeldItemException;
import com.smarsh.discoveryhub.archive.domain.model.MessageNotFoundException;
import com.smarsh.discoveryhub.archive.infrastructure.persistence.MessageDocument;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RetentionGuard} protection-proxy tests (FR-4.2/FR-4.6): a held message
 * can never be deleted, the holds list is reported on rejection, and the
 * defence-in-depth guarded delete wins even when a hold lands between the
 * guard's read and the delete.
 */
class RetentionGuardTest {

    private static final String MSG = "m-1001";

    private final FakeMessageDeletionStore store = new FakeMessageDeletionStore();
    private final FakeMessageDeletionEventPublisher events = new FakeMessageDeletionEventPublisher();
    private final RetentionGuard guard = new RetentionGuard(store, events);

    @Test void rejectsNullMessage() {
        assertThatThrownBy(() -> guard.assertDeletable(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void allowsNotHeldMessage() {
        assertThatCode(() -> guard.assertDeletable(new MessageDocument())).doesNotThrowAnyException();
    }

    @Test void blocksHeldMessage() {
        MessageDocument message = new MessageDocument();
        message.addHold("h-1");
        assertThatThrownBy(() -> guard.assertDeletable(message))
                .isInstanceOf(HeldItemException.class);
    }

    @Test void preservesOverlappingHoldSet() {
        MessageDocument message = new MessageDocument();
        message.setHolds(Set.of("h-1", "h-9"));
        message.removeHold("h-1");
        assertThatThrownBy(() -> guard.assertDeletable(message))
                .isInstanceOf(HeldItemException.class);
        message.removeHold("h-9");
        assertThatCode(() -> guard.assertDeletable(message)).doesNotThrowAnyException();
    }

    @Test void deleteRemovesUnheldMessage() {
        store.add(MSG);
        assertThatCode(() -> guard.delete(MSG)).doesNotThrowAnyException();
        assertThat(store.contains(MSG)).isFalse();
    }

    @Test void deletePublishesMessageDeletedEventOnSuccess() {
        store.add(MSG).setType("CHAT");
        guard.delete(MSG, "DISPOSITION");
        assertThat(events.published).extracting(
                FakeMessageDeletionEventPublisher.Published::messageId,
                FakeMessageDeletionEventPublisher.Published::commType,
                FakeMessageDeletionEventPublisher.Published::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(MSG, "CHAT", "DISPOSITION"));
    }

    @Test void deleteWithoutReasonDefaultsToManual() {
        store.add(MSG);
        guard.delete(MSG);
        assertThat(events.published).extracting(FakeMessageDeletionEventPublisher.Published::reason)
                .containsExactly("MANUAL");
    }

    @Test void deleteUnknownMessageThrowsNotFound() {
        assertThatThrownBy(() -> guard.delete("m-missing"))
                .isInstanceOf(MessageNotFoundException.class);
    }

    @Test void deleteHeldMessageIsRejectedWithHoldsList() {
        store.add(MSG, "h-1", "h-9");
        assertThatThrownBy(() -> guard.delete(MSG))
                .isInstanceOf(HeldItemException.class)
                .satisfies(ex -> assertThat(((HeldItemException) ex).holds())
                        .containsExactlyInAnyOrder("h-1", "h-9"));
        assertThat(store.contains(MSG)).as("held message must survive the delete").isTrue();
        assertThat(events.published).as("a rejected delete must never publish message.deleted.v1").isEmpty();
    }

    @Test void holdLandingBetweenReadAndDeleteStillProtects() {
        MessageDocument doc = store.add(MSG);
        store.beforeDelete = () -> doc.addHold("h-late");
        assertThatThrownBy(() -> guard.delete(MSG))
                .isInstanceOf(HeldItemException.class)
                .satisfies(ex -> assertThat(((HeldItemException) ex).holds()).containsExactly("h-late"));
        assertThat(store.contains(MSG)).isTrue();
    }

    @Test void messageDeletedConcurrentlyCountsAsSuccess() {
        store.add(MSG);
        store.beforeDelete = () -> store.remove(MSG);
        assertThatCode(() -> guard.delete(MSG)).doesNotThrowAnyException();
    }
}
