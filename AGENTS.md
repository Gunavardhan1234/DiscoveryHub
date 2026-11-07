# DiscoveryHub — Build & Verification Commands

## Prerequisites
- **Java 21** (Temurin): `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`
- **Maven 3.9+**: `mvn` (installed via Homebrew)
- **Docker Desktop**: running (for Testcontainers integration tests)
- **JDK selection is toolchain-based**: the root pom's `maven-toolchains-plugin`
  compiles and runs tests on JDK 21 from `~/.m2/toolchains.xml`, regardless of
  which JVM runs Maven (terminal, IntelliJ, Homebrew default). Maven itself only
  needs Java 17+ (enforcer-checked). If `~/.m2/toolchains.xml` is missing, run
  `./run-tests.sh` once — it generates it. CI's `actions/setup-java` writes one
  automatically.
- (Why: JaCoCo 0.8.13 can't instrument Java-26 classes, and a JDK-17 Maven fork
  can't load class-file-65 tests — the toolchain removes both failure modes.)

## Build commands

```bash
# Full build + all tests + JaCoCo coverage report (any Maven JVM >= 17 works;
# project code is built/tested on the JDK-21 toolchain)
DOCKER_HOST=unix:///var/run/docker.sock \
mvn -T1C verify

# Just case-service + its dependencies
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
mvn -pl services/case-service -am verify

# Compile only (fast check)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
mvn -q -T1C compile

# Run case-service against a local Postgres Docker container
docker run --rm -d --name case-pg -p 5433:5432 \
  -e POSTGRES_USER=case_svc -e POSTGRES_PASSWORD=case_svc \
  -e POSTGRES_DB=discoveryhub_case postgres:17-alpine

JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
SPRING_PROFILES_ACTIVE=local \
CASE_DB_URL=jdbc:postgresql://localhost:5433/discoveryhub_case \
CASE_DB_USER=case_svc CASE_DB_PASSWORD=case_svc \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
mvn -pl services/case-service spring-boot:run
```

> **Port 5432 note:** Homebrew PostgreSQL 17 runs on port 5432 on this machine.
> Use port 5433 for Docker Postgres containers to avoid conflicts.

## Running specific tests
A service's unit tests need its upstream modules (`discoveryhub-common`,
`discoveryhub-events`, `discoveryhub-testsupport`) built first. Either
`install -DskipTests` the reactor once, or pass `-am`. When targeting a single
test class with `-am`, the upstream modules have no matching test, so add the
"no specified tests" flags (different names for surefire vs failsafe):

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

`*IT` classes run under failsafe (integration-test phase) and need Docker for
Testcontainers; `*Test` classes run under surefire and do not.

## Coverage report
After `mvn verify`, the aggregate JaCoCo report is at:
`coverage-report/target/site/jacoco-aggregate/index.html`

## Testcontainers note
Testcontainers integration tests (`*IT`) skip gracefully when Docker is
unavailable (via `Assumptions.assumeTrue`). There is a known docker-java
incompatibility with Docker Desktop 29.7.2's containerd snapshotter — the
Docker CLI works but the docker-java HTTP client gets 400s. Integration tests
will run in CI or once the docker-java compatibility is resolved.

## Key module layout
- `services/discoveryhub-events` — shared event DTOs + `ApiError` + `GlobalExceptionHandler` (RFC-7807)
- `services/discoveryhub-testsupport` — Testcontainers base classes (Postgres, Mongo, Kafka, ES)
- `services/case-service` — case lifecycle (State pattern), JPA + Flyway + Postgres
- `services/hold-service` — legal hold + retention (port 8087, Postgres `discoveryhub_hold`); `POST /api/holds` (202 PENDING + outbox), `GET /api/holds[?caseId=]`, `DELETE /api/holds/{id}` (202 RELEASING + RELEASE outbox), outbox poller → `hold.commands.v1`, `hold-tracker` consumer flips status ACTIVE/RELEASED; `hold-case-closed` consumer releases all case holds on `case.closed.v1`; retention `Strategy` + `/api/holds/retention-policy`. Archive hold-applier lives in `archive-service` (`archive-hold-applier` group, new files only).
- `services/archive-service` — `DELETE /api/messages/{id}` goes through the `RetentionGuard` protection proxy: 409 `MESSAGE_ON_HOLD` + holds list while held, else defence-in-depth `deleteOne({_id, holds:{$size:0}})` (M4-owned). GET `/api/messages/{id}` + bulk `?ids=` are real Mongo reads via `MessageReadPort`/`MongoMessageReadAdapter` (M5, new files; raw-Document reads normalize `_id`→`id` and `timestamp`←`sentAt`; Mongo `MessageDocument.timestamp` is `@Field("sentAt")`). Day-3 admin endpoints: `POST /admin/reindex` (reads Mongo, republishes `comm.stored.v1` — ES-rebuild proof that Mongo is the source of truth) and `POST /admin/dlt/replay` (replays `comm.ingest.v1.DLT` back onto `comm.ingest.v1`).
- `services/search-service` — `POST /admin/reindex` drops+recreates the ES index then replays `comm.stored.v1` from offset 0 (`KafkaReindexPort`, fresh consumer group). Dashboard `GET /api/dashboard/counts` aggregates ES message count + remote counts; `RestClientRemoteCountsAdapter` derives active cases from `PageResponse.content` (status != CLOSED) and active holds from the bare `/api/holds` array (PENDING/ACTIVE/RELEASING), degrading each card to 0 on failure.
- `services/export-service` — (M5, port 8085, Postgres `discoveryhub_export`) `POST /api/exports` 202 QUEUED + `export.jobs.v1`; worker (`export-worker` group) packages case evidence: case-service evidence ids → archive bulk GET → attachments from S3 → `AbstractExportPackager` Template Method + `DigestOutputStream` decorator + `ManifestBuilder` → zip → `discoveryhub-exports` `exports/{jobId}/package.zip`; retries 3× then `export.jobs.v1.DLT` + FAILED. Reads: list `?caseId=`, `GET /{id}`, `GET /{id}/download-url` (15-min pre-signed, `{url, expiresAt}`), `POST /{id}/verify` (stored zip SHA-256 + manifest re-hash), `GET /{id}/stream` (SSE Observer). Emits `audit.events.v1` (EXPORT_REQUESTED/COMPLETED/FAILED).
- `services/audit-service` — (M5, port 8086, Mongo `discoveryhub_audit`, insert-only role) `audit-sink` consumer chains `hash = SHA-256(prevHash + canonical)` per case; `GET /api/audit` filters caseId/actor/action/targetType/from/to + page/size (cap 500) and
  returns the unpaged match total in the `X-Total-Count` response header (body stays a bare array); `GET /api/audit/verify-chain?caseId=`; no write API by construction.
- `services/api-gateway` — (M5, port 8080) Spring Cloud Gateway routing all 7 services plus the archive `/admin/**` passthrough; `ApiGatewayApplicationTest` boots the context and requires the 7 core routes as a subset (catches YAML merge artifacts — SnakeYAML 2 rejects duplicate keys — while allowing teammates to add routes).
- `coverage-report` — aggregate JaCoCo report module

## S3 / LocalStack
- Buckets `discoveryhub-attachments` + `discoveryhub-exports` are auto-created by
  `infra/localstack/init/ready.d/01-create-buckets.sh`, mounted into the localstack
  container at `/etc/localstack/init/ready.d` (keep the script executable).
