# DiscoveryHub — Decision Log

A running record of every architectural and execution decision made for this
project, with context and rationale so any team member (or evaluator) can see
*why* a choice was made — not just *what*. This directly supports the
requirements-doc rubric line: *"defending those choices is part of the
evaluation. Expect the question 'why did you use X here and not Y?'"*

Statuses: **Adopted** (in effect) · **Superseded** (replaced, see replacement)
· **Confirmed** (pre-existing, re-validated)

---

## ADR-style summary table

| ID | Decision | Status | Date |
|---|---|---|---|
| D-001 | Use Java 21 (LTS) | Confirmed | Day 0 |
| D-002 | Use Spring Boot 3.5.x | Confirmed | Day 0 |
| D-003 | Split into 7 services — separate `hold-service` | Adopted | 2026-09-07 |
| D-004 | Kafka as the event backbone (not RabbitMQ/SQS) | Confirmed | Day 0 |
| D-005 | Postgres for cases + holds; Mongo for messages + audit | Confirmed | Day 0 |
| D-006 | Elasticsearch for search (not Postgres FTS) | Confirmed | Day 0 |
| D-007 | Hold set per message (not boolean flag) | Confirmed | Day 0 |
| D-008 | Transactional outbox for case-service + hold-service | Confirmed | Day 0 |
| D-009 | Append-only audit is structural (no write API + insert-only Mongo role + hash chain) | Confirmed | Day 0 |
| D-010 | Defense in depth on delete (`RetentionGuard` + Mongo query guard) | Confirmed | Day 0 |
| D-011 | Dual idempotency guards (Postgres dedupe + Mongo unique index) | Confirmed | Day 0 |
| D-012 | LocalStack for S3 (not real AWS for laptop demo) | Confirmed | Day 0 |
| D-013 | JSON events with versioned topics (not Avro + Schema Registry) | Confirmed | Day 0 |
| D-014 | archive-service is the sole Mongo writer | Adopted | 2026-09-07 |
| D-015 | Cross-service referential integrity by ID + events, not FK | Adopted | 2026-09-07 |
| D-016 | Build the vertical slice before any module deep-work | Adopted | 2026-09-07 |
| D-017 | 5-member ownership: each owns one primary service | Adopted | 2026-09-07 |
| D-018 | Tests written alongside each business rule (not deferred to Day 4) | Adopted | 2026-09-07 |
| D-019 | 13:00 + 18:00 mandatory integration checkpoints | Adopted | 2026-09-07 |
| D-020 | K8s is a Day-4 bonus only if the demo is stable | Adopted | 2026-09-07 |
| D-021 | Resiliency demo is the highest-value 3 minutes of the presentation | Adopted | 2026-09-07 |

---

## D-001 — Use Java 21 (LTS)

**Context.** The training and requirements say "current LTS." The build
machine has Java 26 default, but JaCoCo 0.8.13 cannot instrument Java 26
classes; Java 21 is the supported LTS that pairs with Spring Boot 3.5 and
brings virtual threads (Loom) and generational ZGC.

**Decision.** Pin `<java.version>21</java.version>` and
`<maven.compiler.release>21</maven.compiler.release>` in the root `pom.xml`.
Set `JAVA_HOME` to Temurin 21 for every Maven invocation.

**Why not 17 / 26.** ≤17 is at/near end of free support and lacks Loom (no
virtual threads for the export fetch loop). 26 is too new for JaCoCo and
training-tooling parity. 21 is the sweet spot.

**Consequences.** All Dockerfiles must use `eclipse-temurin:21` base images;
CI `setup-java` must request 21.

---

## D-002 — Use Spring Boot 3.5.x

**Context.** Boot 2.x is EOL for free OSS security patches, stuck on
`javax.*`, and cannot use Jakarta APIs, AOT/native images, the Micrometer
Observation API, or virtual-thread integration.

**Decision.** Parent `spring-boot-starter-parent:3.5.3`; Spring Cloud
`2025.0.0` (the compatible line).

**Why not 2.x.** Starting a new project on 2.x means starting with an
unpatched, `javax`-locked stack. This is a HIGH-priority finding in any
review.

---

## D-003 — Split into 7 services: separate `hold-service`

**Context.** The repo had a divergence: `fullplan.md`/`Day1.md` specified a
separate hold-service (port 8084) owned by Member 4, while `docs/architecture.md`
Section 12 argued against splitting and merged holds+retention into
case-service (6 services). The code followed `architecture.md`.

**Decision.** **Adopt the 7-service split per `fullplan.md`.** Create
`services/hold-service` owning `holds`, `retention_policies`,
`disposition_runs`, the hold command lifecycle, the retention **Strategy**,
and the `@Scheduled` disposition trigger. case-service keeps `cases`,
`case_custodians`, `evidence_items`, `saved_searches`, and the
`CaseStateMachine`.

**Rationale — three independent arguments:**

1. **The "distributed transaction" objection in `architecture.md` Section 12
   does not survive scrutiny.** hold-service owns its own Postgres database
   with no FK to `cases` — only a `caseId` reference column enforced by event,
   not by DB constraint. That is the standard microservice pattern.
   case-closed → release-holds is event-driven
   (`case.closed.v1` → hold-service → `hold.commands.v1{RELEASE}` → archive
   `$pull`), not a 2-phase commit. The outbox + Kafka + idempotent consumer
   *is* the distributed-transaction-avoidance pattern; the objection argues
   against itself.

2. **SRP — the decisive architectural argument.** case-service is small
   relational CRUD: low throughput, transactional, tiny dataset. The
   retention/disposition feature is a corpus-wide async background worker that
   scans 10k docs on a schedule, with a different change cadence, scaling
   profile, and failure mode. Gluing a corpus-wide scheduled worker onto a
   low-throughput transactional CRUD service conflates two very different
   concerns — the opposite of SRP, which `architecture.md` itself preaches.

3. **The individual rubric (40 of 100 pts).** "Ownership of assigned service"
   (20) + "consistent contribution across all 4 days" (20), both scored per
   member from repo history. If holds+retention is merged into case-service,
   Member 4 has no primary service and shares case-service with Member 3. The
   commit history won't cleanly show "Member 4 owns X," and the rubric
   explicitly says *"work that is not visible in the repository cannot be
   credited."*

**Consequences.**
- Move `HoldEntity`/`HoldStatus`/`RetentionPolicyEntity` + the
  `holds`/`retention_policies`/`disposition_runs` tables out of case-service's
  `V1__init.sql` into a hold-service Flyway migration.
- Add hold-service to root `pom.xml` modules + `docker-compose.yml` on port
  8084.
- Update `docs/architecture.md` Section 1 (service table) and Section 12
  (remove/rewrite the wrong anti-split argument) to match `fullplan.md`.
- Update `docs/api-contracts.md` port table.

**Supersedes.** The 6-service layout in `docs/architecture.md` Section 1/12.

---

## D-004 — Kafka as the event backbone

**Context.** Cross-service state change must be asynchronous and decoupled
(FR-1.4, FR-4.3). The NFR-2 resiliency demo requires that a downstream outage
not lose messages.

**Decision.** Kafka 3.9.x in KRaft mode (no ZooKeeper), 6 versioned topics
(`*.v1`) + DLTs, keyed partitioning, separate consumer groups per consumer.

**Why not RabbitMQ / SQS.** We need a **replayable log**, not just a queue:
rebuilding the ES index and reprocessing after an outage are both offset
rewinds. RabbitMQ discards after ack; SQS has no replay and would tie the
laptop demo to AWS. KRaft also removes a container from Compose.

---

## D-005 — Postgres for cases + holds; Mongo for messages + audit

**Context.** Cases/holds need a state machine, referential integrity,
multi-row transactions, and optimistic locking on a small dataset. Messages
are 10k+ schema-variant documents (email vs chat, 0..n attachments, growing
hold sets) with per-document atomic set updates and no cross-document
invariants.

**Decision.** Postgres for case-service + hold-service (separate DBs, no
shared schema); MongoDB for archive-service `messages` and audit-service
`audit_events` (separate DBs + users).

**Why not one store for both.** Using one store would mean either JSONB
gymnastics for the relational side or losing transactional transitions.
Mongo's `$addToSet`/`$pull` give atomic per-document hold-set mutation without
a join table — no cross-document transaction is ever needed on this path.

---

## D-006 — Elasticsearch for search

**Context.** FR-3 requires relevance ranking, highlighting, stemmed full-text
over subject+body+participants, multi-filter combinations, and sub-2s latency
at scale.

**Decision.** Elasticsearch 8.15.x as a CQRS read model, populated by a Kafka
consumer; rebuildable from Kafka (ES is never the source of truth).

**Why not Postgres FTS / `LIKE`.** Postgres FTS could do a weaker version but
would not give highlighting quality, relevance tuning, PIT paging, or
aggregation-based scope previews. Cost: eventual consistency, bounded and
demonstrated.

---

## D-007 — Hold set per message (not boolean flag)

**Context.** FR-4.5: overlapping holds must be handled correctly — releasing
one hold must not unprotect a message another hold still covers.

**Decision.** Store `holds: ["h-1","h-9"]` (a set of hold ids) per Mongo
document; `onHold = holds.length > 0` is a derived, indexed field. Release
removes one id; protection ends only when the set empties.

**Why not a boolean.** A boolean flag makes overlapping-hold release
incorrect — the single most common design mistake in this project and the
most-tested rule in the rubric. Write the overlap test before the code.

---

## D-008 — Transactional outbox for case-service + hold-service

**Context.** Case/hold DB writes and their Kafka events must not diverge,
even if the broker is down when the request arrives.

**Decision.** Write the command row and the outbox row in one Postgres
transaction; a poller publishes and marks `published_at`.

**Why not direct Kafka send.** A direct send can succeed after the DB tx
commits but fail if the broker is down — or vice versa — producing
inconsistent state. The outbox guarantees the command is published.

---

## D-009 — Append-only audit is structural

**Context.** FR-7.3: audit entries can never be updated or deleted through
any API.

**Decision.** audit-service exposes no POST/PUT/PATCH/DELETE; the Mongo user
is granted `insert`+`find` only (no `update`/`remove`); each entry carries
`prevHash` and `hash = SHA-256(prevHash + canonical(entry))` forming a
per-case hash chain. `GET /audit/verify-chain?caseId=` re-walks it.

**Why not policy-only.** A policy can be bypassed by a bug or a privileged
user; structural enforcement (no endpoint + insert-only DB role + hash chain)
makes tampering detectable and, for the API path, impossible.

---

## D-010 — Defense in depth on delete

**Context.** FR-4.2/FR-4.6: held messages cannot be deleted or modified by
any part of the system, including retention/cleanup.

**Decision.** Two layers: (1) `RetentionGuard.assertDeletable(msg)` on the
delete path throws `HeldItemException` → HTTP 409 `MESSAGE_ON_HOLD`; (2) the
delete query itself is `deleteOne({_id: id, holds: {$size: 0}})` — even a
guard bug cannot remove a held document, and `deletedCount == 0` is treated as
"blocked."

**Why not the guard alone.** A single guard is a single point of failure; the
query-level guard is defence in depth and is the demo proof.

---

## D-011 — Dual idempotency guards

**Context.** FR-1.6: re-submitting the same message must not create
duplicates, even under consumer retries and duplicate producer sends.

**Decision.** Two guards: (1) ingest-service `INSERT … ON CONFLICT DO
NOTHING` on `ingest_dedupe(message_key)`; (2) archive-service Mongo
`updateOne({_id: messageId}, {$setOnInsert: …}, {upsert:true})` with a unique
index on `messageKey`.

**Why not one guard.** Two guards make at-least-once delivery safe without
needing exactly-once semantics.

---

## D-012 — LocalStack for S3

**Context.** NFR-4 requires one-command laptop startup with no AWS account.

**Decision.** LocalStack (S3 only) for the offline/laptop path; one `S3Client`
bean, endpoint switched by profile; real AWS S3 when `AWS_PROFILE` is set.

**Why not real AWS for the demo.** No credentials in the repo, no AWS account
dependency, and the `StorageService` adapter means switching to real S3 is a
config change — demonstrates Dependency Inversion, not laziness.

---

## D-013 — JSON events with versioned topics (not Avro + Schema Registry)

**Context.** Events need a schema contract; the question is JSON vs Avro.

**Decision.** JSON payloads, versioned topic names (`*.v1`), contracts in
`docs/events.md` mirrored by Java records in `common`.

**Why not Avro + Schema Registry.** Registry is the right long-term answer;
for a 4-day build it adds a container and a build plugin without changing any
demo outcome. Called out as a known trade-off in Q&A.

---

## D-014 — archive-service is the sole Mongo writer

**Context.** After the 7-service split, hold-service owns hold *business logic*
but must not mutate Mongo directly (that would break single-writer data
ownership for the `messages` collection).

**Decision.** archive-service remains the only service that writes to Mongo.
hold-service emits `hold.commands.v1`; archive's hold-applier consumer applies
the `$addToSet`/`$pull` and emits `hold.applied.v1`. Similarly, hold-service's
disposition trigger emits `disposition.commands.v1`; archive executes the
`RetentionGuard`-guarded delete.

**Consequences.** Clean data ownership — one writer per collection — while
hold-service owns the command lifecycle. Communication is by events, not
shared DB access.

---

## D-015 — Cross-service referential integrity by ID + events, not FK

**Context.** With 7 services each owning their DB, a hold-service `holds` row
references a `caseId` owned by case-service. A DB-level FK would require a
shared schema, violating NFR-1.

**Decision.** Cross-service references are ID columns (`caseId`) enforced by
events (`case.closed.v1` triggers hold release), not DB foreign keys.
Cross-service *reads* are REST (`GET /cases/{id}` to check status before
accepting a hold/export).

**Consequences.** No shared schemas (NFR-1 satisfied); eventual consistency on
case-closed propagation, bounded and demonstrable.

---

## D-016 — Build the vertical slice before any module deep-work

**Context.** `fullplan.md` Section 1: the one strategic decision that wins the
project is the Day-1 vertical slice before anyone owns a module. The repo
currently has the four critical algorithms but **no Kafka wiring at all** —
the slice does not run.

**Decision.** Day 1 morning is shared plumbing; the whole team swarms the
slice (ingest→Kafka→Mongo→ES→search) until `GET /search` returns a hit. No
one works on their own module depth before the slice is alive.

**Why.** Once the pipe is alive, all four remaining days are *filling in* a
working system rather than *assembling* one. Everything else is subordinate to
that gate.

---

## D-017 — 5-member ownership: each owns one primary service

**Context.** The individual rubric (40 of 100 pts) scores "ownership of
assigned service" and "consistent contribution across all 4 days" per member
from repo history.

**Decision.** Each of the 5 members owns exactly one primary service end to
end (code + tests + Dockerfile + demo slice). archive-service is the one
shared service — it has three independent consumer groups, each owned by the
member who owns that event domain. Everyone also writes their own Angular
screen(s) and contributes integration wiring.

**Mapping.** M1 ingest+corpus+events; M2 search+shell; M3 case+test tooling;
M4 hold; M5 export+audit+frontend platform. See `docs/execution-plan.md`.

---

## D-018 — Tests written alongside each business rule (not deferred to Day 4)

**Context.** `fullplan.md` Section 3: Day-4-only tests are always shallow; the
rubric rewards real tests.

**Decision.** Write the test for each business rule alongside the rule. The
overlap-hold test is red on Day 1, green on Day 2 — that's the signature.
Target ≥75% line on services, 100% on the four correctness-critical classes
(`CaseStateMachine`, `RetentionGuard`, `ManifestBuilder`, `HashChain`).

---

## D-019 — 13:00 + 18:00 mandatory integration checkpoints

**Context.** The most common way this project fails is five beautiful isolated
services that don't talk on Day 4.

**Decision.** At 13:00 and 17:30/18:00 every day: everyone merges to `main`,
whole stack comes up (`compose down -v && up`), smoke script runs. A red
checkpoint stops feature work until it's green.

---

## D-020 — K8s is a Day-4 bonus only if the demo is stable

**Context.** NFR-4 names orchestrated deployment a stretch goal. A broken demo
is fatal; a missing K8s stretch is not.

**Decision.** On Day 4, attempt `kubectl apply -k k8s/overlays/local` for at
least one service (search or export) with HPA + probes + PDB **only after** the
demo is green and rehearsed. If anything wobbles, abandon and protect the
demo. Resiliency and retention demos come before Kubernetes.

---

## D-021 — Resiliency demo is the highest-value 3 minutes of the presentation

**Context.** `fullplan.md` Section 4: the resiliency proof is the thing most
teams fake. Doing it for real is the highest-impact demo moment.

**Decision.** Rehearse twice on Day 4:
`docker compose stop archive-service` → ingest 2000 more → restart → lag
drains → `GET /search` returns them. State the point: Kafka retained the
event, ingest never failed, ES caught up — ES is not the source of truth,
nothing lost (NFR-2).

---

## How to update this log

- Every decision that changes architecture, service boundaries, tech choices,
  or the execution sequence gets a new `D-0NN` entry with Context, Decision,
  Rationale, and Consequences.
- Superseded decisions stay in place with status **Superseded** and a
  `Supersedes`/`Superseded by` cross-reference — never delete history.
- This file is the single source of truth for "why X and not Y" during the
  presentation Q&A.
