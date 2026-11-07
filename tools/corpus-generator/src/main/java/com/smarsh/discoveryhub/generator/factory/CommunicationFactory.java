package com.smarsh.discoveryhub.generator.factory;

import com.smarsh.discoveryhub.generator.model.Custodian;
import com.smarsh.discoveryhub.generator.model.IngestRequest;

import java.time.Instant;

/**
 * Factory pattern (architecture &sect;2.1): each communication type is produced
 * by its own factory off this common interface. The generator picks
 * {@link EmailFactory} or {@link ChatFactory} per message.
 */
public abstract class CommunicationFactory {

    public abstract String type();

    public abstract IngestRequest create(int index, Custodian sender, Custodian recipient,
                                          Instant sentAt, String threadId, String inReplyTo,
                                          int attachmentPct);
}
