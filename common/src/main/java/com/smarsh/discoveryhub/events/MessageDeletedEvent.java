package com.smarsh.discoveryhub.events;

/** Payload for {@code message.deleted.v1} (see {@link Topics#MESSAGE_DELETED_V1}). */
public record MessageDeletedEvent(String messageId, String commType, String reason) {
}
