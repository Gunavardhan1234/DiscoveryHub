package com.smarsh.discoveryhub.hold.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Transactional outbox for hold-service (D-008). {@link HoldService} writes a
 * {@code hold.commands.v1} outbox row in the same Postgres transaction as the
 * hold insert; {@link com.smarsh.discoveryhub.hold.infrastructure.messaging.OutboxPoller}
 * publishes unpublished rows to Kafka and stamps {@code published_at}.
 */
public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    List<OutboxEntity> findByPublishedAtIsNullOrderByCreatedAtAsc();
}
