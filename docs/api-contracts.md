# DiscoveryHub — API Contracts

Contract-first REST contracts for every backend service. Each service exposes a
**hardcoded stub** for its primary endpoint so the Angular frontend and downstream
services can integrate before any real logic exists (Day 1 requirement). Stubs return
fixed, contract-shaped JSON; they never touch a database, Kafka, or S3.

The shared error envelope for every 4xx/5xx response:

```json
{
  "timestamp": "2026-09-07T16:00:00Z",
  "status": 404,
  "error": "Not Found",
  "code": "MESSAGE_NOT_FOUND",
  "message": "No message for id 'abc'",
  "path": "/api/messages/abc"
}
```

Common health endpoints (identical on every service):

| Endpoint | Status | Body |
|---|---|---|
| `GET /health` | 200 | `{ "status": "UP" }` (liveness; always UP when the process is up) |
| `GET /actuator/health` | 200 | `{ "status": "UP", "components": { ... } }` (readiness; aggregates Kafka/Mongo/Postgres/ES health indicators — used by `docker-compose` healthchecks) |

Port map: ingest 8081 · archive 8082 · search 8083 · case 8084 · export 8085 · audit 8086 · api-gateway 8080 · hold 8087.

All business routes are mounted under `/api/**` and are reverse-proxied by the
api-gateway (`/api/**` → the owning service).

---

## ingest-service  (8081)

Owns acceptance + Kafka producer. Never writes to Mongo. Idempotency key =
`SHA-256(sourceSystem | externalId | canonicalBody | sortedParticipants | timestamp)`.

### POST /api/messages  — stub returns 202
Accept one communication, validate, publish `comm.ingest.v1`, return the deterministic
`messageId` (= `UUIDv5(namespace, messageKey)`).

Request:
```json
{
  "sourceSystem": "exchange-prod",
  "externalId": "ext-000001",
  "type": "EMAIL",
  "subject": "Q2 wire instructions",
  "body": "Please confirm the wire routing...",
  "timestamp": "2026-03-14T09:12:00Z",
  "threadId": "th-8891",
  "inReplyTo": null,
  "custodianId": "c-0007",
  "participants": [
    { "name": "Ada Byron", "address": "ada@fict.example", "role": "FROM" },
    { "name": "Bob Carr",   "address": "bob@fict.example", "role": "TO" }
  ],
  "attachments": [
    { "id": "a-1", "filename": "instructions.pdf",
      "contentType": "application/pdf", "size": 184223 }
  ]
}
```
Stub response — `202 Accepted`:
```json
{ "messageId": "00000000-0000-5f3c-8000-000000000000", "status": "ACCEPTED" }
```
Errors: `400 INVALID_MESSAGE` (missing required field / bad type).

### POST /api/messages:batch  — stub returns 202
Same body shape as `/api/messages` but an array; stub returns:
```json
{ "accepted": 0, "status": "ACCEPTED" }
```

### POST /admin/generate  — stub returns 202
Trigger corpus generation. Request: `{ "messages": 10000, "custodians": 25, "attachmentPct": 8 }`.
Stub response: `202 Accepted`, empty body.

---

## archive-service  (8082)

Owns the immutable message corpus in MongoDB + attachment bytes in S3. The only writer
of `messages`. Hold-guarded deletes (FR-4.2/FR-4.6).

### GET /api/messages/{id}  — implemented
Read the authoritative message document from Mongo (normalized: `_id`→`id`,
`timestamp` falls back to the ingest-written `sentAt`; `attachments[]` with
`filename`/`s3Key` is included when present).

Response — `200 OK`:
```json
{
  "id": "00000000-0000-5f3c-8000-000000000000",
  "messageKey": "sha256:stub",
  "type": "EMAIL",
  "subject": "Q2 wire instructions",
  "body": "Please confirm the wire routing...",
  "timestamp": "2026-03-14T09:12:00Z",
  "threadId": "th-8891",
  "custodianId": "c-0007",
  "participants": [
    { "name": "Ada Byron", "address": "ada@fict.example", "role": "FROM" },
    { "name": "Bob Carr",   "address": "bob@fict.example", "role": "TO" }
  ],
  "hasAttachment": true,
  "onHold": false,
  "holds": []
}
```
Errors: `404 MESSAGE_NOT_FOUND`.

### GET /api/messages?ids=id1,id2  — implemented
Bulk read, one round-trip (the export worker's message source). Unknown ids are
absent from the result: `{ "messages": [ /* message documents */ ] }`.

### DELETE /api/messages/{id}  — implemented (FR-4.2/FR-4.6)
Routed through the `RetentionGuard` protection proxy. Response: `204 No Content`.
Errors: `404 MESSAGE_NOT_FOUND`, `409 MESSAGE_ON_HOLD`
`{ "code": "MESSAGE_ON_HOLD", "holds": ["h-1"] }`.

### POST /internal/disposition/run  — implemented (FR-5, execution-plan Day 3 M4)
Service-to-service only — mounted under `/internal`, not `/api`, so the api-gateway has no
route for it and it is unreachable from outside the compose network. Called by
hold-service's `DispositionService` with one cutoff per configured retention policy; pages
`messages` with `sentAt < cutoff` per `commType`, routes every delete through the same
`RetentionGuard` `DELETE /api/messages/{id}` uses (so a held message is always counted
`skippedOnHold`, never deleted), and hands attachment S3 keys of deleted messages to the
`BlobStore`-backed `AttachmentCleaner`.

Request:
```json
{ "cutoffs": [ { "commType": "EMAIL", "cutoff": "2026-09-09T09:58:00Z" },
               { "commType": "CHAT", "cutoff": "2026-09-09T09:59:00Z" } ] }
```
Response — `200 OK`:
```json
{ "scanned": 42, "deleted": 30, "skippedOnHold": 12, "errors": 0, "deletedByType": { "EMAIL": 30 } }
```

---

## search-service  (8083)

Owns the Elasticsearch `messages` index. Stateless read API; ES is an index, never the
source of truth.

### POST /api/search  — implemented Day 1
Full-text + structured filter query.

Request:
```json
{
  "q": "wire transfer bahamas",
  "filters": {
    "from": "2026-01-01",
    "to": "2026-06-30",
    "types": ["EMAIL"],
    "custodians": ["c-0007"],
    "hasAttachment": true,
    "onHold": false
  },
  "sort": "RELEVANCE",
  "page": 0,
  "size": 25,
  "caseId": "case-42"
}
```
Stub response — `200 OK`:
```json
{
  "total": 1,
  "page": 0,
  "size": 25,
  "hits": [
    {
      "messageId": "00000000-0000-5f3c-8000-000000000000",
      "subject": "Q2 wire instructions",
      "snippet": "Please confirm the <mark>wire</mark> routing...",
      "score": 1.0,
      "custodianId": "c-0007",
      "timestamp": "2026-03-14T09:12:00Z",
      "onHold": false
    }
  ]
}
```

### POST /api/search/scope-ids  — stub returns 200
Stream all matching ids (PIT + `search_after`). Stub: `{ "ids": [] }`.

### POST /api/search/scope-count  — stub returns 200
Cheap count of a scope. Stub: `{ "count": 0 }`.

### GET /api/search?q=  — convenience GET
Query-string form of `POST /api/search` for the smoke script / demo resiliency proof.
Supports `q`, `page` (default 0), `size` (default 25). Response shape is identical to
`POST /api/search`. Routed by the api-gateway like the other `/api/search/**` paths.

### POST /admin/reindex  — stub returns 202
Proves ES is rebuildable, not the source of truth (architecture §3.4 — a likely
evaluator question). Day 1: returns `202` with a stub body; the real rebuild drops &
recreates the `messages` index and replays `comm.stored.v1` from offset 0 (or scans
the archive Mongo source), so the index can always be reconstructed from the durable
event log / source of truth.

Stub response — `202 Accepted`:
```json
{ "status": "ACCEPTED", "rebuilt": 0, "note": "Day 1 stub — full rebuild replays comm.stored.v1 from offset 0 to prove ES is rebuildable" }
```

---

## case-service  (8084)

Owns Postgres: cases, custodians, evidence, saved searches. Case lifecycle enforced by
`CaseStateMachine` (DRAFT→ACTIVE→UNDER_REVIEW→CLOSED). Holds and retention policy are
**not** owned here — see `hold-service` below (D-003, the 7-service split).

### POST /api/cases  — implemented
Create a case. Delegates to `CaseService.create`, persisted via JPA.

Request:
```json
{
  "name": "Project Phoenix",
  "description": "Q2 communications review",
  "matterType": "INVESTIGATION",
  "owner": "i-harriet"
}
```
Response — `201 Created`:
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "Project Phoenix",
  "description": "Q2 communications review",
  "matterType": "INVESTIGATION",
  "owner": "i-harriet",
  "status": "DRAFT",
  "createdAt": "2026-09-07T16:00:00Z",
  "updatedAt": "2026-09-07T16:00:00Z",
  "version": 0
}
```
Errors: `400 VALIDATION_ERROR` (missing `name` / `matterType`).

### GET /api/cases  — implemented
List cases, filterable by `status` and `q` (name substring, case-insensitive), paginated via
standard Spring `page`/`size`/`sort` query params. Response shape matches the Angular
`Page<T>` model exactly (`content`, `totalElements`, `totalPages`, `page`, `size`) — see
`PageResponse` in case-service, which re-shapes Spring Data's own `Page` JSON (which uses
`number`, not `page`) to match the frontend contract.
```json
{ "content": [ /* CaseResponse[] */ ], "totalElements": 1, "totalPages": 1, "page": 0, "size": 20 }
```

### GET /api/cases/{id}  — implemented
Returns the case; `404 NOT_FOUND` if it doesn't exist.

### POST /api/cases/{id}/transition  — implemented
Body: `{ "to": "ACTIVE" }`. Applies `CaseStateMachine.assertTransition` (State pattern,
FR-2.2); `CLOSED` is terminal (FR-2.5). Response: full `CaseResponse` with the new status.
Errors: `409 INVALID_TRANSITION` (illegal transition, including anything out of `CLOSED`),
`404 NOT_FOUND` (unknown case id).

Legal holds are **not** placed through case-service. `POST /api/holds` (routed by
api-gateway directly to hold-service, port 8087) is the real entry point — an earlier
`POST /api/cases/{id}/holds` design was superseded once hold-service became its own
independently deployable service with its own Postgres schema (NFR-1).

---

## hold-service  (8087)

Owns Postgres: `holds`, `retention_policies`, `disposition_runs`, `outbox`. Split out of
case-service (D-003) because legal hold + retention/disposition is a corpus-wide async
background concern with its own schedule and failure mode — not the same concern as
case-service's low-throughput transactional CRUD. All routes below are mounted under
`/api/holds/**`, which rides the api-gateway's single existing route to this service —
no gateway change was needed for retention-policy or disposition even though they are
not literally "holds."

### POST /api/holds  — implemented (FR-4.1/FR-4.3)
Place a hold, scoped by custodian(s), date range, and optional search terms. Persists a
`PENDING` hold row and a `hold.commands.v1{action:PLACE}` outbox row in one Postgres
transaction (D-008) and returns immediately — the archive hold-applier resolves the scope
and reports progress asynchronously.

Request:
```json
{ "caseId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "custodians": ["c-0007","c-0012"],
  "from": "2026-01-01T00:00:00Z", "to": "2026-06-30T00:00:00Z", "terms": "wire transfer",
  "placedBy": "i-harriet" }
```
Response — `202 Accepted`:
```json
{ "holdId": "h-1001", "caseId": "3fa85f64-…", "status": "PENDING", "custodians": ["c-0007","c-0012"],
  "from": "2026-01-01T00:00:00Z", "to": "2026-06-30T00:00:00Z", "terms": "wire transfer",
  "heldCount": 0, "appliedCount": 0, "progressPct": 0, "placedBy": "i-harriet",
  "placedAt": "2026-09-09T10:00:00Z", "releasedAt": null }
```
Errors: `400 VALIDATION_ERROR` (missing/invalid `caseId`).

### GET /api/holds[?caseId=]  — implemented
All holds, optionally filtered to one case, oldest first. Array of the response shape above.

### GET /api/holds/{holdId}  — implemented
Errors: `404 NOT_FOUND`.

### DELETE /api/holds/{holdId}  — implemented (FR-4.5)
Releases a hold: `202 Accepted`, flips to `RELEASING`, writes a `RELEASE` outbox row. The
archive hold-applier `$pull`s the hold id from each message's hold set; overlapping holds
keep the message protected until every hold covering it is released. Releasing an
already-`RELEASING`/`RELEASED` hold is a no-op (idempotent under double-clicks/redelivery).

### GET /api/holds/retention-policy  — implemented (FR-5.1)
```json
{ "policies": [ { "commType": "EMAIL", "duration": "P2555D" }, { "commType": "CHAT", "duration": "P1095D" } ] }
```
Seeded defaults are the realistic values FR-5.1 names (EMAIL 7 years, CHAT 3 years),
expressed in days because the column is parsed with `java.time.Duration`, which rejects
`P7Y`. They are deliberately longer than the corpus generator's 30-day back-dating window
so the FR-5.2 scheduled sweep can run continuously without making the seeded corpus
eligible — see `RetentionSeeder`. Retune to minutes live with the PUT below.

### PUT /api/holds/retention-policy/{commType}  — implemented
Upserts a policy so retention can be retuned live for the demo (e.g. set `EMAIL` to one
minute). Request: `{ "duration": "PT1M" }`. Response — `200 OK`: `{ "commType": "EMAIL", "duration": "PT1M" }`.

### POST /api/holds/disposition/run  — implemented (FR-5.2/FR-5.3)
Manual trigger for a disposition pass — the same `DispositionService` the `@Scheduled` job
calls. Returns as soon as the `RUNNING` ledger row is saved; the actual scan-and-delete
pass (compute one retention cutoff per configured policy, call archive-service's internal
executor below, page and guard-delete) runs on a background thread, since a full corpus
can take well beyond a request timeout. The Disposition screen polls `GET /runs`/`/runs/latest`
every 2 s until the row leaves `RUNNING`.

Response — `202 Accepted`:
```json
{ "id": "d-2001", "startedAt": "2026-09-09T10:00:00Z", "finishedAt": null,
  "status": "RUNNING", "scanned": 0, "deleted": 0, "skippedOnHold": 0, "errors": 0,
  "deletedByType": {} }
```
Errors: `409 DISPOSITION_ALREADY_RUNNING` (another pass — manual or scheduled — is in flight).

### GET /api/holds/disposition/runs | /runs/latest  — implemented
History (newest first) or just the latest run. Once the background pass finishes, the same
row reads back as (for example):
```json
{ "id": "d-2001", "startedAt": "2026-09-09T10:00:00Z", "finishedAt": "2026-09-09T10:00:01Z",
  "status": "COMPLETED", "scanned": 42, "deleted": 30, "skippedOnHold": 12, "errors": 0,
  "deletedByType": { "EMAIL": 30 } }
```
`runs/latest` errors `404 NOT_FOUND` if no run has ever executed.

---

## export-service  (8085)

Owns Postgres `export_jobs` + S3 export packages. Async job: QUEUED→RUNNING→COMPLETED/FAILED.
The worker (`export-worker` group) consumes `export.jobs.v1`: evidence ids from case-service →
message docs from archive bulk GET → attachment bytes from `discoveryhub-attachments` → zip
(Template Method packager, per-item SHA-256 via `DigestOutputStream`) → `discoveryhub-exports`
under `exports/{jobId}/package.zip`. Retries 3× with backoff, then `export.jobs.v1.DLT` + FAILED.

### POST /api/exports  — implemented (202)
Request: `{ "caseId": "case-42", "holdId": "h-1" }`.
Response — `202 Accepted`:
```json
{ "jobId": "…", "caseId": "case-42", "holdId": null, "status": "QUEUED", "progressPct": 0,
  "packageS3Key": null, "error": null, "createdAt": "2026-09-09T10:00:00Z" }
```
Errors: `400 INVALID_EXPORT_REQUEST` (neither `caseId` nor `holdId`).

### GET /api/exports[?caseId=]  — implemented
All jobs (optionally one case's), newest first, as an array of the response shape above.

### GET /api/exports/{id}  — implemented
Errors: `404 NOT_FOUND`.

### GET /api/exports/{id}/download-url  — implemented
Mints a pre-signed S3 GET URL (TTL 15 min):
```json
{ "url": "http://localstack:4566/discoveryhub-exports/exports/<jobId>/package.zip?X-Amz-…", "expiresAt": "2026-09-09T10:15:00Z" }
```
Errors: `404 NOT_FOUND`, `409 EXPORT_NOT_READY` (job not COMPLETED yet).

### POST /api/exports/{id}/verify  — implemented
Server-side integrity proof (FR-6): re-downloads the package, compares its SHA-256 with the
value recorded at completion, then re-hashes every item against `manifest.json`.
```json
{ "jobId": "<id>", "valid": true, "message": "All 12 item hashes and the package checksum match" }
```
Errors: `404 NOT_FOUND`, `409 EXPORT_NOT_READY`.

### GET /api/exports/{id}/stream  — implemented (SSE)
`text/event-stream` of named `progress` events (same JSON shape as the REST response),
replaying the current state on subscribe and closing after COMPLETED/FAILED.

---

## audit-service  (8086)

Append-only audit trail in MongoDB. **No write/POST/PUT/PATCH/DELETE API exists** —
that absence is itself a rubric item. Entries form a per-case SHA-256 hash chain.

### GET /api/audit  — implemented
Filter by any combination of `caseId`, `actor`, `action`, `targetType`, `from`, `to`
(ISO-8601 instants), paged with `page`/`size` (size capped at 500), ordered by `occurredAt`.
Errors: `400 INVALID_AUDIT_FILTER` (malformed `from`/`to`).

Response headers: `X-Total-Count` — entries matching the *filters* (ignoring `page`/`size`),
so a client can render "showing 26–50 of 100" and a page count. The body stays a bare array.

Response — `200 OK`:
```json
[
  {
    "eventId": "evt-0001",
    "occurredAt": "2026-09-07T16:00:00Z",
    "actor": "i-harriet",
    "action": "CASE_CREATED",
    "targetType": "Case",
    "targetId": "00000000-0000-4000-8000-000000000000",
    "caseId": "00000000-0000-4000-8000-000000000000",
    "before": null,
    "after": { "name": "Project Phoenix", "status": "DRAFT" },
    "correlationId": "corr-0001",
    "prevHash": "GENESIS",
    "hash": "0000000000000000000000000000000000000000000000000000000000000000"
  }
]
```

### GET /api/audit/verify-chain?caseId={id}  — implemented
Re-walks the per-case hash chain: `{ "caseId": "<id>", "valid": true, "entries": 42 }`.

---

## api-gateway  (8080)

Spring Cloud Gateway. Reverse-proxies `/api/**` (and archive's `/admin/**`) to the owning
service by path — current routes in `application.yml`:

| Path | Method | Routes to |
|---|---|---|
| `/api/messages/**` | POST | ingest-service (8081) |
| `/api/messages/**` | GET, DELETE | archive-service (8082) |
| `/admin/**` | * | archive-service (8082) — `POST /admin/reindex` |
| `/api/search/**`, `/api/dashboard/**` | * | search-service (8083) |
| `/api/cases/**`, `/api/retention-policy/**` | * | case-service (8084) |
| `/api/holds/**` | * | **hold-service (8087)** — holds, `/api/holds/retention-policy/**`, `/api/holds/disposition/**` |
| `/api/exports/**` | * | export-service (8085) |
| `/api/audit/**` | * | audit-service (8086) |

`/api/holds/**` is one route to hold-service (D-003) — retention-policy and disposition
ride it as sub-paths, not separate gateway entries, so hold-service's whole Day 1–3 buildout
needed zero gateway changes. The bare `/api/retention-policy/**` → case-service route above
predates the split and case-service no longer serves anything real behind it; it is not
used by the frontend, which calls `/api/holds/retention-policy` exclusively.

`GET /health` → `{ "status": "UP" }`; `GET /actuator/health` → aggregated UP.
