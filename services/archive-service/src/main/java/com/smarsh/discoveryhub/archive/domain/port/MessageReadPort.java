package com.smarsh.discoveryhub.archive.domain.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read side of the archive (FR-1.4): authoritative message documents for
 * {@code GET /api/messages/{id}} and the bulk {@code GET /api/messages?ids=}
 * used by the export worker. Documents are returned as normalized maps in the
 * api-contracts.md shape — the archive is schemaless at the edges, so reads
 * pass the stored document through instead of forcing it into a DTO.
 *
 * <p><b>SOLID — ISP:</b> deliberately split from the write-side
 * {@code MessageStorePort} — read consumers (REST reads, export worker) get
 * only {@code findById/findByIds} and cannot see write operations, while
 * writers are not polluted with read query shapes. Each consumer depends on
 * the narrowest interface it needs.
 */
public interface MessageReadPort {

    Optional<Map<String, Object>> findById(String id);

    List<Map<String, Object>> findByIds(List<String> ids);
}
