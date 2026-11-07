# audit-service — Code Conventions

These conventions govern all code in `services/audit-service`. They match the
project-wide posture in `docs/architecture.md` and the decisions in `DECISIONS.md`.
Any exception requires a new dated row in `DECISIONS.md`.

## Package layout

Four packages under `com.smarsh.discoveryhub.audit`:

- `domain` — the audit event model, the hash-chain type, and ports (interfaces). Pure Java.
- `application` — use cases (append event, verify chain) that orchestrate domain logic.
- `infrastructure` — adapters: the Mongo repository, Kafka producer, the insert-only DB role.
- `api` — `@RestController`s, request/response records, the `@RestControllerAdvice`.
- (`config` holds Spring `@Configuration` wiring only; it is not a layer.)

Dependency rule: **`domain` imports nothing from `application`, `infrastructure`, or
`api`** — no Spring, no Mongo, no Kafka, no servlet types. `application` depends on
`domain`; `infrastructure` and `api` implement/depend on `domain` ports. The dependency
direction is always inward toward `domain`.

## Dependency injection

Constructor injection only. No `@Autowired` on fields, no setter injection. Declare
collaborators as `private final` fields and let Spring's single-constructor inference
wire them. One constructor per class; no `@Autowired` on the constructor either.

## DTOs and value objects

Use Java `record` for all DTOs, command/query objects, and value objects. Mongo documents
may remain classes where the framework requires identity/mutable state — they are not
DTOs.

## No Lombok

Do not add Lombok. No `@Data`, `@Getter`, `@Builder`, etc. Use records and explicit
constructors. The build does not depend on Lombok; keep it that way.

## Error handling

Domain code throws domain exceptions. All domain exceptions are mapped to RFC 7807
`ProblemDetail` responses in **one** `@RestControllerAdvice` in the `api` package. Do
not throw `ResponseStatusException` or Spring/HTTP exceptions from `application` or
`domain`. Keep the mapping in a single place.

## Migrations

Flyway migrations only; never `ddl-auto: update` (use `validate` at most) for any
relational schema the service owns. audit-service is Mongo-backed, so Hibernate
`ddl-auto` is not applicable here, but the rule is project-wide (case-service runs
`ddl-auto: validate` + Flyway) and governs any relational schema this service may touch.

## Kafka consumers

Kafka consumers are thin adapters. A `@KafkaListener` deserializes the event envelope,
hands it to a use case in `application`, and returns. It contains no business logic, no
direct repository writes that bypass the use case, and no branching on domain rules.
