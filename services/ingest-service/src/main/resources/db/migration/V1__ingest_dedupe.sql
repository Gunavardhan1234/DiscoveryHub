-- ingest-service idempotency guard (architecture §2.1, FR-1.6).
-- The PRIMARY KEY on message_key backs the INSERT ... ON CONFLICT DO NOTHING
-- that makes duplicate ingests a no-op. The second guard is archive-service's
-- Mongo unique index on messageKey.
CREATE TABLE ingest_dedupe (
    message_key         TEXT PRIMARY KEY,
    message_id          UUID        NOT NULL,
    external_message_id TEXT        NOT NULL,
    ingested_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_ingest_dedupe_external_id ON ingest_dedupe (external_message_id);
