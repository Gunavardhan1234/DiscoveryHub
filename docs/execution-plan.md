# DiscoveryHub — 4-Day Execution Plan & 5-Way Work Split

Grounded in what is already built (case CRUD + the four correctness-critical
algorithms + compose/contracts docs), corrects the architecture to **7
services** (separate `hold-service`), and sequences work so the Kafka/Mongo/ES
gap closes on Day 1 and every trained technology lands on the live demo path.

**Timeline:** Mon Sep 7 (Day 1) → Fri Sep 11 (presentation).

---

## Service ownership (7 services, 5 members)

`archive-service` is the one shared service — it has three independent consumer
groups, each owned by the member who owns that event domain. Each member has
one clean *primary* service for the "ownership" rubric line and contributes
integration code for the "end-to-end integration" line.

| Member | Primary service | Contributes to | Frontend screen(s) |
|---|---|---|---|
| **M1** | `ingest-service` + `corpus-generator` + `discoveryhub-events` | archive ingest consumer (FR-1), compose/Docker infra | Dashboard |
| **M2** | `search-service` + Angular shell | archive ES indexer consumer (FR-1.7) | Search |
| **M3** | `case-service` + test/coverage tooling + `GlobalExceptionHandler` | case-closed guard wiring | Cases + Case Detail |
| **M4** | `hold-service` (NEW) | archive hold-applier + `RetentionGuard` + disposition executor (FR-4/FR-5) | Holds + Disposition |
| **M5** | `export-service` + `audit-service` | S3 `BlobStore` adapter (shared by archive+export), api-gateway, CI | Exports + Audit |

---

## Day 1 — Mon Sep 7: Foundation + the vertical slice

**One goal:** by 18:00 a message can be POSTed → Kafka → Mongo → Kafka → ES →
`GET /search` returns a hit, on `docker compose up`. Everything else this week
is filling in a working system.

### 09:00–10:00 — All five: contract + architecture reconciliation meeting (no code)
- Freeze the **7-service** split: create `services/hold-service`, update
  `docs/architecture.md` Section 1/12 + `api-contracts.md` port table to match
  `fullplan.md`.
- Confirm event contracts in `docs/events.md` are final (they already are).
- Everyone: `git config user.name/user.email` with real identity — verify at
  17:30. **A member with no commits under their own identity loses individual
  marks.**

### 10:00–13:00 — Foundation (parallel)

| Member | Task | Done when |
|---|---|---|
| **M1** | Create `services/hold-service` skeleton (mirrors others, port 8084); add to root `pom.xml` + `docker-compose.yml`; **move `holds`/`retention_policies`/`disposition_runs` + `HoldEntity`/`HoldStatus`/`RetentionPolicyEntity` out of case-service** into hold-service Flyway `V1__init.sql`; trim case-service `V1__init.sql`. Add `spring-kafka` to all service poms + a shared `KafkaConfig` (ProducerFactory/ConsumerFactory/KafkaTemplate beans). Wire `corpus-generator` with **Factory Method** (`CommunicationFactory`→`EmailFactory`/`ChatFactory`) + `ExecutorService` fan-out + virtual threads. | `mvn -q compile` green; hold-service boots; case-service still passes its ITs |
| **M2** | ES index creation on startup from `messages-index.json`; `search-service` `@KafkaListener` (`search-indexer` group) → ES `BulkRequest` flushed 500 docs/2s; `POST /search` returning real hits (multi_match on subject/body/participants, no filters yet). Angular shell: routing, `HttpClient`, `X-Correlation-Id` interceptor, error toast. | ES index exists; consumer compiles; shell serves at 4200 |
| **M3** | Verify case-service CRUD still green after the schema trim; extend `CaseRepository` with filter-by-status/q; set up **JaCoCo aggregate** + `run-tests.sh` one-command entrypoint; add `spring-boot-starter-aop` + skeleton `@ClosedCaseGuard` aspect (not active yet). | `mvn verify` produces coverage report; case ITs green |
| **M4** | hold-service skeleton endpoints: `POST /holds` (202 + PENDING, persist hold row), `GET /holds/{id}`; hold-service `outbox` table + a stub outbox poller; Kafka `hold.commands.v1` producer skeleton. retention **Strategy** interface + per-comm-type config (minute values for demo). | `POST /holds` returns 202; outbox table exists |
| **M5** | `BlobStore` **Adapter** interface in `common` + `S3StorageAdapter` impl over LocalStack (`put`/`get`/`presignedUrl`); auto-create `discoveryhub-attachments` + `discoveryhub-exports` buckets on startup; GitHub Actions CI (`mvn verify` + docker build on PR); `api-gateway` Spring Cloud Gateway routing all 7 services. | `awslocal ls` shows buckets; gateway routes hit health endpoints |

### 13:00 — Integration checkpoint (10 min, before lunch)
`docker compose down -v && docker compose up -d`, confirm all infra + 7 services
healthy. Red → fix before feature work.

### 14:00–18:00 — Build the slice (the critical path)

| Member | Task |
|---|---|
| **M1** | `ingest-service`: validate → compute `messageKey=SHA-256(...)` → Postgres `ingest_dedupe ON CONFLICT DO NOTHING` → `KafkaTemplate.send(comm.ingest.v1)` → 202 + messageId. **archive ingest consumer**: idempotent Mongo upsert (`{_id: messageId}`, unique index `messageKey`), attachment ref → S3 via `BlobStore`, emit `comm.stored.v1`. Generate real 10k corpus, confirm Mongo count. |
| **M2** | Wire ES indexer to consume `comm.stored.v1`; prove `GET /search?q=wire` returns a hit with p95 < 2s. |
| **M3** | Custodian + evidence add endpoints (single + bulk); one Testcontainers IT proving invalid transition rejected (already partly done — extend). |
| **M4** | hold-service: wire outbox poller → publish real `hold.commands.v1{action:PLACE}`; archive hold-applier consumer (`archive-hold-applier` group): resolve scope via `search-service /search/scope-ids`, Mongo `$addToSet{holds:holdId}`, emit `hold.applied.v1`. **Write the failing overlap test first** (two holds cover a msg, release one, msg still held). |
| **M5** | audit-service `@KafkaListener` (`audit-sink`): append-only Mongo insert using `HashChain`; make ingest + hold emit `audit.events.v1`; `GET /audit?caseId=`. export-service: `POST /exports` → `export.jobs.v1` → 202 QUEUED (worker is Day 3). |

### 18:00 — Day 1 hard gate (all seven must be true)
1. `docker compose up` starts infra + 7 services from clean volumes.
2. 10k+ messages generated, 20+ custodians, ≥5% attachments.
3. Mongo holds the corpus (first real MongoDB use).
4. Kafka shows `comm.ingest.v1` + `comm.stored.v1` with separate consumer groups.
5. ES populated by its own consumer.
6. `GET /search?q=...` returns a real hit.
7. `mvn verify` runs + produces coverage report; `main` green in CI.

**Technologies lit up Day 1:** Kafka, MongoDB, Elasticsearch, AWS S3/LocalStack,
Spring Boot, multithreading (generator), Factory, Builder, Adapter, Observer,
PG/SQL, Git/CI.

---

## Day 2 — Tue Sep 8: Cases, holds, retention, search depth

**Goal:** the FR-2/FR-3/FR-4/FR-5 core is real and demonstrable: search → case
→ evidence → hold → **delete of a held message is blocked**.

| Member | All day |
|---|---|
| **M1** | Hardening: idempotency test (N re-submits ⇒ 1 Mongo doc) as `@ParameterizedTest`; claim-check path for large attachments; `comm.ingest.v1.DLT` + `DefaultErrorHandler` with backoff; resiliency scaffolding for the Day-4 demo (consumer lag metric exposed). Dashboard counts API (ES aggregation: total messages, active cases, active holds, exports completed). Dashboard Angular screen. |
| **M2** | Full FR-3: filters (date/type/custodian/hasAttachment/onHold) via **Composite** filter tree; highlighting (`<mark>`); pagination (`from`/`size`) + sort (date/relevance); saved-search re-run (stores `QuerySpec` JSON via case-service `POST /saved-searches`); `POST /search/scope-ids` PIT + `search_after` streaming for bulk. Search Angular screen with highlighting pipe. |
| **M3** | Finish case-service: `@ClosedCaseGuard` AOP active on all mutating ops (reject with 409 CASE_CLOSED); closed-case triggers `case.closed.v1` via outbox; custodian CRUD; saved-search CRUD. Case Detail Angular screen (**Facade** aggregating case+holds+evidence). |
| **M4** | Implement the overlap test (now passing): hold-set `$addToSet`/`$pull`, `onHold = holds.size>0` derived; `hold.applied.v1` progress → hold-service updates heldCount/status ACTIVE on `completed:true`. Release hold on `case.closed.v1`. `RetentionGuard` as protection **Proxy** wired into archive `DELETE /messages/{id}` → 409 `MESSAGE_ON_HOLD` with holds list; defence-in-depth `deleteOne({_id, holds:{$size:0}})`. **Retention Strategy** per comm type (minute-level demo values). Holds + Disposition Angular screens. |
| **M5** | export-service worker: **Template Method** `AbstractExportPackager`; fetch message+attachments from archive → `DigestOutputStream` **Decorator** (SHA-256 per item) → `ManifestBuilder` → zip → S3; pre-signed expiring URL (15 min TTL); retry-safe (`attempt-N` then atomic copy, jobId idempotency). audit-service: `GET /audit/verify-chain?caseId=`; enforce insert-only Mongo role (no update/remove grant); test that **no write API exists**. Exports + Audit Angular screens. |

### 13:00 + 18:00 checkpoints
At 13:00: merge, `compose down -v && up`, smoke. At 18:00 hard gate:
- Search with filters + highlighting works (FR-3).
- Case DRAFT→ACTIVE→UNDER_REVIEW→CLOSED; invalid transition rejected; CLOSED = read-only (FR-2).
- Place hold → 202 → progress → ACTIVE; **`DELETE` held message → 409** (FR-4.6 proof).
- Overlapping holds: release one, message still held (FR-4.5).
- Export QUEUED→RUNNING→COMPLETED; download via expiring link; `verify-export.sh` PASS; flip a byte → FAIL (FR-6).
- Audit trail shows every action above; `verify-chain` PASS; no write endpoint (FR-7).

**Technologies lit up Day 2:** State, Strategy, Proxy, Command, Template Method,
Decorator, Composite, Facade, AOP, Memento (audit before/after), SOLID
(hexagonal), PG/SQL (JSONB/outbox/reporting), InfoSec (insert-only role, hash
chain).

---

## Day 3 — Wed Sep 9: Retention, frontend wiring, end-to-end investigator flow

**Goal:** the full investigator workflow completes from the Angular UI with no
Postman. Retention/disposition is real.

| Member | All day |
|---|---|
| **M1** | Reindex endpoint `POST /admin/reindex` (reads Mongo, republishes `comm.stored.v1` — proves ES is not the source of truth); Kafka offset rewind for ES rebuild; ingest DLT replay tooling. Polish dashboard with live counts. |
| **M2** | Bulk "add all results to case" via `scope-ids` (FR-3.6 stretch); scroll/PIT paging in UI; ES aggregations for hold-scope preview; fix any search/indexing bugs from Day 2 integration. |
| **M3** | Evidence bulk-add from search (batch of 1000 via `scope-ids`); `UNIQUE(case_id, message_id)` idempotency; reporting SQL (`heldCount`/evidence counts per case via `GROUP BY`). Wire Case Detail Facade to live hold/evidence/export reads. |
| **M4** | **Disposition** `@Scheduled` job (`fixedDelayString`): pages Mongo `timestamp < now - retention(type)`, calls `RetentionGuard`, deletes eligible + S3 objects, writes `disposition_runs` (deleted/skippedOnHold/errors), emits `audit.events.v1{DISPOSITION_RUN}`. Demo: set email retention to 2 min, trigger, show `deleted: N, skippedOnHold: M`. Mongo lock so only one replica runs it. |
| **M5** | Export SSE progress (`GET /exports/{id}/stream` — **Observer**); export failure→retry (Spring Kafka `DefaultErrorHandler`, 3 attempts, DLT); `POST /exports/{id}/verify` server-side. Audit filtering (by case/actor/action/time). All frontend screens consuming live APIs. |

### 18:00 hard gate
- Full demo path runs from Angular UI only: generate → search → create case → add evidence → place hold → prove delete blocked → disposition run → export → download → verify → view audit.
- Retention deletes expired unheld, skips held, records counts (FR-5).
- Every screen is live, no stubs.

---

## Day 4 — Thu Sep 10: Resiliency, tests, coverage, K8s, rehearsal

**Goal:** one-command test run + coverage report; resiliency scenario passes;
15-20 min rehearsed demo. K8s is a bonus only if the demo is stable.

| Time | All five |
|---|---|
| **09:00–11:00** | **Tests + coverage push.** Each member writes/extends tests for owned code: unit (JUnit5/Mockito/AssertJ) for all critical logic, Testcontainers ITs for cross-boundary paths (ingest→Mongo→ES visibility, hold propagation, disposition-skips-held, export-verifiable, audit-append-only). Target ≥75% line on services, **100% on the 4 critical classes**. Fix JaCoCo gate. |
| **11:00–13:00** | **Resiliency demo rehearsal (the highest-value 3 min):** `docker compose stop archive-service`; ingest 2000 more (UI degrades gracefully, no cascade); `docker compose start archive-service`; lag drains to 0; `GET /search` returns the 2000. Add **Resilience4j circuit breaker** + bounded **bulkhead** (`ThreadPoolExecutor`) in gateway/export. State the point: Kafka retained events, ingest never failed, index caught up — ES is not the source of truth, nothing lost (NFR-2). |
| **13:00–14:00** | Lunch + integration checkpoint. |
| **14:00–15:30** | **K8s stretch (only if demo is green):** `kubectl apply -k k8s/overlays/local` for at least `search-service` or `export-service` with HPA + liveness/readiness probes + PDB. If anything wobbles, abandon and protect the demo. |
| **15:30–17:00** | **Full demo rehearsal** end-to-end on a clean `compose down -v && up`. Run `e2e/demo-flow.sh` twice. Fix flaky bits. Pre-seed corpus so the demo doesn't depend on live generation. Keep a screen recording as fallback. |
| **17:00–17:30** | **Git identity audit:** `git log --format='%an %ae' \| sort -u` — all 5 members must appear with meaningful commit counts across all 4 days. PR history: every PR has a non-author approval. Branch protection on. |
| **17:30–18:00** | **Presentation prep:** who presents what; prepared "why X not Y" answers (Section 12 of architecture.md); the resiliency script is bulletproof. |

### 18:00 hard gate (Definition of Done)
1. `./run-tests.sh` → `mvn verify` + `ng test` green, aggregate JaCoCo report at `coverage-report/target/site/jacoco-aggregate/`.
2. Resiliency scenario passes with zero message loss.
3. Full 12-min demo runs cleanly twice from `compose down -v`.
4. K8s deploy works *or* is honestly called out as stretch.
5. `git log` shows all 5 identities; PRs reviewed; `main` green.
6. `docs/architecture.md` + `docs/ai-usage.md` + README ownership table final.

---

## Training-technology coverage checkpoint (by end of Day 4)

| Training topic | Where it's load-bearing | Day lit up |
|---|---|---|
| Git/GitHub | 5-author repo, PRs, CI, branch protection | D1–D4 |
| Core Java (records/sealed) | events, DTOs, state machine | D1 |
| Multithreading | generator `ExecutorService` + virtual threads; export bulkhead | D1, D2 |
| Exceptions | typed domain exc → RFC-7807; retry-vs-DLT classification | D1–D2 |
| Collections/Generics | `EventEnvelope<T>`, `Set<String>` holds, transition map | D1 |
| TDD/mocking | one-command suite + coverage report (deliverable) | D1, D4 |
| Creational (Factory/Builder/Singleton/Prototype) | corpus Factory, Manifest/QuerySpec Builder, beans, saved-search clone | D1–D2 |
| OOP & SOLID | hexagonal ports/adapters per service | D1–D2 |
| Spring Boot | web, JPA, Mongo, ES, Kafka, AOP, scheduling, profiles, Gateway, Flyway | D1–D3 |
| PG/SQL | case + hold SoR, enums, JSONB, interval, outbox, reporting SQL | D1–D3 |
| Kafka | backbone, 6 topics, consumer groups, DLT, replay, NFR-2 demo | D1, D4 |
| MongoDB | message corpus + append-only audit (insert-only role) | D1, D2 |
| Elasticsearch | full FR-3, analyzer, highlighting, PIT, rebuild-from-Kafka | D1–D2 |
| AWS S3 | attachments + export packages, pre-signed URLs, Object Lock | D1–D2 |
| Docker & K8s | one-command compose + stretch K8s deploy | D1, D4 |
| Angular | single SPA, 8 screens, SSE, interceptor, pipes | D2–D3 |
| Structural (Adapter/Facade/Decorator/Proxy/Composite) | S3 adapter, Case Facade, DigestOutputStream, RetentionGuard proxy, filter tree | D1–D3 |
| Behavioral (State/Strategy/Observer/Command/Template/Chain/Iterator/Memento) | case State, retention Strategy, Kafka/SSE Observer, hold Command, export Template, guard Chain, PIT Iterator, audit Memento | D1–D3 |
| Distributed/resiliency | CQRS, outbox, idempotent consumer, CB, bulkhead, retry+DLT, replay, graceful degradation | D1, D2, D4 |
| InfoSec | no secrets, least-priv DB roles, insert-only audit, hash chain, short-TTL URLs, non-root containers, dependency scan in CI | D2, D4 |

---

## The three rules that determine whether this plan lands

1. **Nobody works on their own module before the Day-1 slice is alive.** Morning
   is shared plumbing; the slice is the whole team's job until `GET /search`
   returns a hit.
2. **Write the test for each business rule alongside the rule, not on Day 4.**
   The rubric rewards real tests; Day-4-only tests are always shallow. The
   overlap-hold test is red on Day 1, green on Day 2 — that's the signature.
3. **13:00 and 18:00 checkpoints are mandatory, not optional.** A red checkpoint
   stops feature work until green. The most common way this project fails is
   five beautiful isolated services that don't talk on Day 4.
