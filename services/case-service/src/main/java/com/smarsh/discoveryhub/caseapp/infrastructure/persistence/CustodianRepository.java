package com.smarsh.discoveryhub.caseapp.infrastructure.persistence;

import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseCustodianEntity.CaseCustodianId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CustodianRepository extends JpaRepository<CaseCustodianEntity, CaseCustodianId> {
    List<CaseCustodianEntity> findByIdCaseIdOrderByAddedAtAsc(UUID caseId);
}
