package com.smarsh.discoveryhub.caseapp.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface EvidenceRepository extends JpaRepository<EvidenceItemEntity, UUID> {

    // Derived query method: Spring Data parses the method name itself ("findBy...OrderBy...Asc")
    // and generates the SQL — no query string to write or keep in sync with the method signature.
    List<EvidenceItemEntity> findByCaseIdOrderByAddedAtAsc(UUID caseId);

    // A boolean existence check compiles to a lightweight `SELECT 1 ... LIMIT 1`-style query
    // instead of loading a full entity just to test presence — this backs the idempotency check
    // in EvidenceService.addEvidence.
    boolean existsByCaseIdAndMessageId(UUID caseId, String messageId);

    @Query("select count(e) from EvidenceItemEntity e where e.caseId = :caseId")
    long countByCaseId(UUID caseId);

    @Query("""
            select e.caseId, count(e) from EvidenceItemEntity e
            where e.caseId in :caseIds group by e.caseId
            """)
    List<Object[]> countByCaseIds(List<UUID> caseIds);
}
