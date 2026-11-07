package com.smarsh.discoveryhub.caseapp.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "evidence_items")
public class EvidenceItemEntity {
    @Id public UUID id;
    public UUID caseId;
    public String messageId;
    public OffsetDateTime addedAt;
    public String addedBy;
    public String source;
}
