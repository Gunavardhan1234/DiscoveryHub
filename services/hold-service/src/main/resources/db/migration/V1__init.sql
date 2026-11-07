-- hold-service schema (moved from case-service per the 7-service split in
-- execution-plan.md). Owns: holds, retention_policies, disposition_runs, outbox.
CREATE TYPE hold_status AS ENUM ('PENDING','ACTIVE','RELEASING','RELEASED','FAILED');

CREATE TABLE holds (
  id uuid PRIMARY KEY,
  case_id uuid NOT NULL,
  status hold_status NOT NULL DEFAULT 'PENDING',
  scope_custodians text[] NOT NULL DEFAULT '{}',
  scope_from timestamptz, scope_to timestamptz, scope_terms text,
  held_count bigint DEFAULT 0, applied_count bigint DEFAULT 0,
  placed_by text, placed_at timestamptz DEFAULT now(), released_at timestamptz
);

CREATE TABLE retention_policies (
  comm_type text PRIMARY KEY, duration text NOT NULL, updated_at timestamptz DEFAULT now()
);

CREATE TABLE disposition_runs (
  id uuid PRIMARY KEY,
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  scanned bigint DEFAULT 0,
  deleted bigint DEFAULT 0,
  skipped_on_hold bigint DEFAULT 0,
  errors bigint DEFAULT 0
);

CREATE TABLE outbox (
  id bigserial PRIMARY KEY, topic text, key text, payload jsonb,
  created_at timestamptz DEFAULT now(), published_at timestamptz
);

CREATE INDEX ON holds (case_id, status);
