package com.smarsh.discoveryhub.export.infrastructure.persistence;

import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.model.ExportJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "export_jobs")
public class ExportJobEntity {

    @Id public UUID id;
    @Column(name = "case_id", nullable = false) public String caseId;
    @Column(name = "hold_id") public String holdId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false) public ExportJobStatus status;
    @Column(name = "progress_pct", nullable = false) public int progressPct;
    @Column(name = "package_s3_key") public String packageS3Key;
    @Column(name = "package_sha256") public String packageSha256;
    public String error;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;

    public static ExportJobEntity fromDomain(ExportJob job) {
        var e = new ExportJobEntity();
        e.id = job.id();
        e.caseId = job.caseId();
        e.holdId = job.holdId();
        e.status = job.status();
        e.progressPct = job.progressPct();
        e.packageS3Key = job.packageS3Key();
        e.packageSha256 = job.packageSha256();
        e.error = job.error();
        e.createdAt = job.createdAt();
        e.updatedAt = job.updatedAt();
        return e;
    }

    public ExportJob toDomain() {
        return new ExportJob(id, caseId, holdId, status, progressPct, packageS3Key, packageSha256, error, createdAt, updatedAt);
    }
}
