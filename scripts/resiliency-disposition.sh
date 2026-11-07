#!/usr/bin/env bash
# DiscoveryHub disposition resiliency proof (execution-plan Day 4, FR-5/NFR-2).
#
# The unit tests already prove DispositionService marks a run FAILED when
# archive-service is unreachable and that the next tick simply tries again
# (DispositionServiceTest#executorFailureRecordsFailedRunAndStillAudits) —
# but that guarantee has never been rehearsed against a live stack the way
# the "docker compose stop archive-service" ingestion resiliency scenario
# has. This script is that rehearsal, scripted so it is repeatable instead
# of manual:
#
#   1. Stop archive-service.
#   2. Trigger a manual disposition run through the gateway; it must
#      complete (not hang) with status FAILED — proving hold-service
#      degrades gracefully instead of cascading when its downstream
#      dependency is down.
#   3. Restart archive-service and wait for it to be healthy again.
#   4. Trigger another disposition run; it must complete with status
#      COMPLETED or COMPLETED_WITH_ERRORS — proving recovery on the very
#      next attempt, no manual intervention, no stuck state.
#
# Run from the repo root on a stack already up (`docker compose up -d`).
# Exits non-zero on failure.
#
#   BASE_URL        api-gateway base url (default http://localhost:8080)
#   ARCHIVE_SERVICE compose service name to stop/start (default archive-service)
#   WAIT_SECS       max time to wait for a run to leave RUNNING (default 30)
#   HEALTH_WAIT_SECS max time to wait for archive-service to report healthy
#                    again after restart (default 60)
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ARCHIVE_SERVICE="${ARCHIVE_SERVICE:-archive-service}"
WAIT_SECS="${WAIT_SECS:-30}"
HEALTH_WAIT_SECS="${HEALTH_WAIT_SECS:-60}"

command -v jq >/dev/null || { echo "FAIL: jq is required" >&2; exit 2; }
command -v docker >/dev/null || { echo "FAIL: docker is required" >&2; exit 2; }

compose() {
  if docker compose version >/dev/null 2>&1; then docker compose "$@"
  else docker-compose "$@"; fi
}

# Triggers a manual run and polls /runs/latest until it leaves RUNNING.
# Prints the final status on stdout.
run_disposition_and_await() {
  local run_id status
  run_id=$(curl -fsS -X POST "$BASE_URL/api/holds/disposition/run" | jq -r '.id')
  echo "[resiliency] disposition run $run_id started" >&2

  local deadline=$(( $(date +%s) + WAIT_SECS ))
  while :; do
    status=$(curl -fsS "$BASE_URL/api/holds/disposition/runs/latest" | jq -r '.status // empty')
    if [[ "$status" != "RUNNING" && -n "$status" ]]; then
      echo "$status"
      return 0
    fi
    if [[ $(date +%s) -ge $deadline ]]; then
      echo "FAIL: disposition run $run_id still RUNNING after ${WAIT_SECS}s" >&2
      exit 1
    fi
    sleep 1
  done
}

echo "[resiliency] stopping $ARCHIVE_SERVICE to simulate a downstream outage..."
compose stop "$ARCHIVE_SERVICE"

echo "[resiliency] triggering disposition while archive-service is down..."
STATUS_DOWN=$(run_disposition_and_await)
echo "[resiliency] run finished with status=$STATUS_DOWN while archive-service was down"
[[ "$STATUS_DOWN" == "FAILED" ]] || {
  echo "FAIL: expected FAILED while archive-service is down, got '$STATUS_DOWN'" >&2
  echo "      (hold-service should record the failure, never hang or crash)" >&2
  exit 1
}
echo "[resiliency] PASS: hold-service degraded gracefully — FAILED recorded, no hang, no crash"

echo "[resiliency] restarting $ARCHIVE_SERVICE..."
compose start "$ARCHIVE_SERVICE"

echo "[resiliency] waiting up to ${HEALTH_WAIT_SECS}s for $ARCHIVE_SERVICE to report healthy..."
DEADLINE=$(( $(date +%s) + HEALTH_WAIT_SECS ))
while :; do
  HEALTH=$(docker inspect --format '{{.State.Health.Status}}' \
    "$(compose ps -q "$ARCHIVE_SERVICE")" 2>/dev/null || echo "unknown")
  [[ "$HEALTH" == "healthy" ]] && break
  if [[ $(date +%s) -ge $DEADLINE ]]; then
    echo "FAIL: $ARCHIVE_SERVICE not healthy after ${HEALTH_WAIT_SECS}s (last status: $HEALTH)" >&2
    exit 1
  fi
  sleep 2
done
echo "[resiliency] $ARCHIVE_SERVICE is healthy again"

echo "[resiliency] triggering disposition again to prove recovery..."
STATUS_UP=$(run_disposition_and_await)
echo "[resiliency] run finished with status=$STATUS_UP after archive-service recovered"
case "$STATUS_UP" in
  COMPLETED|COMPLETED_WITH_ERRORS)
    echo "[resiliency] PASS: disposition recovered on the very next attempt, no manual intervention"
    ;;
  *)
    echo "FAIL: expected COMPLETED (or COMPLETED_WITH_ERRORS) after recovery, got '$STATUS_UP'" >&2
    exit 1
    ;;
esac

echo "[resiliency] PASS: disposition failure-recovery proven end to end (FR-5/NFR-2)"
