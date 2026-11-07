package com.smarsh.discoveryhub.audit.domain.port;

import com.smarsh.discoveryhub.audit.domain.model.AuditEntry;
import com.smarsh.discoveryhub.audit.domain.model.AuditQuery;

import java.util.List;

/**
 * Outbound port for the append-only audit store (FR-7.3). The Mongo adapter
 * enforces structural append-only: the DB user has {@code insert}+{@code find}
 * only (no {@code update}/{@code remove}). This interface exposes no update or
 * delete — that absence is itself a rubric item.
 *
 * <p><b>SOLID — ISP (by omission):</b> the interface deliberately contains no
 * mutation methods — no implementer <i>can</i> offer an update/delete, so no
 * caller can be tempted to use one. A narrower, intent-revealing interface is
 * the Interface Segregation principle applied as a security guarantee.
 *
 * <p><b>SOLID — DIP:</b> consumers depend on this port; the Mongo mapping
 * lives entirely in the adapter.
 */
public interface AuditStorePort {

    void append(AuditEntry entry);

    /** Returns the latest hash for a case (for chain continuation), or {@code AuditEntry.GENESIS} if none. */
    String latestHashForCase(String caseId);

    List<AuditEntry> findByCaseId(String caseId);

    List<AuditEntry> findAll();

    /** Filtered read (FR-7.4): case/actor/action/targetType/time window + paging. */
    List<AuditEntry> find(AuditQuery query);

    /** Total entries matching the query's filters, ignoring its page/size — lets
     *  the UI render "showing 1–25 of 100" and a real page count. */
    long count(AuditQuery query);

    /** Re-walks the per-case hash chain and returns true if every link verifies. */
    boolean verifyChain(String caseId);
}
