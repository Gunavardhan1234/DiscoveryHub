package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.archive.domain.port.MessageStorePort;
import com.smarsh.discoveryhub.archive.domain.port.StoredEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestConsumeUseCaseTest {

    private final MessageStorePort messageStore = mock(MessageStorePort.class);
    private final StoredEventPublisher publisher = mock(StoredEventPublisher.class);
    private final IngestConsumeUseCase useCase = new IngestConsumeUseCase(messageStore, publisher);

    private CommunicationEvent event(String messageId) {
        return new CommunicationEvent(
                messageId, "sha256:key-1", "ext-1", "EMAIL", "subject", "body",
                "th-1", null, "c-0007", List.of(), Instant.parse("2026-03-14T09:12:00Z"), List.of(), false);
    }

    @Test
    void storesAndPublishesOnNewMessage() {
        when(messageStore.upsert(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        useCase.accept(event("msg-1"));

        ArgumentCaptor<CommunicationEvent> captor = ArgumentCaptor.forClass(CommunicationEvent.class);
        verify(publisher).publishStored(captor.capture(), org.mockito.ArgumentMatchers.eq("msg-1"));
        assertThat(captor.getValue().messageId()).isEqualTo("msg-1");
    }

    @Test
    void doesNotRepublishOnDuplicate() {
        when(messageStore.upsert(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        useCase.accept(event("msg-1"));

        verify(publisher, never()).publishStored(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
