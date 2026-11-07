#!/bin/bash
# Creates per-service Postgres users/databases with passwords supplied via
# environment variables (never hardcoded), so this script is safe to keep
# in version control. Runs once, on first container startup, as part of
# docker-entrypoint-initdb.d.
set -euo pipefail

: "${CASE_DB_PASSWORD:?CASE_DB_PASSWORD must be set}"
: "${EXPORT_DB_PASSWORD:?EXPORT_DB_PASSWORD must be set}"
: "${INGEST_DB_PASSWORD:?INGEST_DB_PASSWORD must be set}"
: "${HOLD_DB_PASSWORD:?HOLD_DB_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  CREATE USER case_svc WITH PASSWORD '$CASE_DB_PASSWORD';
  CREATE USER export_svc WITH PASSWORD '$EXPORT_DB_PASSWORD';
  CREATE USER ingest_svc WITH PASSWORD '$INGEST_DB_PASSWORD';
  CREATE USER hold_svc WITH PASSWORD '$HOLD_DB_PASSWORD';

  CREATE DATABASE discoveryhub_case OWNER case_svc;
  CREATE DATABASE discoveryhub_export OWNER export_svc;
  CREATE DATABASE discoveryhub_ingest OWNER ingest_svc;
  CREATE DATABASE discoveryhub_hold OWNER hold_svc;

  REVOKE CONNECT ON DATABASE discoveryhub_case FROM PUBLIC;
  REVOKE CONNECT ON DATABASE discoveryhub_export FROM PUBLIC;
  REVOKE CONNECT ON DATABASE discoveryhub_ingest FROM PUBLIC;
  REVOKE CONNECT ON DATABASE discoveryhub_hold FROM PUBLIC;
  GRANT CONNECT ON DATABASE discoveryhub_case TO case_svc;
  GRANT CONNECT ON DATABASE discoveryhub_export TO export_svc;
  GRANT CONNECT ON DATABASE discoveryhub_ingest TO ingest_svc;
  GRANT CONNECT ON DATABASE discoveryhub_hold TO hold_svc;
EOSQL
