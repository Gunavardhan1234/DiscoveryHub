package com.smarsh.discoveryhub.export.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExportJobRepository extends JpaRepository<ExportJobEntity, UUID> {

    List<ExportJobEntity> findAllByOrderByCreatedAtDesc();

    List<ExportJobEntity> findByCaseIdOrderByCreatedAtDesc(String caseId);
}
