-- holds and retention_policies were part of an earlier design where
-- case-service owned legal holds directly. hold-service is now its own
-- independently deployable service with its own Postgres database
-- (discoveryhub_hold) and is the real, active owner of both concepts
-- (NFR-1: each service owns its own data). These tables in case-service's
-- schema were never read or written by any code path (no repository ever
-- existed for them) and are dropped here rather than left as dead schema.
DROP TABLE IF EXISTS holds;
DROP TABLE IF EXISTS retention_policies;
DROP TYPE IF EXISTS hold_status;
