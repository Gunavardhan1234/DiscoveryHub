-- Day 3 M4 (FR-5): disposition_runs gains a lifecycle status and the per-type
-- deletion breakdown the Disposition screen renders (deletedByType).
ALTER TABLE disposition_runs
  ADD COLUMN status text NOT NULL DEFAULT 'RUNNING',
  ADD COLUMN deleted_by_type jsonb;

CREATE INDEX ON disposition_runs (started_at DESC);
