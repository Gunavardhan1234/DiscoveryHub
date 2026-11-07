package com.smarsh.discoveryhub.hold.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "outbox")
public class OutboxEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String topic;
    public String key;
    @JdbcTypeCode(SqlTypes.JSON)
    public String payload;
    @Column(name = "created_at")
    public OffsetDateTime createdAt;
    @Column(name = "published_at")
    public OffsetDateTime publishedAt;
}
