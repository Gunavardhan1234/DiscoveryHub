package com.smarsh.discoveryhub.export.infrastructure.persistence;

import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.port.ExportJobPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>Adapter</b> (GoF) implementing {@link ExportJobPort} with Spring Data JPA.
 *
 * <p><b>SOLID — DIP:</b> the application layer calls {@code save/find} on the
 * port; this class is where JPA, entities and transactions live. Mapping to
 * and from the pure domain {@code ExportJob} happens only here, so JPA
 * annotations never leak past this boundary into the domain model.
 */
@Component
public class JpaExportJobAdapter implements ExportJobPort {

    private final ExportJobRepository repository;

    public JpaExportJobAdapter(ExportJobRepository repository) {
        this.repository = repository;
    }

    @Override
    public ExportJob save(ExportJob job) {
        return repository.save(ExportJobEntity.fromDomain(job)).toDomain();
    }

    @Override
    public Optional<ExportJob> findById(UUID id) {
        return repository.findById(id).map(ExportJobEntity::toDomain);
    }

    @Override
    public List<ExportJob> findAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(ExportJobEntity::toDomain).toList();
    }

    @Override
    public List<ExportJob> findByCaseId(String caseId) {
        return repository.findByCaseIdOrderByCreatedAtDesc(caseId).stream().map(ExportJobEntity::toDomain).toList();
    }
}
