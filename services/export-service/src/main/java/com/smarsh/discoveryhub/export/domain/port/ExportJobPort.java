package com.smarsh.discoveryhub.export.domain.port;

import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for persisting export job state. The infrastructure adapter
 * backs this with JPA + Postgres.
 *
 * <p><b>SOLID — DIP + ISP:</b> the application layer owns this interface and
 * depends on it, not on JPA — the concrete {@code JpaExportJobAdapter} is
 * injected at the composition root ({@code ExportConfig}). It exposes only the
 * four operations export-service actually needs; no saveAll, no transaction
 * plumbing, so implementations stay thin and mockable.
 */
public interface ExportJobPort {

    ExportJob save(ExportJob job);

    Optional<ExportJob> findById(UUID id);

    /** All jobs, newest first. */
    List<ExportJob> findAll();

    /** Jobs for one case, newest first. */
    List<ExportJob> findByCaseId(String caseId);
}
