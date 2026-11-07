CREATE TYPE matter_type  AS ENUM ('INVESTIGATION','LITIGATION','REGULATORY_INQUIRY');
CREATE TYPE case_status   AS ENUM ('DRAFT','ACTIVE','UNDER_REVIEW','CLOSED');
CREATE TYPE hold_status   AS ENUM ('PENDING','ACTIVE','RELEASING','RELEASED','FAILED');

CREATE TABLE cases (
  id            uuid PRIMARY KEY,
  name          text NOT NULL,
  description   text,
  matter_type   matter_type NOT NULL,
  owner         text NOT NULL,
  status        case_status NOT NULL DEFAULT 'DRAFT',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  version       bigint NOT NULL DEFAULT 0          -- @Version, optimistic locking
);
CREATE TABLE case_custodians (
  case_id uuid REFERENCES cases(id) ON DELETE CASCADE,
  custodian_id text NOT NULL, custodian_name text, added_at timestamptz DEFAULT now(),
  PRIMARY KEY (case_id, custodian_id)
);
CREATE TABLE evidence_items (
  id uuid PRIMARY KEY,
  case_id uuid NOT NULL REFERENCES cases(id),
  message_id text NOT NULL,
  added_at timestamptz DEFAULT now(), added_by text, source text,  -- MANUAL | SEARCH:<savedSearchId>
  UNIQUE (case_id, message_id)                                     -- idempotent bulk-add
);
CREATE TABLE holds (
  id uuid PRIMARY KEY,
  case_id uuid NOT NULL REFERENCES cases(id),
  status hold_status NOT NULL DEFAULT 'PENDING',
  scope_custodians text[] NOT NULL DEFAULT '{}',
  scope_from timestamptz, scope_to timestamptz, scope_terms text,
  held_count bigint DEFAULT 0, applied_count bigint DEFAULT 0,
  placed_by text, placed_at timestamptz DEFAULT now(), released_at timestamptz
);
CREATE TABLE saved_searches (
  id uuid PRIMARY KEY, case_id uuid REFERENCES cases(id),
  name text NOT NULL, spec jsonb NOT NULL, created_at timestamptz DEFAULT now()
);
CREATE TABLE retention_policies (
  comm_type text PRIMARY KEY, duration interval NOT NULL, updated_at timestamptz DEFAULT now()
);
CREATE TABLE outbox (               -- transactional outbox → Kafka (case.closed.v1)
  id bigserial PRIMARY KEY, topic text, key text, payload jsonb,
  created_at timestamptz DEFAULT now(), published_at timestamptz
);
CREATE INDEX ON evidence_items (case_id);
CREATE INDEX ON holds (case_id, status);
