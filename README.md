# DiscoveryHub

E-Discovery & Legal Hold Platform — a microservices system for ingesting business
communications, searching them, placing legal holds, exporting evidence with a
verifiable chain of custody, and auditing every action. Built end-to-end
(backend, messaging, storage, search, frontend, deployment) per
`requirements.docx`, using every technology from `training.pdf` in a load-bearing
role.

Full design lives in `docs/architecture.md`; decisions and "why X not Y" defenses
in `DECISIONS.md` and `docs/decisions.md`.

---

## Current verified runtime status

The full stack runs via `docker compose up` (Postgres, Mongo, Kafka, Elasticsearch,
LocalStack, all 7 backend services, the gateway, and the Angular frontend), each
with a healthcheck gating startup order. `BlobStore` is implemented against S3
(via LocalStack) in `ingest-service`, `archive-service`, and `export-service` —
attachments and export packages are real S3 objects with pre-signed download
URLs. Deployment is Docker Compose only; there is no Kubernetes or cloud (EC2/EKS)
deployment target for this project.

---

## Prerequisites

- **Java 21 (Temurin)**: `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`
  (default `java` on this machine is 26; JaCoCo 0.8.13 cannot instrument Java 26
  classes — always set `JAVA_HOME` to 21 for Maven).
- **Maven 3.9+**.
- **Docker Desktop** running (engine + compose).
- **Node.js LTS** for the Angular frontend (`npm install` in `web/` on first use).

---

## Build and test

```bash
# Full build + all tests + JaCoCo coverage report
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
DOCKER_HOST=unix:///var/run/docker.sock \
mvn -T1C verify

# Just case-service + its dependencies
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
mvn -pl services/case-service -am verify

# Compile only (fast check)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
mvn -q -T1C compile
```

The aggregate JaCoCo report lands at
`coverage-report/target/site/jacoco-aggregate/index.html` after `mvn verify`.

**Testcontainers note:** `*IT` integration tests run in the failsafe phase and
need Docker Desktop running (real Postgres/Mongo/Kafka/ES containers); they
skip gracefully via `Assumptions.assumeTrue` when Docker is unavailable, so
`mvn verify` still passes on a laptop without Docker.

### Running one test class

Unit tests (`*Test`, surefire) need their module's upstream deps built — either
`install -DskipTests` the reactor once, or pass `-am`:

```bash
# One surefire unit-test class (also builds upstream deps)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
mvn -pl services/hold-service -am test -Dtest='HoldServiceTest' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false

# One failsafe integration test (*IT) — runs in the verify phase
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
mvn -pl services/case-service -am verify \
  -Dit.test=ClosedCaseGuardIT -DfailIfNoTests=false -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dtest=ZZZ -Dsurefire.failIfNoSpecifiedTests=false
```



### End-to-end smoke and demo scripts

- `scripts/smoke.sh` — the one-command end-to-end check the team runs at every
  checkpoint (ingest one message, poll search until it appears, exit non-zero
  on failure).
- `scripts/resiliency-ingest.sh`, `scripts/resiliency-disposition.sh`,
  `scripts/demo-disposition.sh`, `scripts/verify-export.sh` — Day-4 resiliency
  and disposition demo drivers.

---

## Container architecture — one container per service (+ infra)

Microservices (NFR-1: ≥4 independently deployable backend services) means **each
service is its own container**. `docker-compose.yml` already declares all of
them; `docker compose up` creates and starts them — you do not hand-create them
in Docker Desktop's UI.

**15 containers, one command:**

| Layer | Containers |
|---|---|
| Infra (5) | `postgres`, `mongo`, `kafka`, `elasticsearch`, `localstack` |
| Services (8) | `ingest-service`, `archive-service`, `search-service`, `case-service`, `hold-service`, `export-service`, `audit-service`, `api-gateway` |
| Frontend (1) | `web` (Angular + nginx) |
| On-demand (1) | `generator` (profile-gated, runs only with `--profile seed`) |

This is what satisfies NFR-4: "The full system must start with a single command
on a laptop (containerized)."

### Port map

| Component | Host port |
|---|---:|
| API gateway | 8080 |
| Ingest service | 8081 |
| Archive service | 8082 |
| Search service | 8083 |
| Case service | 8084 |
| Export service | 8085 |
| Audit service | 8086 |
| Hold service | 8087 |
| Web (Angular) | 4200 |
| PostgreSQL | 5433 *(host; 5432 inside the compose network — remapped to avoid the Homebrew conflict)* |
| MongoDB | 27017 |
| Kafka | 9094 *(host; 9092 inside the compose network)* |
| Elasticsearch | 9200 |
| LocalStack S3 | 4566 |

---

## Bringing up real infrastructure for the first time

No manual setup is required — no MongoDB Atlas cluster to build, no Postgres
database to hand-create, no connection string to find. `docker compose up`
provisions everything via mounted init scripts:

- `infra/postgres/init/01-databases.sql` → creates `discoveryhub_case` /
  `discoveryhub_ingest` / `discoveryhub_hold` / `discoveryhub_export` databases
  + least-privilege users (`case_svc`, `ingest_svc`, `hold_svc`, `export_svc`).
- `infra/mongo/init/01-users.js` → creates `discoveryhub_archive` +
  `discoveryhub_audit` databases and users; audit gets an `insert`+`find`-only
  role (no `update`/`remove` — append-only is structural, not policy).
- `infra/localstack/init/ready.d/01-create-buckets.sh` → auto-creates
  `discoveryhub-attachments` + `discoveryhub-exports` S3 buckets on LocalStack
  ready.

### Port 5432 note (already handled)

Homebrew PostgreSQL 17 listens on host port 5432 on this machine, so
`docker-compose.yml` already maps Postgres as `["5433:5432"]` (the same trick
Kafka uses — `9094:9092` — to dodge the Homebrew Kafka on 9092). No change is
needed; connect from your laptop on **5433**. Inside the Docker network,
services still reach Postgres at `postgres:5432`.

### Step 1 — start infra only (fastest way to see real DBs running)

```bash
cd /Users/shree.singal/projects/DiscoveryHub_Daemons
docker compose up -d postgres mongo kafka elasticsearch localstack
```

### Step 2 — verify each is real (from your laptop)

```bash
# Postgres — connect to the case DB as case_svc (host port 5433)
psql -h localhost -p 5433 -U case_svc -d discoveryhub_case
# \dt   → empty (Flyway runs when case-service starts, not yet)

# Mongo — connect to the archive DB
mongosh "mongodb://archive_svc:archive_svc@localhost:27017/discoveryhub_archive?authSource=discoveryhub_archive"
# show collections   → empty until archive-service ingests

# Elasticsearch
curl http://localhost:9200/_cluster/health

# Kafka — list topics (none yet; services create them on boot)
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# S3 (LocalStack)
docker compose exec localstack awslocal s3 ls
# → discoveryhub-attachments  discoveryhub-exports
```

All five are live and empty, waiting for the app services.

### Step 3 — start the app services (two options)

**Inside compose (full stack, the demo path):**
```bash
docker compose up -d --build   # builds all 7 service images + gateway + web
```

**From Maven against the running infra (faster iteration, one service at a time):**
```bash
# Example: case-service against the Postgres container on host port 5433
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
SPRING_PROFILES_ACTIVE=local \
CASE_DB_URL=jdbc:postgresql://localhost:5433/discoveryhub_case \
CASE_DB_USER=case_svc CASE_DB_PASSWORD=case_svc \
KAFKA_BOOTSTRAP_SERVERS=localhost:9094 \
mvn -pl services/case-service spring-boot:run
```

### Step 4 — seed the corpus (on-demand generator container)

```bash
docker compose --profile seed run --rm generator \
  --messages 12000 --custodians 25 --attachment-pct 8
```

---

## Tech stack — every training topic is load-bearing

Section 6 of `requirements.docx`: "Every technology covered in the training
program must be used somewhere in the solution, meaningfully (not as
decoration)." The rubric awards 15 pts for "Architecture & justified technology
choices" and expects a "why X and not Y" defense for every major choice. This
stack covers all of it:

| Training topic (training.pdf) | Tech | Where it's load-bearing | Requirement |
|---|---|---|---|
| Git/GitHub | Git + GitHub | 5-author repo, PRs, branch protection, CI | Deliverable #2; collaboration (15 pts) |
| Core Java | Java 21 (records/sealed) | event DTOs + state machine | FR-1…FR-7 |
| Multithreading | `ExecutorService` + virtual threads | corpus generator fan-out; export worker bulkhead | FR-1.2, NFR-3 |
| Exceptions | typed domain exc → RFC-7807 | `GlobalExceptionHandler`, retry-vs-DLT | FR-2.2, NFR-2 |
| Collections/Generics | `EventEnvelope<T>`, `Set<String>` holds | event envelope, overlapping-hold set | FR-4.5 |
| TDD/mocking | JUnit5 + Mockito + AssertJ | one-command suite + JaCoCo coverage | Deliverable #3 (10 pts) |
| Creational patterns | Factory, Builder, Singleton | corpus `CommunicationFactory`; `ManifestBuilder`; beans | FR-1.2, FR-6.3 |
| OOP & SOLID | ports-and-adapters | `BlobStore` interface in `common`; hexagonal per service | NFR-1, code quality |
| Spring Boot | Spring Boot 3.5.x | web, JPA, Mongo, ES, Kafka, AOP, scheduling, Gateway, Flyway | every service |
| PG/SQL | PostgreSQL 17 | case/hold/export SoR, enums, JSONB, outbox, reporting SQL | FR-2, FR-4, FR-6 |
| Kafka | Kafka 3.9 KRaft | backbone, 6 topics, consumer groups, DLT, replay | FR-1.4, NFR-2 |
| MongoDB | MongoDB 7 | message corpus + append-only audit (insert-only role) | FR-1.5, FR-7.3 |
| Elasticsearch | ES 8.18 | full-text, filters, highlighting, PIT, rebuild-from-Kafka | FR-3 |
| AWS S3 | S3 via LocalStack | attachments + export packages, pre-signed URLs | FR-1.3, FR-6.4 |
| Docker | Docker Compose | one-command stack, healthchecks, multi-stage non-root images | NFR-4 |
| Angular | Angular 17+ standalone | single SPA, 8 screens, SSE, interceptor, pipes | FR-8 |
| Structural patterns | Adapter, Facade, Decorator, Proxy, Composite | S3 `BlobStore` adapter; Case Facade; `DigestOutputStream`; `RetentionGuard`; filter tree | FR-3, FR-4.6, FR-6.5 |
| Behavioral patterns | State, Strategy, Observer, Command, Template, Memento | case State; retention Strategy; Kafka/SSE Observer; hold Command; export Template; audit Memento | FR-2.2, FR-5, FR-6, FR-7 |
| Distributed/Resiliency | CQRS, outbox, idempotent consumer, CB, bulkhead, retry+DLT, replay | the resiliency demo (stop a service, no message loss) | NFR-2 |
| InfoSec | least-priv DB roles, insert-only audit, hash chain, no secrets, non-root containers | audit tamper-evidence, no secrets in repo | FR-7.3, code quality |

### "Why X and not Y" defenses (recorded in `DECISIONS.md`)

1. **Kafka over RabbitMQ/SQS** — need a *replayable log* (rebuild ES, reprocess
   after outage = offset rewind). RabbitMQ discards after ack; SQS has no replay
   and ties the demo to AWS.
2. **MongoDB for audit (not Postgres)** — append-only with variable payloads;
   Mongo grants an `insert`+`find`-only DB role so append-only is **structural,
   not policy**.
3. **MongoDB for the corpus** — schema-variant (email vs chat, 0..n attachments,
   growing hold sets), high-volume single-doc upserts, `$addToSet`/`$pull` for
   atomic hold-set mutation. No cross-doc transactions needed.
4. **LocalStack S3 (not real AWS)** — no credentials in repo, no AWS account
   needed for the demo, and the `BlobStore` Adapter means switching to real S3 is
   a config change. This demonstrates Dependency Inversion, not laziness.
5. **Enum state machine (not Spring StateMachine)** — 4 case statuses, flat
   transitions; a hand-rolled enum table is pure Java, fully unit-testable
   without a Spring context, and keeps the domain framework-free.
6. **Fail-closed disposition** — legal hold is a legal obligation; a false
   positive (retain a deletable message) is recoverable, a false negative (delete
   a held message) is not and creates legal liability.
7. **JDK 21 / Spring Boot 3.5.x** — Boot 2.x is EOL (no free security patches),
   `javax`-locked, can't use Jakarta/virtual threads. Mongo <6 and Kafka <3 are
   EOL on the data path.

---

## Module layout

```
common/                              shared event DTOs + BlobStore + ApiError + GlobalExceptionHandler
services/discoveryhub-events         shared event DTOs
services/discoveryhub-testsupport    Testcontainers base classes (Postgres, Mongo, Kafka, ES)
services/ingest-service              accept + publish (Postgres dedupe), 8081
services/archive-service             Mongo corpus + S3 attachments + retention, 8082
services/search-service              Elasticsearch index + full FR-3 + dashboard counts, 8083
services/case-service                cases/custodians/evidence/saved-searches, Postgres, 8084
                                     (strict one-directional lifecycle DRAFT → ACTIVE →
                                     UNDER_REVIEW → CLOSED, FR-2.2)
services/hold-service                async holds + retention/disposition, Postgres, 8087
services/export-service              async export jobs + S3 packages, Postgres, 8085
services/audit-service               append-only Mongo audit + hash chain, 8086
services/api-gateway                 Spring Cloud Gateway routing, 8080
web/                                 Angular SPA (nginx), 4200
tools/corpus-generator               10k+ message generator (Factory pattern)
scripts/                             smoke.sh + resiliency/disposition demo drivers
infra/postgres/init                  DB + user provisioning (runs on first boot)
infra/mongo/init                     DB + user + insert-only role provisioning
infra/localstack/init                S3 bucket auto-creation on ready
coverage-report                      aggregate JaCoCo report module
docs/                                architecture, api-contracts, events, execution-plan, decisions
```

### Reading the code: OOP/SOLID annotations

The export/audit/gateway work (and its archive read-path and dashboard files)
carries inline `OOP —` / `SOLID —` javadoc blocks tying each class to the
principle it demonstrates (e.g. `AbstractExportPackager` → Template Method +
OCP, `AuditStorePort` → ISP by omission, `ExportJob` → encapsulation). Grep for
`SOLID —` to walk every annotated example; GoF pattern names (Template,
Decorator, Observer, Adapter, State, Strategy) appear the same way.

---

## Version posture

Pinned in the root `pom.xml` and `docker-compose.yml`:

- Java 21 (Temurin), Spring Boot 3.5.3, Spring Cloud 2025.0.0, Testcontainers
  BOM 1.21.3.
- Container images: PostgreSQL 17 (alpine), MongoDB 7, Kafka 3.9.0 (KRaft),
  Elasticsearch 8.18.1, LocalStack 3.8, Node 22, Java 21 runtime images.

---

## Key docs

- `docs/architecture.md` — full architecture, service list, data models, flows.
- `docs/api-contracts.md` — every REST endpoint (request/response/error JSON).
- `docs/events.md` — every Kafka topic with exact payloads.
- `docs/execution-plan.md` — 4-day / 5-member work split and hard gates.
- `DECISIONS.md` — architecture decision record ("why X not Y").
- `AGENTS.md` — build/verification commands and environment notes for this
  machine.
- `fullplan.md`, `Day1.md` — strategic approach and Day-1 playbook.
- `docs/updates/` — dated session logs of infra/AWS/credential changes for
  the rest of the team to catch up on (start with the most recent file).
