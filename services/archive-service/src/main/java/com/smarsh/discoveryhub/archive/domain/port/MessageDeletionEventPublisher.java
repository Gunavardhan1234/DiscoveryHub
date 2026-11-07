package com.smarsh.discoveryhub.archive.domain.port;

/**
 * Outbound port for publishing {@code message.deleted.v1} after a message is
 * actually removed from Mongo (FR-5/FR-1.7). Fired by {@link
 * com.smarsh.discoveryhub.archive.application.RetentionGuard} on every
 * successful delete — both the manual hold-guarded endpoint and the
 * disposition executor's retention sweep — so search-service can retract the
 * document from Elasticsearch instead of leaving a "ghost" hit that 404s when
 * opened.
 */
public interface MessageDeletionEventPublisher {

    void publishDeleted(String messageId, String commType, String reason);
}
