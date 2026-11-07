
# export-service — Code Conventions

These conventions govern all code in `services/export-service`. They match the
project-wide posture in `docs/architecture.md` and the decisions in `DECISIONS.md`.
Any exception requires a new dated row in `DECISIONS.md`.

## Package layout

Four packages under `com.smarsh.discoveryhub.export`:

- `domain` — entities, value objects, and ports (interfaces). Pure Java.
- `application` — use cases that orchestrate domain logic.
- `infrastructure` — adapters: JPA repositories, S3, Kafka producers, Flyway.
- `api` — `@RestController`s, request/response records, the `@RestControllerAdvice`.
- (`config` holds Spring `@Configuration` wiring only; it is not a layer.)

Dependency rule: **`domain` imports nothing from `application`, `infrastructure`, or
`api`** — no Spring, no JPA, no Mongo, no Kafka, no servlet types. `application` depends
on `domain`; `infrastructure` and `api` implement/depend on `domain` ports. The
dependency direction is always inward toward `domain`.

## Dependency injection

Constructor injection only. No `@Autowired` on fields, no setter injection. Declare
collaborators as `private final` fields and let Spring's single-constructor inference
wire them. One constructor per class; no `@Autowired` on the constructor either.

## DTOs and value objects

Use Java `record` for all DTOs, command/query objects, and value objects. JPA entities
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

Flyway migrations only. Never set `spring.jpa.hibernate.ddl-auto=update`; use `validate`
at most so Hibernate refuses to start on schema drift. Schema changes come from versioned
migration scripts under `src/main/resources/db/migration`, never from code.

## Kafka consumers

Kafka consumers are thin adapters. A `@KafkaListener` deserializes the event envelope,
hands it to a use case in `application`, and returns. It contains no business logic, no
direct repository writes that bypass the use case, and no branching on domain rules.
