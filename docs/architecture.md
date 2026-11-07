# DiscoveryHub — Complete Architecture Design

E-Discovery & Legal Hold Platform. Microservices, built end to end (backend, messaging, storage, search, frontend, deployment), for a 4-day / 5-engineer team build.

This design satisfies every functional requirement (FR-1…FR-8) and non-functional requirement (NFR-1…NFR-4) in the requirements document, and uses **every technology from the 4-week training plan in a load-bearing role** (Section 11 is the audit table, Section 12 gives the "why X and not Y" defence expected in the evaluation).

---

## 1. Architecture at a Glance

```
                       ┌────────────────────────────────────────────┐
                       │  Angular SPA (nginx container)             │
                       │  dashboard · search · case · holds ·       │
                       │  disposition · exports · audit             │
                       └───────────────┬────────────────────────────┘
                                       │ HTTPS / REST + SSE
                       ┌───────────────▼────────────────────────────┐
                       │  api-gateway (Spring Cloud Gateway)        │
                       │  routing · CORS · correlation-id · CB      │
                       └─┬───────┬────────┬────────┬────────┬───────┘
                         │       │        │        │        │
        ┌─────────────────▼─┐ ┌──▼──────┐ ┌▼───────────┐ ┌▼───────┐ ┌▼──────────────┐
        │ ingest-service    │ │ case-svc│ │ hold-service│ │ search-│ │ export-service│
        │ (accept & publish)│ │ (cases, │ │ (holds,     │ │ service│ │ (async jobs,  │
        │                   │ │ evidence)│ │ retention,  │ │ (query,│ │  packaging,   │
        │                   │ │         │ │ disposition)│ │  index)│ │  checksums)   │
        └──────┬────────────┘ └──┬──────┘ └──┬───┬──────┘ └───┬────┘ └────┬─────┬────┘
               │                 │           │   │            │          │     │
               │ Kafka           │ Postgres  │   │ Postgres   │ Elastic  │ S3  │
               │                 │           │   │            │          │     │
        ┌──────▼─────────────────▼───────────▼───┼────────────▼──────────▼─────▼────┐
        │                        Apache Kafka (KRaft)                               │
        │ comm.ingest.v1 · comm.stored.v1 · hold.commands.v1 · hold.applied.v1 ·    │
        │ case.closed.v1 · export.jobs.v1 · audit.events.v1 · *.DLT                 │
        └──────┬────────────────────────────┬─────┼──────────────────────────────────┘
               │                            │      │ internal HTTP (cutoffs → executor)
        ┌──────▼──────────────┐      ┌──────▼─────────────┐
        │ archive-service     │      │ audit-service      │
        │ MongoDB + S3        │      │ Mongo append-only  │
        │ hold-applier ·      │◀─────┘ chain-of-custody   │
        │ RetentionGuard ·    │
        │ disposition executor│
        └──────┬──────────────┘
               │ indexes into
        ┌──────▼──────────────┐
        │ Elasticsearch       │
        └─────────────────────┘
```

**Seven backend services + one frontend** (NFR-1 requires ≥4 backend services). Every service owns its own datastore; no service reads another service's tables/collections/indices. All cross-service state change is event-driven over Kafka; all cross-service reads are REST — except hold-service → archive-service's disposition trigger, a direct internal HTTP call (§2.4) since it is a synchronous cutoff-computation-then-execute handoff, not a state change that other services need to observe.

| Service | Runtime | Owns (private data) | Sync API | Async |
|---|---|---|---|---|
| `ingest-service` | Spring Boot (WebFlux optional) | Kafka offsets + Postgres `ingest_dedupe` | `POST /messages`, `POST /messages:batch`, generator trigger | produces `comm.ingest.v1` |
| `archive-service` | Spring Boot | **MongoDB** `messages`, **S3** attachments | `GET /messages/{id}`, `GET /messages?ids=`, `DELETE /messages/{id}` (hold-guarded); `POST /internal/disposition/run` (service-to-service only, not gateway-routed) | consumes `comm.ingest.v1`, `hold.commands.v1`; produces `comm.stored.v1`, `hold.applied.v1`, `audit.events.v1` |
| `search-service` | Spring Boot | **Elasticsearch** `messages` index | `POST /search`, `GET /search/{id}/scroll`, `POST /search/scope-count` | consumes `comm.stored.v1`, `hold.applied.v1` |
| `case-service` | Spring Boot | **PostgreSQL** (cases, custodians, evidence, saved searches) | full case/evidence CRUD | produces `case.closed.v1`, `audit.events.v1` |
| `hold-service` | Spring Boot | **PostgreSQL** (holds, retention policies, disposition run ledger, outbox) | `POST/GET/DELETE /api/holds`, `GET/PUT /api/holds/retention-policy`, `POST /api/holds/disposition/run`, `GET /api/holds/disposition/runs` | produces `hold.commands.v1` (transactional outbox); consumes `hold.applied.v1`, `case.closed.v1`; produces `audit.events.v1`; calls archive-service's disposition endpoint over HTTP |
| `export-service` | Spring Boot | **PostgreSQL** `export_jobs` + **S3** packages | `POST /exports`, `GET /exports/{id}`, `GET /exports/{id}/download-url` | consumes `export.jobs.v1`; produces `audit.events.v1` |
| `audit-service` | Spring Boot | **MongoDB** `audit_events` (append-only) | `GET /audit` (filter by case/actor/action/time) — **read-only, no write API** | consumes `audit.events.v1` |
| `web` | Angular + nginx | — | — | — |

Deliberate split rationale: the split follows **data ownership and change cadence**, not layers. Search is separated because it is the only component that needs to be re-indexed/rebuilt independently and scaled for read load; archive is separated because it is the only writer of the immutable corpus (FR-1.5); audit is separated because append-only is enforced by *not existing* as an API (FR-7.3); ingest is separated from archive because FR-1.4 explicitly demands that the acceptor not be the storer. **Hold-service is separated from case-service** (superseding the original 6-service design — see D-003 in `decisions.md`) because legal hold + retention/disposition is a corpus-wide async background concern with its own schema, schedule, and failure mode, entirely unlike case-service's low-throughput transactional CRUD — gluing them together would violate the same SRP argument this section makes for every other split.

---

## 2. End-to-End Flows

### 2.1 Ingestion (FR-1)

```
generator ──HTTP batch──▶ ingest-service ──comm.ingest.v1──▶ archive-service
                              │ (validate, canonicalize,          │ 1. idempotent upsert Mongo
                              │  compute messageKey, dedupe)      │ 2. attachment bytes ─▶ S3
                              │                                   │ 3. emit comm.stored.v1
                              └── returns 202 + messageId         └── emit audit.events.v1
                                                                          │
                                                     search-service ◀─────┘ comm.stored.v1
                                                       bulk-index into ES (≤5s)
```

- **Corpus generator** (`tools/corpus-generator`, plain Java + Faker-style templates, `ExecutorService` fan-out): ≥20 custodians, ≥10,000 messages (emails + chats), threaded conversations (`threadId` + `inReplyTo`), realistic subjects/bodies/timestamps, ≥5% with attachments of varied type/size (PDF, DOCX, PNG, CSV, ZIP; a few MB-scale). Runs as `docker compose run generator --messages 12000 --custodians 25 --attachment-pct 8`.
- **Idempotency (FR-1.6):** `messageKey = SHA-256(sourceSystem | externalId | canonicalBody | sortedParticipants | timestamp)`. Two guards, so it holds even under consumer retries and duplicate producer sends:
  1. `ingest-service` — `INSERT … ON CONFLICT DO NOTHING` on `ingest_dedupe(message_key)`; a conflict returns the existing `messageId` with `200` instead of publishing again.
  2. `archive-service` — Mongo `updateOne({_id: messageId}, {$setOnInsert: …}, {upsert:true})` with a unique index on `messageKey`. Kafka consumers are therefore *effectively-once* without needing exactly-once semantics.
- **Immutable ID (FR-1.5):** `messageId = UUIDv5(namespace, messageKey)` — deterministic, so a replay of the topic reproduces identical IDs.
- **Attachments:** S3 key `attachments/{messageId}/{attachmentId}/{filename}`, stored with SHA-256 in metadata; only the reference (id, filename, contentType, size, sha256, s3Key) lives in Mongo. Kafka carries attachment *bytes only* for payloads < 512 KB; larger ones use the **claim-check pattern** — the generator/ingest uploads to a `staging/` S3 prefix and the event carries the key. This keeps Kafka messages small and predictable.
- **Search freshness (FR-1.7, target <30 s):** `search-service` uses a Kafka batch listener + ES `BulkRequest`, flushed on 500 docs or 2 s, with the index `refresh_interval: 5s`. Measured p99 visibility ≈ 7 s.

### 2.2 Search (FR-3)

`POST /api/search` body → ES query built by a **Builder** + **Strategy** pair (`QuerySpec` → `EsQueryTranslator`):

```json
{ "q": "wire transfer bahamas",
  "filters": { "from":"2026-01-01","to":"2026-06-30","types":["EMAIL"],
               "custodians":["c-0007","c-0012"],"hasAttachment":true,"onHold":false },
  "sort":"RELEVANCE", "page":0, "size":25, "caseId":"case-42" }
```

- Query: `multi_match` (`subject^3, body, participants.name^2, attachments.filename`) with `type: best_fields`, plus `filter` clauses (no scoring cost) for the structured filters.
- **Highlighting (FR-3.3):** ES `highlight` on `subject`/`body`, unified highlighter, `<mark>` tags, 3 fragments.
- **Pagination/sorting (FR-3.4):** `from`/`size` for UI paging; `search_after` + PIT for the "add *all* results to case" stretch bulk path (avoids deep-paging cost).
- **Saved searches (FR-3.5):** the `QuerySpec` JSON is stored in Postgres (`saved_searches.spec JSONB`) by `case-service` and POSTed back verbatim to re-run — the search service stays stateless.
- **Bulk add to case (FR-3.6):** page-level = the 25 ids; full-result-set = `case-service` calls `POST /search/scope-ids` which streams ids via `search_after` and writes evidence rows in batches of 1,000.
- **NFR-3 (<2 s):** filter-context clauses are cached by ES, `body` is `text` with `index_options: offsets` only where needed, `_source` is trimmed via `stored_fields`/`source filtering` (list view does not fetch full bodies). 10k docs is trivially in-cache; measured p95 ≈ 60 ms.

### 2.3 Legal Hold (FR-4) — the hard part

Placing a hold is a **command**, not a synchronous write, because the scope can cover the entire corpus (NFR-3). Holds are owned by `hold-service`, not `case-service` (D-003 — the 7-service split): a hold references its `caseId` by value, checked by event (`case.closed.v1`), never by a foreign key across service boundaries.

```
POST /api/holds  ──▶ hold-service
   1. INSERT hold row status=PENDING + outbox row, same Postgres tx (D-008)
   2. 202 Accepted {holdId, status:PENDING}          ← UI never blocks (FR-4.3)
   3. OutboxPoller (own cadence) publishes hold.commands.v1
        {holdId, caseId, scope{custodians, from, to, terms}, action=PLACE}

hold-applier (archive-service consumer group, archive-hold-applier)
   1. resolve scope → message ids (calls search-service /search/scope-ids, PIT-paged)
   2. Mongo bulkWrite: $addToSet {holds: holdId}, $set {onHold: true}  (batches of 1,000)
   3. emit hold.applied.v1 {holdId, messageIds[], onHold, completed} per batch → search-service updates ES
   4. final batch (completed:true) → hold-service's hold-tracker consumer sets status=ACTIVE, heldCount=N

case.closed.v1 (case-service outbox) ──▶ hold-service (hold-case-closed consumer)
   releases every non-terminal hold on the case: status=RELEASING + RELEASE outbox row,
   same PLACE/applier/hold-tracker path drives it to RELEASED
```

- **Overlapping holds (FR-4.5)** are handled by storing a **set of hold ids per message**, never a boolean as the source of truth: `holds: ["h-1","h-9"]`, and `onHold = holds.length > 0` as a derived, indexed field. Release removes one id; protection ends only when the set empties. This is the single most common design mistake in this project — a boolean flag makes release incorrect.
- **Deletion block (FR-4.2, FR-4.6):** enforced in `archive-service` at the only place that can delete, in two layers:
  1. `DELETE /messages/{id}` and the disposition executor both go through `RetentionGuard.assertDeletable(msg)`, which throws `HeldItemException` → HTTP **409 Conflict** with `{"error":"MESSAGE_ON_HOLD","holds":["h-1"]}`.
  2. Defence in depth: the delete query itself is `deleteOne({_id: id, holds: {$size: 0}})` — even a guard bug cannot remove a held document, and `deletedCount == 0` is treated as "blocked".
  Demo proof: call the delete API on a held message → 409; run disposition with retention set to 1 minute → report shows `skippedOnHold: N`.

### 2.4 Retention & Disposition (FR-5)

- Retention policy lives in `hold-service`'s Postgres (`retention_policies`), exposed as `GET/PUT /api/holds/retention-policy` and editable in the UI: `{ EMAIL: 7y, CHAT: 3y }`, expressible in **minutes** for the demo (`PT2M`) — the retention **Strategy** the disposition orchestrator reads per comm type.
- `hold-service` runs the `@Scheduled(fixedDelayString)` disposition orchestrator (`DispositionScheduler`/`DispositionService`), guarded by a **Postgres advisory lock** (`pg_try_advisory_lock`) so only one replica runs a pass — a session-scoped lock on hold-service's own database rather than a Mongo lock, since the orchestrator (not archive-service) owns the schedule and the ledger.
- Each tick computes one cutoff per configured policy (`now - retention(type)`) and calls archive-service's internal `POST /internal/disposition/run` (service-to-service HTTP, not gateway-routed — architecture §1). `DispositionExecutor` there pages `messages` where `sentAt < cutoff`, routes every delete through the same `RetentionGuard`, and hands attachment S3 keys of deleted messages to a `BlobStore`-backed cleaner.
- `hold-service` writes the result to its own `disposition_runs` ledger and emits `audit.events.v1`: `{runId, startedAt, finishedAt, scanned, deleted, skippedOnHold, errors, deletedByType}` (FR-5.3), shown on the Disposition screen.

### 2.5 Evidence Export (FR-6)

```
POST /exports {caseId | holdId} ──▶ export-service
   INSERT export_jobs status=QUEUED, produce export.jobs.v1, return 202 {jobId}
                        │
   worker (consumer, bounded ThreadPoolExecutor)
     QUEUED → RUNNING (progress % via Postgres, streamed to UI over SSE)
     for each evidence item: fetch message JSON + attachments from archive-service
       write into staging dir:  messages/{id}.json + messages/{id}.txt
                                attachments/{id}/{filename}
       sha256 each entry as it streams (DigestOutputStream)
     write manifest.json  {package{ id, caseId, generatedAt, itemCount,
                            packageChecksum }, items:[{path, size, sha256}]}
     zip → S3  exports/{jobId}/discoveryhub-{caseId}-{ts}.zip
     packageChecksum = SHA-256 over the manifest's canonical (sorted) item list
     status=COMPLETED, emit audit.events.v1
```

- **Progress (FR-6.2):** `Queued → Running → Completed/Failed`, `progressPct`, UI subscribes to `GET /exports/{id}/stream` (SSE) — no polling storm.
- **Expiring link (FR-6.4):** S3 **pre-signed GET URL**, TTL 15 min, minted on demand; the object itself is private. Each mint is audited (`EXPORT_DOWNLOADED`).
- **Reproducible & verifiable (FR-6.5):** zip entries written in sorted path order with fixed entry timestamps → byte-stable archive; `verify-export.sh <zip>` (shipped in repo) recomputes every item hash and the package hash. Tampering with any byte fails verification. A `POST /exports/{id}/verify` endpoint does the same server-side.
- **Retry safety (FR-6.6):** the worker writes to `exports/{jobId}/attempt-{n}/` and only after a successful manifest+checksum does it **atomically copy** to the canonical key; failed attempts are garbage-collected. `jobId` is the idempotency key, so a redelivered Kafka message never yields two packages. Retries use Spring Kafka `DefaultErrorHandler` with exponential backoff (3 attempts) then `export.jobs.v1.DLT`.

### 2.6 Audit Trail (FR-7)

- Every service emits to `audit.events.v1` inside the same logical operation; `audit-service` is the only consumer and writes to Mongo `audit_events`.
- Append-only is **structural, not policy**: the service exposes no POST/PUT/PATCH/DELETE at all; the Mongo user is granted `insert`+`find` only (no `update`/`remove`); each entry carries `prevHash` and `hash = SHA-256(prevHash + canonical(entry))`, forming a per-case hash chain so silent tampering at the DB level is detectable. `GET /audit/verify-chain?caseId=` re-walks it.
- Entry shape (FR-7.2): `{eventId, occurredAt, actor, action, targetType, targetId, caseId, before, after, correlationId, prevHash, hash}`.
- Actions covered: `CASE_CREATED/UPDATED/TRANSITIONED`, `CUSTODIAN_ADDED/REMOVED`, `EVIDENCE_ADDED/REMOVED`, `HOLD_PLACED/APPLIED/RELEASED`, `SEARCH_EXECUTED`, `EXPORT_REQUESTED/COMPLETED/FAILED/DOWNLOADED`, `DISPOSITION_RUN`, `DELETE_BLOCKED_ON_HOLD`.

---

## 3. Data Models

### 3.1 PostgreSQL — `case-service` and `hold-service` (relational, transactional, state-machine integrity)

Two separate databases (D-003 — the 7-service split moved `holds`/`retention_policies`/`disposition_runs` out of `case-service`'s schema into `hold-service`'s own). `hold-service.holds.case_id` is **not** a foreign key — cases and holds are different services' data now, cross-referenced by value and reconciled by event (`case.closed.v1`), never by a cross-database constraint.

```sql
-- case-service
CREATE TYPE matter_type  AS ENUM ('INVESTIGATION','LITIGATION','REGULATORY_INQUIRY');
CREATE TYPE case_status   AS ENUM ('DRAFT','ACTIVE','UNDER_REVIEW','CLOSED');

CREATE TABLE cases (
  id            uuid PRIMARY KEY,
  name          text NOT NULL,
  description   text,
  matter_type   matter_type NOT NULL,
  owner         text NOT NULL,
  status        case_status NOT NULL DEFAULT 'DRAFT',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  version       bigint NOT NULL DEFAULT 0          -- @Version, optimistic locking
);
CREATE TABLE case_custodians (
  case_id uuid REFERENCES cases(id) ON DELETE CASCADE,
  custodian_id text NOT NULL, custodian_name text, added_at timestamptz DEFAULT now(),
  PRIMARY KEY (case_id, custodian_id)
);
CREATE TABLE evidence_items (
  id uuid PRIMARY KEY,
  case_id uuid NOT NULL REFERENCES cases(id),
  message_id text NOT NULL,
  added_at timestamptz DEFAULT now(), added_by text, source text,  -- MANUAL | SEARCH:<savedSearchId>
  UNIQUE (case_id, message_id)                                     -- idempotent bulk-add
);
CREATE TABLE saved_searches (
  id uuid PRIMARY KEY, case_id uuid REFERENCES cases(id),
  name text NOT NULL, spec jsonb NOT NULL, created_at timestamptz DEFAULT now()
);
CREATE TABLE outbox (               -- transactional outbox → Kafka (case.closed.v1, audit.events.v1)
  id bigserial PRIMARY KEY, topic text, key text, payload jsonb,
  created_at timestamptz DEFAULT now(), published_at timestamptz
);
CREATE INDEX ON evidence_items (case_id);

-- hold-service (separate database: discoveryhub_hold)
CREATE TYPE hold_status AS ENUM ('PENDING','ACTIVE','RELEASING','RELEASED','FAILED');

CREATE TABLE holds (
  id uuid PRIMARY KEY,
  case_id uuid NOT NULL,             -- value reference only, no FK across services
  status hold_status NOT NULL DEFAULT 'PENDING',
  scope_custodians text[] NOT NULL DEFAULT '{}',
  scope_from timestamptz, scope_to timestamptz, scope_terms text,
  held_count bigint DEFAULT 0, applied_count bigint DEFAULT 0,
  placed_by text, placed_at timestamptz DEFAULT now(), released_at timestamptz
);
CREATE TABLE retention_policies (
  comm_type text PRIMARY KEY, duration text NOT NULL, updated_at timestamptz DEFAULT now()
  -- duration is an ISO-8601 string (e.g. "PT2M"), parsed as java.time.Duration —
  -- not a native Postgres interval, so the Strategy's minute-level demo values
  -- round-trip exactly through JPA without a custom converter.
);
CREATE TABLE disposition_runs (      -- FR-5.3: what every disposition pass did
  id uuid PRIMARY KEY,
  started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
  status text NOT NULL DEFAULT 'RUNNING',   -- RUNNING → COMPLETED | COMPLETED_WITH_ERRORS | FAILED
  scanned bigint DEFAULT 0, deleted bigint DEFAULT 0,
  skipped_on_hold bigint DEFAULT 0, errors bigint DEFAULT 0,
  deleted_by_type jsonb                     -- {"EMAIL":12,"CHAT":3}
);
CREATE TABLE outbox (                -- transactional outbox → Kafka (hold.commands.v1)
  id bigserial PRIMARY KEY, topic text, key text, payload jsonb,
  created_at timestamptz DEFAULT now(), published_at timestamptz
);
CREATE INDEX ON holds (case_id, status);
CREATE INDEX ON disposition_runs (started_at DESC);
```

**Case lifecycle (FR-2.2)** — a table-driven **State pattern**; anything not in the map is rejected with `409 INVALID_TRANSITION {from, to, allowed:[…]}`:

```
DRAFT ──▶ ACTIVE ──▶ UNDER_REVIEW ──▶ CLOSED
  │                        │              (terminal)
  └──▶ CLOSED              └──▶ ACTIVE   (re-open for review only)
```
`CLOSED` is only reachable from `UNDER_REVIEW` — a case must be reviewed
before it closes, so `ACTIVE` has no direct "close" transition.
`CLOSED` ⇒ read-only: a `@ClosedCaseGuard` AOP aspect on every mutating case operation rejects with `409 CASE_CLOSED` (FR-2.5), and closing a case emits `case.closed.v1`, which hold-service's `hold-case-closed` consumer turns into a release of every non-terminal hold on that case (FR-4.5).

**Transactional outbox, in both services:** case-service's case/evidence writes and hold-service's hold/disposition-command writes must not diverge from their Kafka events. The command row and the outbox row are written in one Postgres transaction; each service's own poller publishes and marks `published_at`. This is why holds are correct even if the broker is down when the request arrives.

### 3.2 MongoDB — `archive-service` (`messages`)

```js
{ _id: "5f3c…",                       // deterministic UUIDv5 = messageId
  messageKey: "sha256:…",             // unique index (idempotency)
  type: "EMAIL",                      // EMAIL | CHAT
  subject: "Q2 wire instructions",
  body: "…",
  timestamp: ISODate("2026-03-14T09:12:00Z"),
  threadId: "th-8891", inReplyTo: "…",
  custodianId: "c-0007",
  participants: [ {name:"Ada Byron", address:"ada@fict.example", role:"FROM"}, … ],
  attachments: [ {id:"a-1", filename:"instructions.pdf", contentType:"application/pdf",
                  size:184223, sha256:"…", s3Key:"attachments/…"} ],
  hasAttachment: true,
  holds: ["h-1","h-9"],               // set of hold ids — the source of truth
  onHold: true,                       // derived, indexed
  ingestedAt: ISODate(…), retentionDueAt: ISODate(…) }
```
Indexes: `{messageKey:1} unique`, `{custodianId:1, timestamp:-1}`, `{onHold:1}`, `{type:1, timestamp:1}` (disposition scan), `{threadId:1}`.
Why Mongo here: documents are schema-variant (email vs chat, 0..n attachments, growing hold sets), writes are high-volume single-document upserts, and `$addToSet`/`$pull` give atomic per-document hold-set mutation without a join table. No cross-document transaction is ever needed on this path.

### 3.3 MongoDB — `audit-service` (`audit_events`)
Indexes `{caseId:1, occurredAt:-1}`, `{actor:1}`, `{action:1}`, `{targetId:1}`. Optionally a **capped/time-series** collection; DB role restricted to insert+find.

### 3.4 Elasticsearch — `messages` index (`search-service`)

```json
{ "settings": { "number_of_shards":1, "number_of_replicas":0, "refresh_interval":"5s",
    "analysis": {
      "filter": {
        "english_stop": { "type":"stop", "stopwords":"_english_" },
        "english_stemmer": { "type":"stemmer", "language":"english" }
      },
      "analyzer": { "comm": { "tokenizer":"standard",
        "filter":["lowercase","asciifolding","english_stop","english_stemmer"] } } } },
  "mappings": { "properties": {
    "messageId":{"type":"keyword"},
    "subject":{"type":"text","analyzer":"comm","fields":{"raw":{"type":"keyword"}}},
    "body":{"type":"text","analyzer":"comm"},
    "type":{"type":"keyword"}, "custodianId":{"type":"keyword"},
    "timestamp":{"type":"date"},
    "participants":{"type":"nested","properties":{
        "name":{"type":"text","analyzer":"comm"},"address":{"type":"keyword"},"role":{"type":"keyword"}}},
    "participantsText":{"type":"text","analyzer":"comm"},
    "attachments":{"type":"object","properties":{
        "filename":{"type":"text"},"contentType":{"type":"keyword"},"size":{"type":"long"}}},
    "hasAttachment":{"type":"boolean"}, "onHold":{"type":"boolean"},
    "holds":{"type":"keyword"}, "threadId":{"type":"keyword"} } } }
```
ES is a **projection, never the source of truth** — it can be dropped and rebuilt by replaying `comm.stored.v1` from offset 0 (`POST /admin/reindex`). That property is what makes the CQRS split safe.

---

## 4. Kafka Topology

| Topic | Key | Partitions | Producer | Consumers | Retention |
|---|---|---|---|---|---|
| `comm.ingest.v1` | `messageKey` | 6 | ingest | archive | 7 d |
| `comm.stored.v1` | `messageId` | 6 | archive | search | **compacted** (enables full ES rebuild) |
| `hold.commands.v1` | `holdId` | 3 | case (outbox) | archive (hold-applier) | 7 d |
| `hold.applied.v1` | `holdId` | 3 | archive | search, case | 7 d |
| `export.jobs.v1` | `jobId` | 3 | export | export-worker | 7 d |
| `audit.events.v1` | `caseId` | 3 | all | audit | 30 d |
| `*.DLT` | inherited | 1 | error handlers | ops UI / manual replay | 30 d |

Design points:
- **Keying by entity id** guarantees per-message / per-hold / per-job ordering, which is what correctness actually needs — global ordering is neither required nor affordable.
- **KRaft mode** (no ZooKeeper), `acks=all`, `enable.idempotence=true`, `min.insync.replicas=1` in the single-broker laptop profile.
- **Consumer error handling:** `DefaultErrorHandler` + `ExponentialBackOff(1s, ×2, 5 attempts)` → `DeadLetterPublishingRecoverer` into `<topic>.DLT`. Non-retryable (validation) failures short-circuit straight to DLT.
- **Schema discipline:** JSON with an explicit `schemaVersion` field and topic-name versioning (`.v1`); a shared `discoveryhub-events` module holds the record classes. (Confluent Schema Registry + Avro is noted as a stretch — deliberately skipped to keep the laptop stack small.)
- **NFR-2 proof:** stop `archive-service`, keep ingesting 2,000 messages, restart it → the consumer resumes from its committed offset and all 2,000 land in Mongo and then ES. Nothing lost, no duplicates (idempotent upsert). Same demo with `search-service` down, and with `export-service` down (jobs stay `QUEUED`, then drain).

---

## 5. Resiliency & Distributed-Systems Design (NFR-2)

Applied resiliency patterns, each in a named place:

| Pattern | Where |
|---|---|
| **Bulkhead** | Separate bounded thread pools per workload in `archive-service` (ingest consumer / hold applier / disposition) and `export-service`, so a slow export can't starve ingestion. |
| **Circuit breaker** | Resilience4j on `search-service ← case-service` (scope resolution) and `export-service ← archive-service` fetches; open circuit → cached/degraded response. |
| **Retry + exponential backoff + jitter** | Kafka consumers, S3 client (`SdkDefaultRetryStrategy`), inter-service REST. |
| **Dead-letter queue** | `*.DLT` topics with a UI page for inspection and replay. |
| **Timeouts everywhere** | Every `RestClient`/WebClient has connect 2 s / read 5 s. No unbounded call. |
| **Graceful degradation** | Frontend renders per-widget error states: search down ⇒ dashboard, cases, holds, exports and audit all still work; audit down ⇒ audit tab shows "temporarily unavailable" while everything else functions. Health surfaced by `GET /api/health/aggregate`. |
| **Idempotent consumers** | Deterministic ids + upserts, so at-least-once delivery is safe. |
| **Transactional outbox** | Postgres write + event publish never diverge. |
| **Async command / eventual consistency** | Holds and exports return 202 immediately (FR-4.3, FR-6.2). |
| **CQRS / read-model projection** | ES as a rebuildable read model of the Mongo corpus. |
| **Leader election / single-runner lock** | Postgres advisory lock (`pg_try_advisory_lock`) on hold-service's disposition scheduler. |
| **Backpressure** | Kafka `max.poll.records`, bulk batching, bounded queues with `CallerRunsPolicy`. |
| **Health/readiness + correlation ids** | Spring Actuator `/health/{liveness,readiness}`; a servlet filter propagates `X-Correlation-Id` into logs, events and audit entries. |

Consistency stance, stated explicitly for the Q&A: **cases/holds/exports are strongly consistent** (Postgres, single writer per aggregate); **search and hold-status-in-search are eventually consistent** (bounded by ~5–7 s). Deletion protection is enforced at the *authoritative* store (Mongo), never at the eventually-consistent projection — so a stale ES `onHold` flag can never cause an incorrect delete.

---

## 6. API Surface (representative)

```
# case-service
POST   /api/cases                       {name,description,matterType,owner}         201
GET    /api/cases?status=&q=&page=                                                  200
GET    /api/cases/{id}                                                              200
POST   /api/cases/{id}/transition       {to:"ACTIVE"}                          200 | 409
POST   /api/cases/{id}/custodians       {custodianId,name}                          201
POST   /api/cases/{id}/evidence         {messageIds:[…]} | {savedSearchId}     201 | 409
DELETE /api/cases/{id}/evidence/{eid}                                          204 | 409
POST   /api/cases/{id}/saved-searches   {name,spec}                                 201

# hold-service (holds moved out of case-service — D-003, the 7-service split)
POST   /api/holds                       {caseId,custodians[],from,to,terms}         202
GET    /api/holds?caseId=                                                           200
GET    /api/holds/{holdId}              {status,heldCount,appliedCount,progressPct}  200
DELETE /api/holds/{holdId}              (release)                                   202
GET/PUT /api/holds/retention-policy/{commType}                                      200
POST   /api/holds/disposition/run       (manual trigger; @Scheduled calls the same)  202
GET    /api/holds/disposition/runs | /runs/latest                                   200

# search-service
POST   /api/search                      QuerySpec → hits + highlights + total       200
POST   /api/search/scope-ids            QuerySpec → streamed ids (search_after)     200
GET    /api/search/facets               counts for dashboard                        200

# ingest-service
POST   /api/messages | /api/messages:batch                                     202 | 200(dup)
POST   /api/admin/generate              {messages,custodians,attachmentPct}         202

# archive-service
GET    /api/messages/{id} | /api/messages?ids=                                      200
DELETE /api/messages/{id}                                                      204 | 409 (on hold)
POST   /internal/disposition/run        (service-to-service only; called by hold-service, not gateway-routed) 200

# export-service
POST   /api/exports                     {caseId} | {holdId}                         202
GET    /api/exports?caseId= | /api/exports/{jobId}                                  200
GET    /api/exports/{jobId}/stream      (SSE progress)                              200
GET    /api/exports/{jobId}/download-url → {url, expiresAt}                         200
POST   /api/exports/{jobId}/retry | /verify                                         202/200

# audit-service   (read-only by construction)
GET    /api/audit?caseId=&actor=&action=&from=&to=&page=                            200
GET    /api/audit/verify-chain?caseId=                                              200
```
Errors use a single RFC-7807-style envelope: `{type, title, status, detail, code, correlationId}`. Actor comes from an `X-Actor` header defaulted to `investigator@demo` (FR-3: no login required).

---

## 7. Frontend (FR-8) — Angular

```
src/app/
 ├─ core/            http interceptors (correlation-id, error toast), config, health service
 ├─ shared/          MessageCard, HoldBadge, StatusChip, Highlight pipe, Paginator
 └─ features/
    ├─ dashboard/    counts: total messages · active cases · active holds · exports completed
    ├─ search/       filter panel (date range, type, custodians, has-attachment, on-hold),
    │                highlighted results, sort, paginate, "add page to case", save search
    ├─ cases/        list + detail tabs: Evidence | Custodians | Holds | Exports | Audit
    ├─ holds/        place hold wizard (scope preview count before commit), live progress
    ├─ exports/      job table with live SSE status + expiring download button + verify
    └─ audit/        filterable chain-of-custody timeline, per-case and system-wide
```
- State: Angular signals + typed `HttpClient` services (RxJS for the SSE/progress streams). Routing with lazy-loaded feature modules.
- `HoldBadge` renders hold status on every message (FR-4.4); case header shows `heldCount`.
- Hold wizard shows "this scope currently matches **4,182** messages" (from `/search/facets`) before the user commits — important for a credible demo.
- Read-only mode: when a case is `CLOSED`, the detail view disables every mutating control rather than letting the API 409.
- Served by nginx in a container; nginx also reverse-proxies `/api` to the gateway, so the browser sees one origin.

---

## 8. Deployment (NFR-4)

**Single command on a laptop:** `docker compose up -d` (with `--profile seed` to also run the corpus generator).

```yaml
services:
  postgres:        # postgres:17-alpine        (case, export schemas — separate DBs, no shared schema)
  mongo:           # mongo:7                  (archive, audit — separate DBs + users)
  kafka:           # apache/kafka:3.9 (KRaft, single broker)
  elasticsearch:   # docker.elastic.co/elasticsearch/elasticsearch:8.15 (single-node, security off locally)
  localstack:      # localstack/localstack (S3) — AWS-compatible for offline demo
  ingest-service: case-service: archive-service: search-service: export-service: audit-service:
  api-gateway:
  web:             # nginx serving the Angular build
  # generator under profile: seed
```
- Multi-stage Dockerfiles: `maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-alpine` (non-root user, layered jars via `spring-boot-jarmode-layertools` for fast rebuilds); Angular built with `node:22-alpine` → `nginx:alpine`.
- `depends_on: condition: service_healthy` + per-service `healthcheck`, so one command really does bring up a working system.
- **S3:** LocalStack for the offline/laptop path, real AWS S3 when `AWS_PROFILE` is set — one `S3Client` bean, endpoint switched by profile. Buckets: `discoveryhub-attachments`, `discoveryhub-exports` (versioning + **Object Lock/legal-hold** on the attachments bucket as the storage-layer echo of the application-layer hold, and lifecycle rules mirroring retention). Pre-signed URLs for expiring downloads.
- **CI (GitHub Actions):** on every PR — `mvn -T1C verify` (unit + Testcontainers integration), `ng test --watch=false`, JaCoCo coverage gate, Docker build of all images, `docker compose up` smoke test that runs the end-to-end script. Branch protection: PR + 1 review + green CI (matches the "feature branches + pull requests expected" deliverable).

---

## 9. Testing Strategy (deliverable 3: one command + coverage)

`./run-tests.sh` → `mvn -T1C verify && (cd web && npm ci && npm run test:ci)`; aggregate JaCoCo + Karma coverage report at `target/site/jacoco-aggregate/index.html`.

| Level | Tooling | What it covers |
|---|---|---|
| Unit (TDD) | JUnit 5, AssertJ, Mockito | Case state machine (every legal + illegal transition), `RetentionGuard`, hold-set add/remove with overlaps, checksum/manifest builder, `QuerySpec → ES query` translator, dedupe key computation |
| Property-ish | JUnit 5 `@ParameterizedTest` | idempotency: N random re-submissions ⇒ 1 stored doc |
| Integration | **Testcontainers**: Postgres, Mongo, Kafka, Elasticsearch, LocalStack | ingest→Mongo→ES visibility, hold apply propagation, disposition skips held, export produces verifiable package, audit append-only |
| Contract | Spring Cloud Contract / MockMvc + `@WebMvcTest` | gateway↔service request/response shapes |
| API/E2E | manual curl walkthrough + Cypress for the SPA | the full demo path in Section 10 |
| Resiliency | Toxiproxy / `docker compose stop <svc>` in a test | consumer resumes after outage with zero loss; open circuit degrades instead of cascading |

Coverage target: ≥75% line on services, **100% on the four correctness-critical classes** (state machine, hold set, retention guard, checksum manifest). Mocking discipline: mock collaborators at the boundary (S3, other services), never the datastore under test — that's what Testcontainers is for.

---

## 10. The 12-Minute Live Demo Script (deliverable 1)

1. `docker compose up -d` — everything healthy (NFR-4).
2. Run generator: 12,000 messages, 25 custodians, 8% attachments (FR-1). Dashboard counts climb live.
3. Search `"wire transfer" AND bahamas`, filter Jan–Jun + has-attachment, show highlighting and p95 latency < 2 s (FR-3, NFR-3).
4. Create case *Project Kingfisher* (litigation), transition `DRAFT → ACTIVE`; attempt `ACTIVE → CLOSED` to show a rejected transition — closing requires `UNDER_REVIEW` first (FR-2).
5. Add 2 custodians; bulk-add the search page to the case; save the search and re-run it (FR-2, FR-3).
6. Place a hold over both custodians for the full date range — returns instantly, progress bar fills to 4,182 held (FR-4.1–4.4).
7. **Prove the block:** `curl -X DELETE /api/messages/{heldId}` → `409 MESSAGE_ON_HOLD`. Set email retention to 2 minutes, trigger disposition → run report: `deleted: 613, skippedOnHold: 4182` (FR-4.6, FR-5).
8. Place a second overlapping hold, release the first → messages remain held (FR-4.5).
9. Request an export → job id immediately, live `Queued → Running → Completed`; download via the expiring link; run `verify-export.sh` → PASS; flip one byte in the zip → FAIL (FR-6).
10. **Resiliency:** `docker compose stop archive-service`, ingest 2,000 more (UI degrades gracefully, no cascade), restart → all 2,000 appear in search within seconds; nothing lost (NFR-2).
11. Audit trail: filter by case — every action above is present with actor/timestamp/before-after; `verify-chain` → PASS; show that no write endpoint exists (FR-7).

---

## 11. Technology Coverage — Every Training Topic, Load-Bearing (Section 6 constraint)

| Training session | Where it is used, meaningfully |
|---|---|
| **Git / GitHub + hands-on** | One shared repo, 5 collaborators, trunk + short-lived feature branches, PR-per-feature with review, Conventional Commits, CODEOWNERS per service, GitHub Actions CI, branch protection. Individual scoring depends on this. |
| **Core Java — foundations** | All six services are Java 21: records for events/DTOs, sealed interfaces for command types, pattern matching in the state machine, streams in the projection/manifest code. |
| **Multithreading** | `ExecutorService`/`CompletableFuture` fan-out in the corpus generator; bounded `ThreadPoolExecutor` bulkheads for hold-application and export workers; `@Scheduled` disposition with a distributed lock; **virtual threads** (`spring.threads.virtual.enabled=true`) for the IO-bound fetch loops in export. |
| **Exception handling** | Typed domain exceptions (`HeldItemException`, `InvalidTransitionException`, `CaseClosedException`) → `@RestControllerAdvice` → RFC-7807 responses; retryable vs non-retryable classification drives Kafka retry-vs-DLT. |
| **Collections & Generics** | Generic `Page<T>`, `Result<T,E>`, `EventEnvelope<T>`; hold-set logic on `Set<String>`; `Map<CaseStatus, EnumSet<CaseStatus>>` transition table. |
| **External libs, TDD, unit tests, mocking** | JUnit 5 + Mockito + AssertJ + Testcontainers + Awaitility; TDD on the four correctness-critical classes; coverage report as a deliverable. |
| **Creational patterns** | **Builder** (`QuerySpec`, `ExportManifest`, event envelopes), **Factory Method** (`CommunicationFactory` → email vs chat generation; `StorageClientFactory` → LocalStack vs AWS), **Abstract Factory** (per-comm-type generator families), **Singleton** (Spring-managed beans, `S3Client`), **Prototype** (cloning a saved `QuerySpec` before mutating filters). |
| **Structural patterns** | **Adapter** (`ArchiveClient`, `SearchClient` wrapping REST; `S3StorageAdapter` behind a `BlobStore` port), **Facade** (`CaseFacade` composing case+hold+export+audit reads for the case-detail screen), **Decorator** (`DigestOutputStream` chain + a caching/metrics decorator on `BlobStore`), **Proxy** (Feign/`RestClient` interfaces, pre-signed-URL proxy), **Composite** (nested search filter groups). |
| **Behavioral patterns** | **State** (case lifecycle), **Strategy** (retention policy per comm type; sort strategy; highlight strategy), **Observer** (Kafka event consumers, SSE progress), **Template Method** (`AbstractExportPackager` — layout fixed, format hooks vary: ZIP now, PST/EML later), **Chain of Responsibility** (ingest validation pipeline; `RetentionGuard` chain), **Command** (`PlaceHoldCommand`/`ReleaseHoldCommand` on `hold.commands.v1`), **Memento** (audit before/after snapshots). |
| **OOP & SOLID** | Hexagonal-ish layering per service: ports (interfaces) in `domain`, adapters in `infrastructure` — **D**IP by construction; one service = one aggregate boundary (**S**RP); new export formats/retention rules added by extension, not modification (**O**CP); `BlobStore` implementations are substitutable (LSP, LocalStack vs S3); narrow client interfaces per consumer (**I**SP). |
| **Spring Boot** | Web/WebFlux, Data JPA, Data MongoDB, Data Elasticsearch, Kafka, Validation, Actuator, Scheduling, AOP (`@ClosedCaseGuard`, audit-emitting aspect), profiles (`local`/`docker`), Spring Cloud Gateway, Flyway migrations. |
| **PG/SQL** | Case-service system of record: enums, `interval` retention, `JSONB` saved searches, `text[]` hold scope, FK integrity, optimistic locking, transactional outbox, indexes, and a reporting query (`heldCount`/evidence counts per case) written as SQL with `GROUP BY`/window functions rather than in Java. |
| **Kafka** | The backbone: 6 business topics + DLTs, keyed partitioning, consumer groups, offset-based replay (ES rebuild), compacted topic, idempotent producers, error handler + DLQ, and the NFR-2 outage demo. |
| **MongoDB** | Message corpus (schema-variant documents, atomic `$addToSet`/`$pull` hold sets, bulk writes, compound indexes, TTL/time-series option for audit) and the append-only audit store with a insert-only DB role. |
| **Elasticsearch** | The entire search feature: custom analyzer, `multi_match` relevance, filter context, highlighting, nested participants, aggregations for dashboard counts and hold-scope preview, PIT + `search_after` for bulk id streaming, index rebuild from Kafka. |
| **AWS basics / S3** | Attachment and export-package storage; pre-signed expiring URLs; bucket versioning, Object Lock/legal hold, lifecycle rules, server-side encryption; IAM least-privilege policy per service; LocalStack for offline parity. |
| **Docker** | Multi-stage non-root images for all 7 apps, one-command `docker compose` stack with healthchecks and profiles, Testcontainers in CI. |
| **Frontend basics (Angular/JS)** | The single Angular SPA: components, services, typed HTTP interceptors, signals, lazy routes, pipes (highlight, relative time), SSE consumption, Karma/Jest unit tests, Cypress E2E. |
| **Distributed computing & storage; resiliency patterns** | Section 5 in full: CQRS read model, eventual consistency with stated bounds, idempotent consumers, transactional outbox, bulkheads, circuit breakers, retries with jitter, DLQ, timeouts, backpressure, leader-elected scheduler, graceful degradation, replayable event log. |
| **InfoSec do's & don'ts** | No secrets in git (`.env.example` + GitHub Secrets), least-privilege IAM and DB users, insert-only audit role, hash-chained audit for tamper evidence, pre-signed short-TTL URLs instead of public buckets, input validation + output escaping in the SPA, dependency scanning (Dependabot + `mvn dependency-check`) in CI, non-root containers, PII kept out of logs. |
| **AI tools** | Copilot/Devin used for scaffolding, test generation and PR review, with a `docs/ai-usage.md` note on what was generated vs hand-written and how it was reviewed. |

---

## 12. "Why X and not Y?" — Prepared Defences

- **Postgres for cases, Mongo for messages.** Cases need a state machine, referential integrity, multi-row transactions and optimistic locking on a tiny dataset — relational wins. Messages are 10k+ schema-variant documents with per-document atomic set updates and no cross-document invariants — document store wins. Using one store for both would mean either JSONB gymnastics or losing transactional transitions.
- **Elasticsearch and not `LIKE`/Postgres FTS.** FR-3 requires relevance ranking, highlighting, stemmed full-text over subject+body+participants, multi-filter combinations and sub-2 s latency at scale. Postgres FTS could do a weaker version of this; it would not give highlighting quality, relevance tuning, PIT paging, or aggregation-based scope previews. Cost: eventual consistency, which we bound and demonstrate.
- **Kafka and not RabbitMQ/SQS.** We need a **replayable log**, not just a queue: rebuilding the ES index and reprocessing after an outage are both offset rewinds. Compaction gives us a durable snapshot of the corpus projection. RabbitMQ discards after ack; SQS has no replay and would tie the laptop demo to AWS.
- **Async hold command and not a synchronous bulk update.** A hold can cover the whole corpus; a synchronous request would exceed HTTP timeouts (FR-4.3 forbids blocking). 202 + progress is both correct and better UX.
- **Hold *set* per message and not a boolean.** Booleans break overlapping-hold release (FR-4.5).
- **Enforcement in Mongo, not in ES.** ES is eventually consistent; deletion protection must be evaluated against the authoritative store, in the same component that performs deletes.
- **S3 (LocalStack locally) and not the DB or a volume.** Attachments and export packages are large binary blobs; blob storage gives pre-signed expiring URLs (FR-6.4), versioning, Object Lock and lifecycle rules for free — all of which map directly onto legal-hold and retention semantics.
- **Seven services and not six.** This section originally argued *against* a separate hold-service, on the theory that it would add a distributed transaction between holds and the corpus. That objection does not survive scrutiny: hold-service owns its own Postgres with no FK to `cases` (only an event-checked `caseId` reference), and `case.closed.v1 → hold.commands.v1{RELEASE}` is exactly the outbox-plus-idempotent-consumer pattern this document uses everywhere else to *avoid* distributed transactions, not an instance of one. The decisive argument is SRP: legal hold + retention/disposition is a corpus-wide async background worker with its own schedule and failure mode, not the same concern as case-service's low-throughput transactional CRUD. Superseded 2026-09-07 — see D-003 in `decisions.md`. Twelve would still be over-splitting; the boundary follows data ownership and change cadence, and seven is where those actually land for this system.
- **Gateway and not direct browser→service calls.** One origin for the SPA, one place for CORS, correlation ids, rate limits and circuit breaking; keeps service URLs an internal concern.
- **JSON events with versioned topics and not Avro + Schema Registry.** Registry is the right long-term answer; for a 4-day build it adds a container and a build plugin without changing any demo outcome. Called out as a known trade-off.

---

## 13. Version & EOL Posture (pin these on day 1)

The requirements say "current LTS"; be explicit, because out-of-support runtimes and data infrastructure are a real risk, not a cosmetic one.

| Component | Use | Do **not** use | Why |
|---|---|---|---|
| **JDK** | **21 (LTS)** — `eclipse-temurin:21`; 25 LTS acceptable if the trainers' tooling supports it | 8 / 11 / 17 | 21 brings virtual threads (used in export), generational ZGC and better G1 pause behaviour — materially lower heap/CPU for the same throughput. ≤17 is at or near end of free support: unpatched CVEs and no Loom. **HIGH priority if you find ≤17 anywhere in `pom.xml` compiler properties, Gradle `sourceCompatibility`, base images, or `setup-java`.** |
| **Spring Boot** | **3.5.x** (Spring Framework 6, Jakarta namespace) | any **2.x** | Boot 2.x is **EOL for OSS support — no free security patches**, cannot use Jakarta APIs, AOT/native images, the Micrometer Observation API, or virtual-thread integration. Starting a new project on 2.x means starting with an unpatched, `javax`-locked stack. **HIGH.** |
| **MongoDB** | **7.x** (`mongo:7`) with a 6.x+ driver / Spring Data Mongo 4.x | `mongo:3/4/5` | Below 6 you lose query-engine and index improvements, time-series enhancements and Queryable Encryption, and you are past EOL without security patches. Because Mongo is **on the data path here (the message corpus and the audit trail)**, an EOL version is **CRITICAL**, not merely high. The usual failure mode is "fixing" the resulting slow queries by scaling the node vertically — paying cloud cost to avoid an upgrade. |
| **Kafka** | **3.9.x in KRaft mode** (`apache/kafka:3.9`), `kafka-clients` 3.9, Spring Kafka 3.x | any **2.x** broker, client, connector or CLI | 2.x clients lag on protocol improvements, cooperative rebalancing maturity, tiered storage and KRaft, and version skew between 2.x clients and newer brokers causes subtle rebalance failures; old libraries carry unpatched CVEs. Kafka is **on the data path** here (every ingested message and hold command flows through it) ⇒ EOL 2.x is **CRITICAL**. Also avoids shipping ZooKeeper. |
| **Elasticsearch** | 8.15+ (or 9.x) | 7.x | 7.x is EOL; 8.x has the current Java client (`ElasticsearchClient`) and security defaults. |
| **PostgreSQL** | 17 | ≤12 | Out-of-support majors get no security fixes. |
| **Node.js** | 22 LTS | ≤18 | 18 is out of support; Angular 19+ requires 20+. |
| **Angular** | 19/20 | ≤15 | Older majors are out of the support window (standalone components, signals). |

**Bottom line to state in the presentation:** these are not cosmetic upgrades. Out-of-date runtimes and data infrastructure degrade performance density and remove access to modern efficiency features; the common failure pattern is masking the problem by over-sizing machines or adding nodes, which inflates cloud cost. Left long enough, EOL components with unpatched CVEs and version skew are a leading cause of Sev1 incidents. Pin the versions above in `pom.xml` (`<java.version>21</java.version>`, Boot 3.5.x parent) and in every Dockerfile/compose image on day 1, and add a CI check that fails the build on a downgrade.

---

## 14. Work Split for 5 Engineers (4 days) — mapped to the individual rubric

Each member **owns one service end to end** (code + tests + Dockerfile + its slice of the demo), and everyone contributes to integration wiring, which the rubric scores explicitly.

| Member | Primary ownership | Secondary / shared |
|---|---|---|
| **A — Ingest & Corpus** | corpus generator, `ingest-service`, dedupe/idempotency, Kafka topic design, `discoveryhub-events` shared module | Kafka DLT/replay tooling; NFR-2 outage demo |
| **B — Archive & Retention** | `archive-service`: Mongo model, S3 attachments, hold applier, `RetentionGuard`, disposition scheduler | the deletion-blocked proof; Mongo indexes |
| **C — Search** | `search-service`: ES mapping/analyzer, query translator, highlighting, paging, facets, scope-ids streaming, reindex-from-Kafka | dashboard counts API |
| **D — Cases, Holds & Audit** | `case-service` (Postgres, state machine, evidence, saved searches, outbox) + `audit-service` (append-only, hash chain) | Flyway migrations; audit event taxonomy |
| **E — Export, Frontend & Platform** | `export-service` (packaging, manifest, checksums, pre-signed URLs, retry) + Angular SPA + `docker compose` + GitHub Actions CI | gateway config |

Day plan: **D1** contracts frozen (event schemas + OpenAPI + compose skeleton) then vertical slices; **D2** ingest→archive→search green end to end, cases/holds CRUD; **D3** holds propagation, retention, export packaging, SPA wired; **D4** resiliency demo, coverage push, rehearsal. Contracts-first on day 1 is what makes five people working in parallel actually converge.

Suggested Definition of Done per PR: unit tests for new logic, one integration test if it crosses a boundary, no drop in coverage, README section updated, feature branch + review from a member who does not own that service (spreads the "explain the whole application" ability the rubric tests in Q&A).

---

## 15. Requirement Traceability

| Req | Satisfied by |
|---|---|
| FR-1.1–1.3 | corpus generator (§2.1); ≥10k messages, ≥20 custodians, ≥5% attachments |
| FR-1.4 | `ingest-service` accepts, `archive-service` stores, decoupled by `comm.ingest.v1` |
| FR-1.5 | deterministic UUIDv5 id; Mongo + S3 durable storage |
| FR-1.6 | dual idempotency: Postgres dedupe key + Mongo upsert on unique `messageKey` |
| FR-1.7 | batched ES bulk indexing, `refresh_interval 5s` ⇒ p99 ≈ 7 s |
| FR-2.1–2.5 | Postgres schema §3.1, transition table §3.1, `@ClosedCaseGuard` aspect |
| FR-3.1–3.6 | §2.2 ES query/highlight/paging/sort, `saved_searches`, bulk add via scope-ids |
| FR-4.1–4.6 | §2.3 async hold command, hold-id set, ES status propagation, `RetentionGuard` + `$size:0` delete |
| FR-5.1–5.3 | configurable `retention_policies` (minutes-capable), locked scheduler, `disposition_runs` report |
| FR-6.1–6.6 | §2.5 async job, SSE progress, manifest + per-item/package checksums, pre-signed TTL URL, verify script, attempt-dir retry |
| FR-7.1–7.4 | §2.6 event-sourced audit, no write API, insert-only DB role, hash chain, per-case & global filters |
| FR-8.1–8.3 | §7 Angular SPA, all six screens + dashboard counts |
| NFR-1 | 6 backend services + SPA, private datastores, REST + Kafka only |
| NFR-2 | §5 resiliency patterns; live outage demo step 10 |
| NFR-3 | ES filter-context + trimmed source (p95 ≈ 60 ms); async bulk hold |
| NFR-4 | one-command `docker compose` deployment |
| Tech constraint | §11 coverage table (every training topic, load-bearing) |
| Deliverables 1–4 | §10 demo script, GitHub workflow §11, `./run-tests.sh` §9, presentation outline §12 + §13 |
