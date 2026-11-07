package com.smarsh.discoveryhub.hold.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Per communication-type retention policy. {@code duration} is stored as an
 * ISO-8601 duration string (e.g. {@code "PT2M"}) so the value is portable and
 * dialect-agnostic; {@link com.smarsh.discoveryhub.hold.domain.retention.RetentionService}
 * parses it to a {@link java.time.Duration} via the {@link
 * com.smarsh.discoveryhub.hold.domain.retention.RetentionStrategy} for a type.
 */
@Entity
@Table(name = "retention_policies")
public class RetentionPolicyEntity {
    @Id
    @Column(name = "comm_type")
    public String commType;
    public String duration;
    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;
}
