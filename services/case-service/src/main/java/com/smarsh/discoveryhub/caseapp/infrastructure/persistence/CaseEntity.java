package com.smarsh.discoveryhub.caseapp.infrastructure.persistence;

import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import com.smarsh.discoveryhub.caseapp.domain.model.MatterType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

// @Entity/@Table: JPA/Hibernate maps this class directly to the "cases" table — case-service owns
// this table exclusively (NFR-1: no shared database schemas between services); hold-service,
// search-service etc. never query Postgres directly, only through case-service's API/events.
@Entity
@Table(name = "cases")
public class CaseEntity {
    @Id public UUID id;
    @Column(nullable = false)
    public String name;
    public String description;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "matter_type", nullable = false, columnDefinition = "matter_type")
    public MatterType matterType;
    @Column(nullable = false)
    public String owner;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "case_status")
    public CaseStatus status = CaseStatus.DRAFT;
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
    // @Version: JPA optimistic locking — Hibernate checks this value hasn't changed since the row
    // was read before every UPDATE, and throws if it has. This handles concurrent writers (e.g.
    // two requests transitioning the same case at once) WITHOUT taking a database row lock or any
    // application-level `synchronized`/thread coordination — the alternative (pessimistic locking)
    // would hold a lock for the whole transaction and hurt throughput for a rarely-contended row.
    @Version public long version;

    // JPA lifecycle callbacks: Hibernate invokes these automatically immediately before the
    // INSERT/UPDATE, so every service method that changes a case gets consistent createdAt/
    // updatedAt stamping for free instead of every write path in CaseService remembering to set it.
    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
