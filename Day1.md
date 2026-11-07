# DiscoveryHub — Execution Approach & 5-Way Work Split (4 days)

This is the *how*, layered on top of your existing plan doc. The plan doc says what to build; this says how a team of 5 actually lands it in 4 days without a Day-4 integration disaster.

---

## 0. Non-negotiable version choices (decide in the first 30 minutes)

Pick these once, put them in the root `pom.xml` and `docker-compose.yml`, never debate again. Old versions are also an easy way to lose credibility in Q&A.

| Component | Use | Why not older |
|---|---|---|
| Java / JDK | **21 (LTS)** | JDK 17 and below are aging out of support; 21 gives virtual threads and generational ZGC. Anything <= 17 is a HIGH-priority finding in a review. |
| Spring Boot | **3.x latest stable** | Boot 2.x is EOL for free OSS security patches, is stuck on `javax.*`, and can't use Jakarta APIs, AOT images, or virtual-thread integration. Requires JDK 17+ anyway, so it pairs with JDK 21. |
| MongoDB | **7.x** (6.x minimum) | Mongo < 6 is EOL and on your data path — unpatched CVEs plus missing query-engine/time-series improvements. Teams mask the resulting slow queries by buying bigger nodes. |
| Kafka | **3.x, KRaft mode (no ZooKeeper)** | Kafka 2.x clients/brokers lag on cooperative rebalancing and tiered storage, carry unpatched CVEs, and cause subtle version-skew rebalance bugs. KRaft also removes a container from Compose. |
| Elasticsearch | **8.x** | Matches the Spring Data ES version in Boot 3.x. |
| PostgreSQL | **16** | — |
| Angular | **latest LTS (17+)**, standalone components | — |

Bottom line to say out loud in the demo: these aren't cosmetic upgrades. EOL runtimes and data infra on the data path are a leading cause of Sev1s, and the usual "fix" is over-provisioning hardware, which just inflates cloud spend.

---

## 1. The one strategic decision that wins this project

**Build the vertical slice on Day 1, before anyone owns a module.**

Most 5-person teams build 5 beautiful isolated services and discover on Day 4 that nothing talks. Your plan doc already warns about this (§18). The counter-measure is concrete:

By 18:00 on Day 1, this must run on one machine, in one command:

```
POST /messages/ingest  ->  Kafka message.ingested  ->  Mongo write  ->  Kafka  ->  ES index  ->  GET /search?q=...  returns a hit
```

Even with 1 hardcoded message and zero business logic. Once that pipe is alive, all four remaining days are *filling in* a working system rather than *assembling* one. Everything else in this document is subordinate to that gate.

### Supporting decisions that make the slice cheap
1. **One monorepo, multi-module Maven.** Root `pom.xml` with `<modules>` for each service + a `common` module holding event DTOs, `correlationId`, error envelope, and base exceptions. Shared *code contract*, separate *databases*. Never share schemas.
2. **Contract-first, one hour, whole team.** Write `docs/api-contracts.md` and `docs/events.md` in the 09:00 meeting: exact JSON of each Kafka event and each REST endpoint (path, request, response, error codes). Once merged, changing a contract requires telling the whole team. This is what lets 5 people work in parallel without blocking.
3. **Mock the neighbours immediately.** Every service returns hardcoded-but-contract-shaped responses on Day 1 morning so the frontend and downstream services can start before the real logic exists.
4. **Everyone runs the whole stack locally.** `docker compose up` from hour one — infra containers (postgres, mongo, es, kafka, localstack) merged Day 1 morning by Member 1 so nobody is blocked on infra.
5. **LocalStack for S3.** No real AWS account needed, no credentials in the repo, and the `StorageService` adapter means switching to real S3 is a config change. Say this in Q&A — it demonstrates Dependency Inversion, not laziness.

---

## 2. Work split — equal by effort, not by line count

The trap: Member 5 (export + audit + S3) and Member 1 (ingestion + corpus) look small on paper and are actually the biggest. Here's the balanced allocation. Each member owns **one backend area + one frontend area + their own tests**, and every member writes Angular.

### Member 1 — Ingestion & Corpus  (+ owns Docker/Compose)
- Corpus generator: 10k+ messages, 20+ custodians, threads, participants, >=5% attachments. **Factory** pattern for Email vs Chat.
- `POST /messages/ingest` (accept + publish only, returns 202) and a separate consumer that writes to Mongo — the acceptance/durability split is an explicit requirement.
- Idempotency: deterministic `externalMessageId` + unique index; duplicate ingest must be a no-op.
- Publishes `message.ingested`; consumes `message.held` / `message.released` to flip message status; enforces "held message cannot be deleted or modified" at the source of truth.
- Frontend: **Dashboard** + ingestion status.
- Infra: `docker-compose.yml`, all Dockerfiles, the `common` module skeleton. Delivered Day 1 morning.
- Tests: duplicate ingest, ID uniqueness/immutability, delete-blocked-when-held.

### Member 2 — Search & Elasticsearch  (+ owns the shared Angular shell)
- ES mapping + indexer consuming `message.ingested`. ES is an index, never the source of truth.
- Full text over subject/body/participants; filters date/type/custodian/hasAttachment/onHold; highlighting; pagination; sort by date & relevance.
- Re-index endpoint (proves ES is rebuildable — a likely evaluator question).
- Consumes `message.held`/`released` to keep the `onHold` flag searchable.
- Saved searches (API lives with Member 3's Postgres; Member 2 owns the search-side integration) and bulk "add this page to case".
- Frontend: **Search screen** + the shared shell — routing, nav, HTTP interceptor, error toast, loading spinner, environment config. This is why his backend area is slightly narrower: everyone else consumes his shell.
- Tests: full-text, each filter, pagination, sorting, backlog catch-up after ES/consumer restart.

### Member 3 — Case & Evidence  (+ owns test/coverage tooling)
- Postgres: `cases`, `case_custodians`, `evidence`, `saved_searches`. Flyway migrations.
- Lifecycle Draft -> Active -> Under Review -> Closed via the **State** pattern; invalid transitions rejected with clear domain errors.
- Closed case is read-only: no new evidence, holds, or exports. Enforced in the case service *and* checked by hold/export services.
- Evidence add (single + bulk from search), custodians CRUD.
- Frontend: **Cases list + Case Detail** (evidence, custodians, holds, saved searches, export trigger).
- Owns: `@RestControllerAdvice` error envelope in `common`, JaCoCo aggregate coverage, Testcontainers base classes, and the single `make test` / `mvn verify` entrypoint. Set up Day 1, not Day 4.
- Tests: invalid transition, closed-case restrictions, evidence linkage.

### Member 4 — Legal Hold & Retention
- `POST /holds` returns a job immediately (202 + jobId/PROCESSING) and matches messages asynchronously — never synchronous.
- Scope = custodians + date range + optional terms; resolve matches by querying Search, apply against Mongo via events.
- **Overlapping holds:** a `hold_count` / hold-reference table per message. Protected while >= 1 active hold covers it; releasing one hold does not unprotect a message another hold still covers. This is the single most-tested rule in the rubric — write the test before the code.
- Publishes `message.held` / `message.released`.
- Retention: configurable per communication type via the **Strategy** pattern, minute-level values for the demo. Scheduled disposition deletes expired *unheld* messages, skips held, records deleted/skipped counts + timestamp, publishes `disposition.completed`.
- Frontend: **Holds screen** (create/release, PROCESSING -> ACTIVE, protected count) + **disposition** view.
- Tests: async hold status, overlap-survives-single-release, retention deletes expired unheld, retention skips held, disposition counts.

### Member 5 — Export, Audit & S3
- Export as an async job: QUEUED -> RUNNING -> COMPLETED/FAILED, driven by `export.requested`.
- **Facade** over fetch -> package -> checksum -> upload. ZIP of readable messages + all attachments + `manifest.json` (**Builder**). Per-item SHA-256 + package SHA-256.
- `StorageService` **Adapter** over the AWS SDK -> LocalStack S3; presigned expiring download link.
- Retry safety: deterministic object key + idempotent job state so a retry never produces a duplicate or corrupt package.
- Audit service: append-only, consumes `audit.event` and every significant event; stores timestamp/actor/action/target/before/after; `previousHash` + `currentHash` chain for tamper evidence; **no update/delete API at all**; filters per-case and system-wide.
- Frontend: **Exports screen** (job progress, package details, download, verify integrity) + **Audit screen**.
- Tests: job transitions, failure/retry no-duplicate, manifest + checksum verification, no audit mutation endpoint exists, audit filtering.

### Shared by all five (build it into the schedule, don't hope for it)
Integration fixes, PR reviews (**every PR needs one non-author approval**), their own Angular screens, their own tests, README/architecture docs, and demo rehearsal.

---

## 3. Revised 4-day rhythm

The plan doc's day themes are right. Two changes:

**Change 1 — daily fixed ceremonies:**
- 09:00 (15 min) standup: what I'll merge today, what I need from whom.
- 13:00 **integration checkpoint**: everyone merges to `main`, whole stack comes up, smoke script runs. A red checkpoint stops feature work until it's green.
- 17:30 **end-of-day gate** against the doc's hard gate + demo the day's slice to each other.

**Change 2 — tests and Angular start on Day 1, not Day 3/4.** Day 4 is *hardening and rehearsal*, not "build the frontend and write all the tests". Write the test for each business rule alongside the rule; the rubric rewards real tests and Day-4-only tests are always shallow.

| Day | Team focus | Hard gate at 18:00 |
|---|---|---|
| 1 | Contracts, skeletons, Compose infra, **the vertical slice** | `docker compose up` works; 10k corpus generated; Mongo has it; Kafka events flowing; ES populated; search returns results |
| 2 | Case lifecycle, async holds, overlapping holds, retention/disposition | Search -> case -> evidence -> hold -> **delete of a held message is blocked** |
| 3 | Export pipeline, audit chain, all Angular screens wired | Whole investigator workflow completable from the Angular UI, no Postman |
| 4 | Tests + coverage, code review pass, resiliency + integrity demos, K8s if stable, rehearsal | One-command test run + coverage report; resiliency scenario passes; 15–20 min rehearsed demo |

Day 4 ordering matters: resiliency and retention demos before Kubernetes. K8s is a bonus; a broken demo is fatal.

---

## 4. The resiliency proof (rehearse it twice)

This is the highest-value 3 minutes of the demo because it's the thing most teams fake:

```
docker compose stop search-service
POST /messages/ingest        # "Project Phoenix quarterly review"
# show consumer lag > 0 on the search consumer group
docker compose start search-service
# lag drains to 0
GET /search?q=Project+Phoenix   # the message is there
```

The point to state: Kafka retained the event, the ingestion path never failed, and the index caught up. Nothing was lost because ES is not the source of truth.

---

## 5. Git discipline (it is individually scored)

- One repo, protected `main`, no direct pushes.
- `git config user.name/user.email` per member on day one — verify with `git log --format='%an %ae' | sort -u` on Day 4. A member with no commits under their own identity loses marks regardless of what they built.
- Branches `feature/<area>-<thing>`, conventional commits (`feat:`, `fix:`, `test:`, `refactor:`, `docs:`), small and continuous across all 4 days.
- Every PR: one non-author approval + the stack still boots. PR body says what and why.
- Squash-merge to keep `main` readable while keeping per-member authorship.

---

## 6. Risk register — the five things most likely to sink you

| Risk | Mitigation |
|---|---|
| Day-1 slice slips | Cut scope, not the slice: 1 hardcoded message end-to-end counts. Whole team swarms it after 16:00. |
| Overlapping-hold logic wrong | Write the test first; it's the most-checked rule. Reference counting, not a boolean flag. |
| Nobody owns integration | The 13:00 checkpoint + shared smoke script are mandatory, not optional. |
| One person becomes the only Angular dev | Each member ships their own screen off Member 2's shell, starting Day 3 at the latest. |
| Demo machine dies / flaky first run | Pre-seed the corpus, script every demo step, run the full demo twice on Day 4, and keep a recording as fallback. |

---

## 7. Day 1, 09:00–10:00 meeting agenda (produce these artifacts, then split up)

1. `docs/architecture.md` — service list, ports, DB ownership, one diagram.
2. `docs/api-contracts.md` — every endpoint with request/response/error JSON.
3. `docs/events.md` — every topic with exact payload, versioned: `eventId`, `eventType`, `timestamp`, `entityId`, `correlationId`, `actor`.
4. Version lock-in from §0 above.
5. Ownership table from §2, written into the README so scoring is unambiguous.
6. Agreement on the 13:00 / 17:30 ceremonies.

Then: Member 1 pushes Compose + `common` + skeletons within two hours; everyone else branches off it.
