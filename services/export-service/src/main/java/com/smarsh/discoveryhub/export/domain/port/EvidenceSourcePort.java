package com.smarsh.discoveryhub.export.domain.port;

import java.util.List;

/**
 * Outbound port resolving the export scope for a case: the message ids on the
 * case's evidence list. The HTTP adapter calls case-service
 * ({@code GET /api/cases/{caseId}/evidence}) — services never read each
 * other's databases (architecture §1).
 *
 * <p><b>SOLID — DIP:</b> "where do evidence ids come from" is a detail behind
 * this interface; the worker only knows the contract, so the data source can
 * evolve without rippling into the export workflow.
 */
public interface EvidenceSourcePort {

    List<String> evidenceMessageIds(String caseId);
}
