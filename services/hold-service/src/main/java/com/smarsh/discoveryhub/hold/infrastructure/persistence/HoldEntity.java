package com.smarsh.discoveryhub.hold.infrastructure.persistence;

import com.smarsh.discoveryhub.hold.domain.model.HoldStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A legal hold placed against a communication scope (custodians + date range +
 * optional terms). Persisted {@code PENDING}; flipped to {@code ACTIVE} by the
 * {@code hold-tracker} consumer once {@code hold.applied.v1} reports
 * {@code completed:true} from the archive hold-applier.
 */
@Entity
@Table(name = "holds")
public class HoldEntity {
    @Id public UUID id;
    @Column(name = "case_id")
    public UUID caseId;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "hold_status")
    public HoldStatus status;
    @Column(name = "scope_custodians", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    public String[] scopeCustodians;
    @Column(name = "scope_from")
    public OffsetDateTime scopeFrom;
    @Column(name = "scope_to")
    public OffsetDateTime scopeTo;
    @Column(name = "scope_terms")
    public String scopeTerms;
    @Column(name = "held_count")
    public long heldCount;
    @Column(name = "applied_count")
    public long appliedCount;
    @Column(name = "placed_by")
    public String placedBy;
    @Column(name = "placed_at")
    public OffsetDateTime placedAt;
    @Column(name = "released_at")
    public OffsetDateTime releasedAt;
}
