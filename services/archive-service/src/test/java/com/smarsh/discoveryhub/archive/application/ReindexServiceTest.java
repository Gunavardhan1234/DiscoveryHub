package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.archive.domain.port.MessageStorePort;
import com.smarsh.discoveryhub.archive.domain.port.StoredEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReindexService}: it streams every stored message from
 * Mongo (the source of truth) and republishes {@code comm.stored.v1}, proving
 * ES is a rebuildable projection (architecture §3.4, Day-3 M1).
 */
class ReindexServiceTest {

    private final MessageStorePort messageStore = mock(MessageStorePort.class);
    private final StoredEventPublisher publisher = mock(StoredEventPublisher.class);
    private final ReindexService service = new ReindexService(messageStore, publisher);

    private CommunicationEvent event(String messageId) {
        return new CommunicationEvent(
                messageId, "sha256:" + messageId, "ext-" + messageId, "EMAIL", "subject", "body",
                "th-1", null, "c-0007", List.of(), Instant.parse("2026-03-14T09:12:00Z"), List.of(), false);
    }

    @Test
    void republishesEveryStoredMessageWithMessageIdAsKey() {
        when(messageStore.streamAll()).thenReturn(Stream.of(event("msg-1"), event("msg-2"), event("msg-3")));

        long count = service.republishAll();

        assertThat(count).isEqualTo(3L);
        verify(publisher).publishStored(any(), eq("msg-1"));
        verify(publisher).publishStored(any(), eq("msg-2"));
        verify(publisher).publishStored(any(), eq("msg-3"));
    }

    @Test
    void skipsNullEventsAndEventsWithoutMessageId() {
        CommunicationEvent noId = new CommunicationEvent(
                null, "k", "ext", "EMAIL", "s", "b", "t", null, "c", List.of(),
                Instant.parse("2026-03-14T09:12:00Z"), List.of(), false);
        when(messageStore.streamAll()).thenReturn(Stream.of(event("msg-1"), null, noId));

        long count = service.republishAll();

        assertThat(count).isEqualTo(1L);
        verify(publisher).publishStored(any(), eq("msg-1"));
        verify(publisher, never()).publishStored(any(), eq(null));
    }

    @Test
    void emptyStoreRepublishesNothing() {
        when(messageStore.streamAll()).thenReturn(Stream.empty());

        assertThat(service.republishAll()).isZero();
        verify(publisher, never()).publishStored(any(), any());
    }
}
