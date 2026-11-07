/** TypeScript mirrors of the DiscoveryHub REST contracts (docs/api-contracts.md).
 *  One file per domain would be overkill at this scale; grouped here so a screen
 *  imports the slice it needs. Strings stay strings; we never refactor an ISO
 *  timestamp into a Date at the API boundary. */

// ---- shared ----
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface ApiError {
  timestamp?: string;
  status: number;
  error?: string;
  title?: string;
  code?: string;
  detail?: string;
  message: string;
  correlationId?: string;
  path?: string;
}

// ---- cases (case-service 8084) ----
export type CaseStatus = 'DRAFT' | 'ACTIVE' | 'UNDER_REVIEW' | 'CLOSED';
export type MatterType = 'INVESTIGATION' | 'LITIGATION' | 'REGULATORY_INQUIRY';

export interface Case {
  id: string;
  name: string;
  description?: string;
  matterType: MatterType;
  owner: string;
  status: CaseStatus;
  createdAt: string;
  updatedAt?: string;
  version: number;
}

export interface CaseCreate {
  name: string;
  description?: string;
  matterType: MatterType;
  owner: string;
}

/** Partial case update (FR-2.3 / FR-7.1 CASE_UPDATED). Every field optional —
 *  null/undefined means "leave unchanged". status is not updatable here. */
export interface CaseUpdate {
  name?: string;
  description?: string;
  matterType?: MatterType;
  owner?: string;
}

export interface Custodian {
  caseId: string;
  custodianId: string;
  custodianName?: string;
  addedAt?: string;
}

export interface CustodianAdd {
  custodianId: string;
  custodianName?: string;
}

export interface EvidenceItem {
  id: string;
  caseId: string;
  messageId: string;
  addedAt?: string;
  addedBy?: string;
  source?: string;
}

/**
 * A message as archive-service returns it (`GET /api/messages/{id}` and the
 * bulk `?ids=` read). The adapter reads raw Mongo documents, so the shape is
 * open-ended; these are the fields the UI relies on. `holds` is the hold
 * *set* — the source of truth for FR-4.4 hold status — and archive normalizes
 * it to `[]` (with `onHold: false`) when absent.
 */
export interface ArchiveMessage {
  id: string;
  holds?: string[];
  onHold?: boolean;
  subject?: string;
  type?: string;
  custodianId?: string;
  timestamp?: string;
  hasAttachment?: boolean;
}

export interface EvidenceBulkAddResult {
  added: number;
  skipped: number;
  addedMessageIds: string[];
}

export interface SavedSearch {
  id: string;
  caseId: string;
  name: string;
  spec: string;
  createdAt?: string;
}

export interface CaseDetail {
  id: string;
  name: string;
  description?: string;
  matterType: MatterType;
  owner: string;
  status: CaseStatus;
  createdAt: string;
  updatedAt?: string;
  version: number;
  custodians: Custodian[];
  evidence: EvidenceItem[];
  savedSearches: SavedSearch[];
  counts: { custodianCount: number; evidenceCount: number; savedSearchCount: number; heldCount: number };
}

// ---- holds (hold-service 8087) ----
export type HoldStatus = 'PENDING' | 'ACTIVE' | 'RELEASING' | 'RELEASED' | 'FAILED';

export interface Hold {
  holdId: string;
  caseId: string;
  status: HoldStatus;
  custodians: string[];
  from?: string;
  to?: string;
  terms?: string;
  heldCount: number;
  appliedCount: number;
  progressPct: number;
  placedBy?: string;
  placedAt?: string;
  releasedAt?: string;
}

export interface HoldCreate {
  caseId: string;
  custodians?: string[];
  from?: string;
  to?: string;
  terms?: string;
}

export interface RetentionPolicy {
  commType: string;
  duration: string;
}

// ---- ingest (ingest-service 8081) ----
export interface IngestParticipant {
  name?: string;
  address: string;
  role: string;
}

export interface IngestMessage {
  externalMessageId: string;
  type: 'EMAIL' | 'CHAT';
  subject?: string;
  body: string;
  threadId?: string;
  inReplyTo?: string;
  custodian: string;
  participants: IngestParticipant[];
  sentAt: string;
}

export interface IngestResult {
  messageId: string;
  status: string;
  correlationId?: string;
}

// ---- exports (export-service 8085) ----
export type ExportStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface ExportJob {
  jobId: string;
  caseId?: string;
  holdId?: string;
  status: ExportStatus;
  progressPct: number;
  packageS3Key?: string;
  error?: string;
  createdAt?: string;
}

export interface DownloadUrl {
  url: string;
  expiresAt: string;
}

export interface ExportVerify {
  valid: boolean;
  jobId: string;
  message?: string;
}

// ---- audit (audit-service 8086) ----
export interface AuditEvent {
  eventId: string;
  occurredAt: string;
  actor: string;
  action: string;
  targetType: string;
  targetId: string;
  caseId?: string;
  before?: string;
  after?: string;
  correlationId?: string;
  prevHash?: string;
  hash?: string;
}

export interface ChainVerification {
  caseId: string;
  valid: boolean;
  entries: number;
}

/**
 * `action` vocabulary actually emitted on `audit.events.v1` (events.md §6,
 * cross-checked against every `AuditEventPayload` producer). Listed in
 * logical groups so the audit-screen dropdown reads in the order an
 * investigator would scan: case lifecycle, holds, search, exports, retention.
 */
export const AUDIT_ACTIONS = [
  // case-service
  'CASE_CREATED',
  'CASE_TRANSITIONED',
  'CUSTODIAN_ADDED',
  'CUSTODIAN_REMOVED',
  'EVIDENCE_ADDED',
  'EVIDENCE_REMOVED',
  'SAVED_SEARCH_CREATED',
  'SAVED_SEARCH_DELETED',
  // hold-service
  'HOLD_PLACED',
  'HOLD_RELEASED',
  'DISPOSITION_RUN',
  'RETENTION_POLICY_UPDATED',
  // search-service
  'SEARCH_EXECUTED',
  // ingest-service
  'MESSAGE_INGESTED',
  // export-service
  'EXPORT_REQUESTED',
  'EXPORT_COMPLETED',
  'EXPORT_FAILED',
  'EXPORT_DOWNLOADED',
  'EXPORT_RETRIED',
] as const;
export type AuditAction = (typeof AUDIT_ACTIONS)[number];

/** `targetType` values actually emitted alongside the actions above. */
export const AUDIT_TARGET_TYPES = [
  'CASE',
  'CUSTODIAN',
  'EVIDENCE',
  'SAVED_SEARCH',
  'HOLD',
  'DISPOSITION_RUN',
  'RETENTION_POLICY',
  'SEARCH',
  'EXPORT',
  'MESSAGE',
] as const;
export type AuditTargetType = (typeof AUDIT_TARGET_TYPES)[number];

// ---- dashboard + disposition ----
export interface DashboardCounts {
  totalMessages: number;
  activeCases: number;
  activeHolds: number;
  exportsCompleted: number;
}

export interface DispositionRun {
  id?: string;
  startedAt?: string;
  finishedAt?: string;
  status?: string;
  deleted: number;
  skippedOnHold: number;
  errors: number;
  deletedByType?: Record<string, number>;
}
