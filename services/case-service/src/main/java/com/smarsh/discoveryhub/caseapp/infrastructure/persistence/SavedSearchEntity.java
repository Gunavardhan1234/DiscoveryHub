package com.smarsh.discoveryhub.caseapp.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code spec} is stored as {@code jsonb} (V1 migration) — {@code @JdbcTypeCode(SqlTypes.JSON)}
 * tells Hibernate to bind it as JSON rather than a plain varchar (same fix as
 * {@code OutboxEntity.payload}); without it, every insert fails with
 * "column \"spec\" is of type jsonb but expression is of type character varying".
 */
@Entity
@Table(name = "saved_searches")
public class SavedSearchEntity {
    @Id public UUID id;
    public UUID caseId;
    public String name;
    @JdbcTypeCode(SqlTypes.JSON)
    public String spec;
    public OffsetDateTime createdAt;
}
