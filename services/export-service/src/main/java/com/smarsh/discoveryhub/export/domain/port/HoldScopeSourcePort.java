package com.smarsh.discoveryhub.export.domain.port;

import java.util.List;

/**
 * Outbound port resolving the export scope for a hold (FR-6.1: export "a
 * hold's full scope"): the ids of every message currently covered by that
 * hold. The HTTP adapter calls search-service ({@code POST
 * /api/search/scope-ids} filtered on {@code holds}), which reflects the live,
 * reference-counted hold state kept in sync by {@code HoldIndexer} — not a
 * replay of the hold's original placement criteria, since messages ingested
 * after placement are never retroactively covered by it.
 */
public interface HoldScopeSourcePort {

    List<String> holdMessageIds(String holdId);
}
