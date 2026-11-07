package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.archive.domain.port.MessageDeletionEventPublisher;

import java.util.ArrayList;
import java.util.List;

/** Records every {@code message.deleted.v1} publish for assertions. */
public class FakeMessageDeletionEventPublisher implements MessageDeletionEventPublisher {

    public record Published(String messageId, String commType, String reason) {}

    public final List<Published> published = new ArrayList<>();

    @Override
    public void publishDeleted(String messageId, String commType, String reason) {
        published.add(new Published(messageId, commType, reason));
    }
}
