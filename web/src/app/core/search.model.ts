/** TypeScript mirrors of the search-service REST contract (docs/api-contracts.md §search). */

export type Sort = 'RELEVANCE' | 'DATE_ASC' | 'DATE_DESC';

export interface Filters {
  from?: string;
  to?: string;
  types?: string[];
  custodians?: string[];
  hasAttachment?: boolean;
  onHold?: boolean;
}

export interface QuerySpec {
  q: string;
  filters: Filters;
  sort: Sort;
  page: number;
  size: number;
  caseId?: string;
}

export interface SearchHit {
  messageId: string;
  subject: string;
  snippet: string;
  score: number;
  custodianId: string;
  timestamp: string;
  type: string;
  hasAttachment: boolean;
  onHold: boolean;
}

export interface SearchResult {
  total: number;
  page: number;
  size: number;
  hits: SearchHit[];
}

/**
 * Aggregated counts for a scope overview (architecture §2.2). The REST endpoint
 * `GET /api/search/facets?q=` reflects the text query only — not the structured
 * filters — so the UI labels it accordingly.
 */
export interface FacetResult {
  total: number;
  byType: Record<string, number>;
  byCustodian: Record<string, number>;
  onHoldCount: number;
  hasAttachmentCount: number;
}

/** Shared error envelope (docs/api-contracts.md). The backend sends an
 *  RFC-7807-shaped body with {@code detail} (human-readable) + {@code code}
 *  (machine-readable); {@code message}/{@code error} are kept for older
 *  callers. {@link ErrorService} prefers {@code detail} so the toast shows
 *  the actual reason (e.g. "Case is closed — no new holds allowed"). */
export interface ApiError {
  timestamp: string;
  status: number;
  error?: string;
  title?: string;
  code?: string;
  detail?: string;
  message?: string;
  path?: string;
}
