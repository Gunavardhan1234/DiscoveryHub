package com.smarsh.discoveryhub.hold.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Ledger row for one disposition pass (FR-5): what was scanned, deleted,
 * skipped because a legal hold protects it, and errored. {@code deletedByType}
 * is the per-comm-type breakdown as JSON ({@code {"EMAIL":12,"CHAT":3}}).
 * Status: {@code RUNNING} → {@code COMPLETED}/{@code FAILED}.
 */
@Entity
@Table(name = "disposition_runs")
public class DispositionRunEntity {
    @Id public UUID id;
    @Column(name = "started_at")
    public OffsetDateTime startedAt;
    @Column(name = "finished_at")
    public OffsetDateTime finishedAt;
    public String status;
    public long scanned;
    public long deleted;
    @Column(name = "skipped_on_hold")
    public long skippedOnHold;
    public long errors;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "deleted_by_type")
    public String deletedByType;
}
