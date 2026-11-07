package com.smarsh.discoveryhub.caseapp.infrastructure.persistence;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "case_custodians")
public class CaseCustodianEntity {
    @EmbeddedId public CaseCustodianId id;
    public String custodianName;
    public java.time.OffsetDateTime addedAt;

    // @EmbeddedId + Serializable: a composite primary key (caseId, custodianId) — a custodian
    // isn't a globally unique entity, only unique per-case, so the natural key is the pair rather
    // than a synthetic UUID; JPA requires composite-key classes to implement Serializable.
    @jakarta.persistence.Embeddable
    public static class CaseCustodianId implements Serializable {
        private java.util.UUID caseId;
        private String custodianId;

        public CaseCustodianId() {}

        public CaseCustodianId(java.util.UUID caseId, String custodianId) {
            this.caseId = caseId;
            this.custodianId = custodianId;
        }

        public java.util.UUID getCaseId() { return caseId; }
        public void setCaseId(java.util.UUID caseId) { this.caseId = caseId; }
        public String getCustodianId() { return custodianId; }
        public void setCustodianId(String custodianId) { this.custodianId = custodianId; }

        // equals/hashCode override: REQUIRED for a JPA composite-key class — Hibernate uses these
        // (not reference identity) to compare/cache entities by primary key, and existsById()/
        // findById() would silently misbehave without a value-based equals here. Compares by the
        // business key fields (caseId + custodianId), not by object identity, so two separately
        // loaded CaseCustodianId instances representing the same row are correctly equal.
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CaseCustodianId that)) return false;
            return Objects.equals(caseId, that.caseId) && Objects.equals(custodianId, that.custodianId);
        }

        @Override
        public int hashCode() { return Objects.hash(caseId, custodianId); }
    }
}
