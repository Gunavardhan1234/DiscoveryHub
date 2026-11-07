package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.archive.domain.port.MessageStorePort;
import com.smarsh.discoveryhub.archive.domain.port.StoredEventPublisher;

/**
 * The archive ingest use case (FR-1.4/1.5/1.6): durably store the message in
 * Mongo (idempotent upsert), then emit {@code comm.stored.v1} only if a new
 * document was inserted — a duplicate ingest is a logged no-op, never a second
 * event. This is the "durability" half of the acceptance/durability split.
 */
public class IngestConsumeUseCase {

    private final MessageStorePort messageStore;
    private final StoredEventPublisher publisher;

    public IngestConsumeUseCase(MessageStorePort messageStore, StoredEventPublisher publisher) {
        this.messageStore = messageStore;
        this.publisher = publisher;
    }

    public void accept(CommunicationEvent event) {
        boolean inserted = messageStore.upsert(event);
        if (inserted) {
            publisher.publishStored(event, event.messageId());
        }
    }
}
