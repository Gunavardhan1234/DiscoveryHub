# Storage Architecture Notes (Postgres, MongoDB, S3)

This doc captures the reasoning behind our polyglot-persistence setup, so
anyone on the team (and future us during the presentation Q&A) can explain
"why did you use X here and not Y?".

## TL;DR

| Store | Who owns it | What's in it | Why this store |
|---|---|---|---|
| Postgres `discoveryhub_case` | case-service | `cases`, `case_custodians`, `evidence_items`, `holds` (local copy), `saved_searches`, `retention_policies`, `outbox` | Stateful workflow with strict lifecycle rules (Draft→Active→Under Review→Closed) + FK integrity between cases/evidence/custodians → needs ACID + relational constraints |
| Postgres `discoveryhub_hold` | hold-service | `holds` (source of truth), `retention_policies`, `disposition_runs`, `outbox` | Same reasoning: hold state machine (PENDING→ACTIVE→RELEASING→RELEASED/FAILED) needs transactional guarantees |
| Postgres `discoveryhub_export` | export-service | `export_jobs` (QUEUED→RUNNING→COMPLETED/FAILED, progress %, S3 key, error) | Job/state-machine tracking, same relational fit |
| Postgres `discoveryhub_ingest` | ingest-service | `ingest_dedupe` (message_key PK) | Idempotency guard only — see below |
| Postgres `discoveryhub` | n/a (bootstrap) | nothing (empty) | Default DB created by `POSTGRES_DB` env var when the container first boots; no service ever writes to it |
| Postgres `postgres` | n/a | Postgres's own internal admin DB | Exists in every Postgres install by default, not app-related |
| MongoDB `discoveryhub_archive` | archive-service | `messages` collection: subject, body, participants[], attachments[], threadId, holds[], etc. | Message content is semi-structured/variable-shape (variable participant count, variable attachments, mutable holds set) — document model avoids 3-4 join tables and fits high-volume async ingestion writes |
| Elasticsearch | search-service | search index, rebuilt from Mongo via Kafka replay | Read-optimized full-text search; Mongo remains source of truth, ES is a derived/rebuildable index (classic CQRS split) |
| S3 (real AWS, bucket `daemon-discovery`) | ingest/archive/export-service | raw attachment bytes, export `.zip` packages | Object storage for large binary blobs that don't belong in a DB row; also gives pre-signed, time-expiring download URLs natively |

## Why both Postgres AND MongoDB (polyglot persistence)?

The requirements doc (`requirements.docx`) never mandates a specific database
technology. What it does say:

- **NFR-1**: "Each service owns its own data — no shared database schemas
  between services." → explicitly permits (and implicitly encourages)
  different services picking different storage tech, as long as schemas
  aren't shared.
- **Section 6 (Technology Constraint)**: "Every technology covered in the
  training program must be used somewhere in the solution, meaningfully...
  Expect the question 'why did you use X here and not Y?' for every major
  choice." → if training covered both a relational DB and a document DB,
  we're expected to use both somewhere and justify each choice.

Reasoning per data shape:

- **Relational (Postgres)** for case/hold/export: these are *stateful
  workflows with strict invariants* — case status transitions (FR-2.2: "Only
  valid transitions are allowed"), hold lifecycle, export job lifecycle, and
  foreign-key relationships (`evidence_items.case_id -> cases.id`). ACID +
  FK constraints are exactly what a relational DB is built for.
- **Document (MongoDB)** for archived messages: a message has a variable
  number of participants, a variable number of attachments, and a mutable
  `holds` set. That's semi-structured, nested, variable-shape data — a
  document store avoids needing `message_participants`,
  `message_attachments`, `message_holds` join tables the way Postgres would.
  Combined with FR-1's ingestion volume requirement (10,000+ messages,
  async, idempotent, high write throughput), Mongo fits the write-heavy,
  flexible-schema ingestion path well.

Overall pattern: Mongo is the system of record for message content → Kafka
events (`comm.stored.v1`) propagate to Elasticsearch, the read-optimized
search index. This is a form of CQRS / polyglot persistence, a well-known,
legitimate architecture — not an inconsistency.

## Why does ingest-service have its own Postgres DB if messages live in Mongo?

`discoveryhub_ingest` does **not** store message content. It exists purely
to satisfy **FR-1.6**: "Ingestion must be idempotent — re-submitting the
same message must not create duplicates."

Reasoning chain:

1. FR-1.4 requires the component that *accepts* messages to be different
   from the component that *stores* them → `ingest-service` (accepts) is
   deliberately decoupled from `archive-service` (stores, in Mongo).
2. Because they're decoupled, `ingest-service` can't just ask Mongo "have I
   seen this message before?" without creating a tight sync dependency
   between two services meant to be independent.
3. So `ingest-service` keeps one tiny table, `ingest_dedupe`
   (`message_key TEXT PRIMARY KEY`). On ingest it does
   `INSERT ... ON CONFLICT DO NOTHING` — a duplicate is a silent no-op and
   never reaches Kafka.
4. `archive-service` *also* has a unique index on `messageKey` in Mongo as
   an independent second guard, since Kafka doesn't guarantee exactly-once
   delivery. Belt-and-suspenders across two independent services.

So: one table, one job — a cheap idempotency gate right at the ingestion
front door, kept local to the service that needs it (matches NFR-1).

## Postgres: per-service isolation (why can't I see all 6 databases at once?)

`infra/postgres/init/01-databases.sh` creates one Postgres login per service
(`case_svc`, `export_svc`, `ingest_svc`, `hold_svc`) and revokes `CONNECT`
from `PUBLIC` on each service's database, granting it back only to that
service's own user:

```sql
REVOKE CONNECT ON DATABASE discoveryhub_case FROM PUBLIC;
GRANT CONNECT ON DATABASE discoveryhub_case TO case_svc;
-- (same pattern for export/ingest/hold)
```

In Postgres, each database has its own connect ACL — being "connected to the
server" doesn't mean a login can open every database on it. A login
(`case_svc`) can only open the databases it's explicitly been granted
`CONNECT` on. That's why a pgAdmin server logged in as `case_svc` shows
`discoveryhub_case` (granted) and `discoveryhub` (never revoked from
PUBLIC) as browsable, but greys out `discoveryhub_export` /
`discoveryhub_hold` / `discoveryhub_ingest` / `postgres` — not a bug, just
Postgres enforcing the per-service data ownership NFR-1 requires.

This setup has been stable since commit `7668a5f` and has never changed.

### How to connect to each database

All of these are the **same Postgres server**: host `localhost`, port
`5433` (mapped from the container's `5432` — Homebrew Postgres already owns
port `5432` on this machine, so Docker Postgres uses `5433` to avoid the
clash).

```bash
# case-service data (cases, evidence_items, ...)
psql "postgresql://case_svc:$CASE_DB_PASSWORD@localhost:5433/discoveryhub_case"

# hold-service data (holds, disposition_runs, ...)
psql "postgresql://hold_svc:$HOLD_DB_PASSWORD@localhost:5433/discoveryhub_hold"

# export-service data (export_jobs)
psql "postgresql://export_svc:$EXPORT_DB_PASSWORD@localhost:5433/discoveryhub_export"

# ingest-service data (ingest_dedupe)
psql "postgresql://ingest_svc:$INGEST_DB_PASSWORD@localhost:5433/discoveryhub_ingest"
```

Passwords come from `.env` (`CASE_DB_PASSWORD`, `HOLD_DB_PASSWORD`,
`EXPORT_DB_PASSWORD`, `INGEST_DB_PASSWORD`).

**In pgAdmin**, register a separate server entry per service with the
matching username/password above (all on `localhost:5433`), rather than one
shared login — this mirrors the per-service isolation the requirements
actually test for. Logging in once as the bootstrap superuser
(`POSTGRES_USER`/`POSTGRES_PASSWORD` from `.env`) can see every database at
once and is fine for quick personal debugging, but shouldn't be the pattern
used to explain the architecture (it's a single shared credential crossing
every service boundary, the opposite of what NFR-1 asks for).

## S3: we moved off LocalStack to real AWS

We used to run S3 locally via LocalStack (`localstack/localstack:3`,
`infra/localstack/init/ready.d/01-create-buckets.sh`, port `4566`). That was
removed in commit `7b5c948` ("Added s3 and fixed corpus errors"):

- The `localstack` service block, its healthcheck, and its `localstack_data`
  volume were deleted from `docker-compose.yml`.
- `localstack: {condition: service_healthy}` was removed from
  `ingest-service` and `archive-service`'s `depends_on`.
- Hardcoded `S3_ENDPOINT: http://localstack:4566` and fallback
  `test`/`test` AWS creds were replaced with `${S3_ENDPOINT}`,
  `${AWS_ACCESS_KEY_ID}`, `${AWS_SECRET_ACCESS_KEY}` pulled from `.env`.

Right now, `.env` points those at **real AWS** (`s3.eu-north-1.amazonaws.com`,
bucket `daemon-discovery`), so `ingest-service`, `archive-service`, and
`export-service` all talk to a real S3 bucket, not a local emulator.

**Leftover to clean up:** `infra/localstack/init/ready.d/01-create-buckets.sh`
is still sitting in the repo but is no longer referenced anywhere — dead
file, safe to delete in a follow-up commit so nobody thinks LocalStack is
still wired up.

**Security note:** `.env` contains a real AWS access key/secret in
plaintext. Make sure `.env` is git-ignored and was never committed/pushed to
the shared repo.

### Why S3 at all (not just a DB blob column)?

- **FR-1.5**: "Every ingested message must be assigned a unique, immutable
  ID and stored durably along with its attachments." Databases are a poor
  fit for large binary blobs (email attachments) — S3 is built for exactly
  that; Mongo just stores metadata + an S3 key pointer.
- **Export requirement**: "Completed packages must be stored durably and
  downloadable from the UI via a link that expires." That's a textbook S3
  pre-signed URL (`GET /{id}/download-url`, 15-min expiry) — a feature S3
  supports natively, which is the actual reason to use object storage
  instead of serving files from a server's local disk.
