# DiscoveryHub — Architecture Decision Record

Decisions settled in the design docs (`Architecture file/DiscoveryHub_Day1_Playbook.md`
and `docs/architecture.md`) and recorded here as the team's baseline. New decisions are
appended below; existing rows are not silently rewritten — to reverse one, add a new
dated row and mark the old one **superseded by <date>**.

Dates are the recording date in this log. The decisions themselves were settled in the
Day 1 contract meeting and `docs/architecture.md` §12 ("Why X and not Y?") and §13
("Version & EOL Posture").

| Date | Decision | Alternative rejected | Why |
|---|---|---|---|
| 2026-09-07 | JDK 21 (LTS) — `eclipse-temurin:21` | 8 / 11 / 17 | 21 brings virtual threads (used by the export worker), generational ZGC and better G1 pause behaviour; ≤17 is at/near free-support EOL with unpatched CVEs and no Loom. |
| 2026-09-07 | Spring Boot 3.5.x (Spring Framework 6, Jakarta namespace); repo pins 3.5.3 | Spring Boot 2.x | 2.x is EOL for OSS support — no free security patches — and cannot use Jakarta APIs, AOT/native images, the Micrometer Observation API, or virtual-thread integration. Starting on 2.x means starting unpatched and `javax`-locked. |
| 2026-09-07 | Kafka 3.9.x in KRaft mode as the messaging backbone | RabbitMQ / SQS | We need a replayable log, not just a queue: rebuilding the ES index and reprocessing after an outage are both offset rewinds, and compaction gives a durable corpus projection. RabbitMQ discards after ack; SQS has no replay and ties the laptop demo to AWS. |
| 2026-09-07 | MongoDB for the audit trail (`audit_events`) | Relational/Postgres audit table | Audit is append-only with variable event payloads; Mongo grants an insert+find-only DB role (no update/remove) so append-only is structural, not policy, and the per-case hash chain fits a document store. Mongo is already on the data path for the corpus. |
| 2026-09-07 | Ports-and-adapters package layout (`domain` / `application` / `infrastructure` / `api`) | Conventional layered layout (`controller` / `service` / `repository` / `model` / `dto`) | Keeps the domain free of framework I/O so business rules are unit-testable without a Spring context, and realizes the Dependency Inversion the design already relies on (e.g. the `StorageService` interface in `common`). The Day 1 Playbook's original layered layout was superseded by this. |
| 2026-09-07 | Enum-based state machine in the domain (`CaseStateMachine` over `CaseStatus` / `HoldStatus`) | Spring StateMachine | Case/hold transitions are few and flat (4 case statuses, 5 hold statuses); a hand-rolled enum transition table is pure Java, fully unit-testable without a Spring context, and keeps the domain framework-free. Spring StateMachine's hierarchical/parallel machinery is overkill and drags a framework dependency into the domain. |
| 2026-09-07 | Fail-closed disposition | Fail-open (delete on unknown / error hold state) | Legal hold is a legal obligation; an erroneous deletion is irreversible and creates legal liability. A false positive (retaining a deletable message) is recoverable; a false negative (deleting a held message) is not. `RetentionGuard` blocks deletion and emits `DELETE_BLOCKED_ON_HOLD` when hold state cannot be positively confirmed. |
| 2026-09-07 | Per-case hash chain for audit integrity | Single global chain / plain append-only (no chain) | Localizes tamper detection to a case and bounds `GET /audit/verify-chain?caseId=` to that case's events; a global chain would couple every event to every other and make per-case verification O(total events). Each entry: `hash = SHA-256(prevHash + canonical(entry))`. |
