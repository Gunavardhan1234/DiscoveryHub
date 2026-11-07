package com.smarsh.discoveryhub.caseapp.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Transactional outbox for case-service (D-008). case-service writes
 * {@code case.closed.v1} outbox rows in the same Postgres transaction as the
 * status update; a poller publishes them to Kafka. This is case-service's own
 * outbox — hold-service has its own for {@code hold.commands.v1}.
 */
// Transactional Outbox pattern: rows here are written in the SAME database transaction as the
// business change they describe (see CaseService.emitCaseClosed), guaranteeing the event is never
// lost even if Kafka is down at that instant — a separate poller (not shown in this file) reads
// rows where publishedAt IS NULL and forwards them, then stamps publishedAt once delivered.
@Entity
@Table(name = "outbox")
public class OutboxEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String topic;
    public String key;
    // @JdbcTypeCode(SqlTypes.JSON): tells Hibernate to bind this column as Postgres jsonb rather
    // than a plain varchar — without it every insert fails with a type-mismatch error at the DB.
    @JdbcTypeCode(SqlTypes.JSON)
    public String payload;
    @Column(name = "created_at")
    public OffsetDateTime createdAt;
    // null == "not yet published" — this single nullable timestamp doubles as both the publish
    // status flag and the audit record of when delivery actually happened, avoiding a separate
    // boolean column that could drift out of sync with the timestamp.
    @Column(name = "published_at")
    public OffsetDateTime publishedAt;
}
