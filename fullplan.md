# DiscoveryHub — Day 1 Playbook (per member, hour by hour)

**Day 1 has exactly one goal:** by 18:00 a message can be POSTed, travel through Kafka into Mongo, be indexed into Elasticsearch, and come back from a search API — on a stack started with `docker compose up`. Business logic is *not* a Day 1 goal. If you are choosing between "my module is deep" and "the pipe is alive", the pipe wins every time.

Nobody works on their own module before 13:00. Morning is shared plumbing.

---

## 0. The merge order (this is the critical path)

Everything is blocked on step 1 and 2, so these are timeboxed hard:

```
1. M1  repo + root pom + common module + compose infra   -> merged by 11:00
2. M2  service skeletons (all 6, generated together)     -> merged by 12:00
3. M1  ingest endpoint + Kafka producer                  -> 14:00-15:30
4. M1  ingest consumer -> Mongo                          -> 15:30-16:30
5. M2  ES indexer consumer + GET /search                 -> 15:30-17:00
6. All smoke test green                                  -> 17:00-18:00
```

Steps 3–5 are the only truly serial part. M3/M4/M5 work in parallel on things that do not block the slice.

---

## 09:00–10:00 — All five: contract meeting (no code)

One person shares a screen, one person types. Produce three files and merge them as the first PR:

- `docs/architecture.md` — service list, ports (8081 ingestion, 8082 search, 8083 case, 8084 hold, 8085 export, 8086 audit, 4200 frontend), which service owns which database, one diagram.
- `docs/api-contracts.md` — every endpoint each member will expose, with request JSON, response JSON, and error codes. Written now, even for endpoints that won't exist until Day 3.
- `docs/events.md` — the 6 topics with exact payloads. Every event carries: `eventId`, `eventType`, `eventVersion`, `occurredAt`, `entityId`, `correlationId`, `actor`. Keep payloads small; no service reads another's database, ever.

Also lock, out loud, so nobody re-litigates it later: **JDK 21, Spring Boot 3.x latest stable, Kafka 3.x KRaft, Mongo 7, ES 8, Postgres 16, LocalStack for S3, Angular 17+ standalone components.** (Boot 2.x and Mongo <6 are EOL — no free security patches, and both Mongo and Kafka sit on your data path.)

Last 5 minutes: everyone runs `git config user.name` / `user.email` with their real name and work email, and confirms it in the call. A member with no commits under their own identity loses individual marks no matter what they built.

---

## 10:00–13:00 — Foundation

### Member 1 — repo, root POM, `common`, Compose infra  ← everyone is blocked on this
**Merge by 11:00. This is the hardest deadline of the day.**

- Create the GitHub repo, protect `main` (no direct pushes, 1 approval required).
- Root `pom.xml`: `<packaging>pom</packaging>`, Java 21, Spring Boot 3.x parent, `<modules>` for `common` + the 6 services.
- `common` module: the event DTOs from `docs/events.md`, a `CorrelationId` holder, the `ApiError` envelope shape, and base domain exception classes. Nothing else — `common` must not become a dumping ground.
- `docker-compose.yml` with infra only, on a single network, with healthchecks:
  `postgres:16`, `mongo:7`, `elasticsearch:8` (single-node, security off for local), `kafka:3.x` in **KRaft mode** (no ZooKeeper container), `localstack` (S3 only, auto-create the buckets).
- `.gitignore`, `README.md` with the ownership table from the approach doc, and `docs/` from the 09:00 meeting.

**Done when:** `docker compose up -d` brings all five infra containers to healthy and `mvn -q clean install` succeeds at the root.

### Member 2 — all 6 service skeletons  ← generate them together, one PR
**Start ~10:30 off M1's branch, merge by 12:00.**

- Generate all six Spring Boot 3 / JDK 21 services *yourself, in one go* — do not let each member generate their own or you will get six different package layouts and six different config styles.
- Identical structure per service: `controller/ service/ repository/ model/ dto/ exception/ config/ kafka/`, `application.yml` with the right port, actuator health exposed, and a `/health`-reachable app that boots.
- One `Dockerfile` per service (multi-stage, JDK 21 base) and the six service entries appended to `docker-compose.yml` with `depends_on` healthchecks.
- Each service gets one hardcoded stub endpoint matching `docs/api-contracts.md`, so the frontend and downstream services can integrate before real logic exists.

**Done when:** `docker compose up` starts infra + all 6 services, and every service's health endpoint returns UP.

### Member 3 — test/coverage tooling + Postgres schema
- JaCoCo in the root POM with an aggregate report, plus a Testcontainers base test class per infra type (Postgres, Mongo, Kafka, ES) that the other four will extend all week.
- One `mvn verify` (or `make test`) entrypoint that runs everything and emits the coverage report. **Set up today so Day 4 is just writing tests, not fighting tooling.**
- `@RestControllerAdvice` global handler in `common` producing the agreed `ApiError` envelope.
- Flyway migration V1: `cases`, `case_custodians`, `evidence`, `saved_searches`.
- One trivial passing test in each service so the pipeline is proven green.

### Member 4 — corpus data design + hold schema (paired with M1)
- Design the message document shape *with* M1 and write it into `docs/events.md`: `id`, `externalMessageId`, `type` (EMAIL/CHAT), `subject`, `body`, `participants[]`, `custodian`, `threadId`, `sentAt`, `attachments[]`, `onHold`, `holdCount`.
- Build the custodian roster: 20+ fictional employees with names, emails, departments. Commit as a JSON/CSV resource — M1's generator reads it, so it must exist before the generator does.
- Flyway migration for the hold side: `holds`, `hold_message_refs` (the reference-counting table that makes overlapping holds correct), `retention_policies`, `disposition_runs`.
- Write the retention policy config shape (per communication type, minute-level demo values).

### Member 5 — CI, Kafka topics, LocalStack S3 wiring
- GitHub Actions: on every PR run `mvn verify` + build all Docker images. Merges blocked on green. Set up today — a broken build found on Day 3 costs hours.
- Kafka topic creation (init container or startup bean) for all six topics with sane partitions/replication, plus a documented consumer group per service.
- LocalStack S3: create the `discoveryhub-attachments` and `discoveryhub-exports` buckets on startup; verify a put/get with the AWS CLI.
- The `StorageService` interface in `common` (put, get, presignedUrl) — interface only, no AWS SDK types leaking into it. This is your Dependency Inversion / Adapter talking point in Q&A.
- Write `scripts/smoke.sh`: the end-to-end curl script the team runs at every checkpoint (ingest one message, poll search until it appears, exit non-zero on failure). **The team lives off this script for 4 days.**

---

## 13:00–14:00 — Lunch. First integration checkpoint at 13:00 before you eat (10 min)

Merge everything ready, `docker compose down -v && docker compose up`, confirm all services healthy. If it's red, it gets fixed before feature work resumes.

---

## 14:00–18:00 — Build the slice

### Member 1 — ingestion path (the critical path, 14:00–16:30)
- 14:00–15:00: corpus generator using the **Factory** pattern (EmailFactory / ChatFactory off a common `CommunicationFactory`). Subjects, bodies, timestamps, threads, participants, >=5% with attachments. `POST /admin/generate?count=10000`.
  - Deterministic `externalMessageId` (e.g. hash of custodian+thread+sentAt+bodyHash) — this is what makes ingestion idempotent later.
- 15:00–15:30: `POST /messages/ingest` — validate, publish `message.ingested`, return **202 + messageId**. It must NOT write to Mongo. Acceptance and durability are deliberately separate components; this split is an explicit requirement and an evaluator question.
- 15:30–16:30: the ingestion consumer — reads `message.ingested`, writes to Mongo with a **unique index on `externalMessageId`**, treats a duplicate as a logged no-op.
- 16:30–18:00: generate the real 10,000 and confirm the Mongo count. Fix whatever the smoke script finds.

### Member 2 — search path (15:30 onward, as soon as M1's producer is merged)
- ES index mapping: `text` analyzed fields for subject/body, `keyword` for custodian/type, `date` for `sentAt`, boolean `hasAttachment`/`onHold`.
- Indexer consumer on `message.ingested` (its **own consumer group**, independent of M1's).
- `GET /search?q=` — full text over subject/body/participants, returning ids + snippet. Filters/highlighting/pagination are Day 2; today just return a hit.
- Prove ES is not the source of truth: a `POST /admin/reindex` stub that reads Mongo. Even unimplemented, note it in the API doc.

### Member 3 — case service real endpoints
- `POST /cases`, `GET /cases`, `GET /cases/{id}` against real Postgres via Flyway + JPA.
- Case lifecycle skeleton using the **State** pattern: Draft -> Active -> Under Review -> Closed, with invalid transitions throwing a domain exception that surfaces through M3's own `@RestControllerAdvice`.
- One Testcontainers integration test proving an invalid transition is rejected. **First real business test of the project, on Day 1.**

### Member 4 — hold service skeleton + the overlap test
- `POST /holds` accepting `{custodians[], from, to, terms[]}`, persisting a hold row, returning **202 + jobId with status PROCESSING**. Async from the very first commit — never synchronous.
- Publish `message.held` for a hardcoded message id, to prove the topic and contract work end to end.
- Write the **failing** test for the rule that matters most: two overlapping holds cover a message; release one; the message must still be protected. Reference counting via `hold_message_refs`, not a boolean. Implementing it is Day 2 — having the test red today is the point.

### Member 5 — audit sink + export skeleton
- Audit service consumes `audit.event` and persists append-only rows: `timestamp, actor, action, targetType, targetId, before, after, previousHash, currentHash`.
- Hash chain: `currentHash = sha256(previousHash + canonicalJson(event))`. **No update or delete endpoint exists anywhere in the service** — that absence is itself a rubric item and a test.
- `GET /audit?caseId=` and `GET /audit` (system-wide).
- `POST /exports` -> publishes `export.requested`, returns 202 + QUEUED job. Worker is Day 3.
- Make M1's ingestion emit an `audit.event` so the chain has real data by 18:00.

### 17:00–18:00 — All five: the gate
Run `scripts/smoke.sh` on a clean stack (`docker compose down -v` first — a fresh machine is the real Definition of Done). Then each member demos their piece to the other four in 2 minutes. Merge everything; `main` must end the day green.

---

## Day 1 hard gate — all seven must be true

1. `docker compose up` starts infra + 6 services from a clean volume state.
2. 10,000+ messages generated, with 20+ custodians and >=5% attachments.
3. Mongo holds the corpus as the durable source of truth.
4. Kafka shows real `message.ingested` events, with separate consumer groups per consumer.
5. Elasticsearch is populated by its own consumer.
6. `GET /search?q=...` returns a real hit.
7. `mvn verify` runs and produces a coverage report; `main` is green in CI.

Miss one? Fix it tonight or first thing Day 2 — do not start Day 2 features on a red Day 1.

---

## The three ways Day 1 actually fails

- **M1's foundation slips past 11:00.** Then four people idle. If it's 10:45 and it isn't close, M2 and M5 drop their own tasks and pair on it.
- **Someone starts building their module deep instead of wide.** A gorgeous case service is worth nothing on Day 1 if the pipe is dead. Wide first, always.
- **Six different service layouts.** That's why M2 generates all six skeletons in one PR, not each member their own.
