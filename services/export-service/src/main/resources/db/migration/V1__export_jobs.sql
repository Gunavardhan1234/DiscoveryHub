-- export-service schema (architecture §2.5, events.md §5).
-- The export_jobs table tracks the async packaging lifecycle:
-- QUEUED → RUNNING → COMPLETED/FAILED. jobId is the idempotency key
-- (events.md §5: "a redelivered message never yields two packages").
CREATE TABLE export_jobs (
    id              uuid PRIMARY KEY,
    case_id         text NOT NULL,
    hold_id         text,
    status          text NOT NULL DEFAULT 'QUEUED',
    progress_pct    int  NOT NULL DEFAULT 0,
    package_s3_key  text,
    error           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_export_jobs_case_id ON export_jobs (case_id);
CREATE INDEX ix_export_jobs_status ON export_jobs (status);
