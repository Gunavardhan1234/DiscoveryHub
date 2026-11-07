#!/usr/bin/env bash
# DiscoveryHub ingestion resiliency proof (Day1.md §4, architecture §5 + step 10, NFR-2).
#
# Day1.md calls this "the highest-value 3 minutes of the demo because it's the
# thing most teams fake", and sketches it as a handful of manual commands:
#
#     docker compose stop search-service
#     POST /messages/ingest
#     # show consumer lag > 0 on the search consumer group
#     docker compose start search-service
#     # lag drains to 0
#     GET /search?q=Project+Phoenix   # the message is there
#
# This script is that scenario, scripted so it is repeatable and actually
# *asserts* the claim instead of eyeballing it.
#
# Why search-service is the service to stop: it owns the `search-indexer`
# consumer group on `comm.stored.v1` and is a pure read projection. Taking it
# down stalls the Elasticsearch index while leaving the durable ingest path
# (ingest-service -> comm.ingest.v1 -> archive-service -> Mongo) fully
# operational. That is exactly the claim under test — ES is a rebuildable
# projection, never the source of truth, and Kafka retains what has not yet
# been consumed.
#
# Flow (mirrors scripts/resiliency-disposition.sh):
#   1. Baseline: this run's token matches nothing in the index yet.
#   2. Ingest the first half with search-service UP; wait until the index shows
#      them — a control proving the pipeline is healthy *before* the outage, so
#      the staleness in step 5b can only be caused by the outage itself.
#   3. Stop search-service mid-ingestion.
#   4. Ingest the second half while it is down; every message must still be
#      accepted — the ingest path must not care that a projection is offline.
#   5. Prove nothing is lost *while still down*:
#        a. archive-service (Mongo, the source of truth) returns ALL N messages;
#        b. the index is still stale at N/2 — the outage is real, not a no-op;
#        c. the `search-indexer` group reports LAG > 0 — the unconsumed events
#           are sitting durably in Kafka, which is what "not lost" means here.
#   6. Restart search-service, wait for healthy, wait for LAG to drain to 0.
#   7. Assert the index now holds exactly N for this run's token, and that the
#      user-facing GET /api/search agrees — sent count == processed count, so
#      nothing was dropped during the outage.
#
# Index counts are read straight from Elasticsearch rather than through
# search-service, because step 5b has to measure the projection precisely while
# the service in front of it is stopped (its API is unavailable then, by
# design). Step 7 additionally asserts the user-facing search API, which is the
# form Day1.md demos.
#
# Run from the repo root on a stack already up (`docker compose up -d`).
# Exits non-zero on failure. search-service is restarted on any exit path, so a
# failed run never leaves the stack degraded for the next one.
#
# Prerequisite: the scheduled disposition sweep must not delete the test
# messages mid-run. The compose default (DISPOSITION_SCHEDULED_ENABLED=false)
# satisfies this; with the sweep enabled against minute-level retention
# policies the final count would legitimately fail.
#
#   BASE_URL         api-gateway base url (default http://localhost:8080)
#   ES_URL           elasticsearch base url (default http://localhost:9200)
#   SEARCH_INDEX     ES index holding the projection (default messages)
#   SEARCH_SERVICE   compose service to stop/start (default search-service)
#   KAFKA_SERVICE    compose service running the broker (default kafka)
#   SEARCH_GROUP     consumer group to read lag from (default search-indexer)
#   TOTAL_MESSAGES   messages to ingest, split in half around the outage (default 40)
#   TOKEN            run token; unique per run so re-runs never collide
#   INDEX_WAIT_SECS  max wait for the index to catch up (default 60)
#   LAG_WAIT_SECS    max wait for lag to drain after restart (default 90)
#   HEALTH_WAIT_SECS max wait for search-service to report healthy (default 90)
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ES_URL="${ES_URL:-http://localhost:9200}"
SEARCH_INDEX="${SEARCH_INDEX:-messages}"
SEARCH_SERVICE="${SEARCH_SERVICE:-search-service}"
KAFKA_SERVICE="${KAFKA_SERVICE:-kafka}"
SEARCH_GROUP="${SEARCH_GROUP:-search-indexer}"
TOTAL_MESSAGES="${TOTAL_MESSAGES:-40}"
INDEX_WAIT_SECS="${INDEX_WAIT_SECS:-60}"
LAG_WAIT_SECS="${LAG_WAIT_SECS:-90}"
HEALTH_WAIT_SECS="${HEALTH_WAIT_SECS:-90}"
STORED_TOPIC="comm.stored.v1"

command -v jq >/dev/null || { echo "FAIL: jq is required" >&2; exit 2; }
command -v docker >/dev/null || { echo "FAIL: docker is required" >&2; exit 2; }

(( TOTAL_MESSAGES >= 2 && TOTAL_MESSAGES % 2 == 0 )) || {
  echo "FAIL: TOTAL_MESSAGES must be an even number >= 2 (got $TOTAL_MESSAGES)" >&2
  exit 2
}
HALF=$(( TOTAL_MESSAGES / 2 ))

# Unique per run, so a re-run is a fresh, independent experiment that can never
# collide with a previous one via the ingest dedupe table (same convention as
# smoke.sh / demo-disposition.sh).
TOKEN="${TOKEN:-ingres$(date +%s)$RANDOM}"
SENT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

compose() {
  if docker compose version >/dev/null 2>&1; then docker compose "$@"
  else docker-compose "$@"; fi
}

IDS_FILE="$(mktemp)"
STOPPED=0

# Leave the stack usable no matter how we exit: `compose start` on an already
# running service is a harmless no-op.
cleanup() {
  local code=$?
  rm -f "$IDS_FILE"
  if (( STOPPED == 1 )); then
    echo "[resiliency] cleanup: restarting $SEARCH_SERVICE so the stack is left usable" >&2
    compose start "$SEARCH_SERVICE" >/dev/null 2>&1 || true
  fi
  exit $code
}
trap cleanup EXIT

# Total lag of the search consumer group over comm.stored.v1. Columns are
# GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG; a partition with no
# committed offset prints "-" and contributes nothing.
search_lag() {
  local out
  out=$(compose exec -T "$KAFKA_SERVICE" /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 --describe --group "$SEARCH_GROUP" 2>/dev/null || true)
  printf '%s\n' "$out" \
    | awk -v topic="$STORED_TOPIC" '$2 == topic && $6 ~ /^[0-9]+$/ { sum += $6 } END { print sum + 0 }'
}

# How many of this run's messages the Elasticsearch projection holds. Read
# directly from ES so it stays measurable while search-service is stopped.
es_count() {
  local out
  out=$(curl -fsS "$ES_URL/$SEARCH_INDEX/_count" -H 'Content-Type: application/json' \
    -d "{\"query\":{\"query_string\":{\"query\":\"$TOKEN\"}}}" 2>/dev/null || true)
  printf '%s' "${out:-}" | jq -r '.count // 0' 2>/dev/null || echo 0
}

# The user-facing search API (Day1.md's final demo step).
api_search_total() {
  local out
  out=$(curl -fsS "$BASE_URL/api/search?q=$TOKEN&size=1" 2>/dev/null || true)
  printf '%s' "${out:-}" | jq -r '.total // 0' 2>/dev/null || echo 0
}

# How many of this run's messages archive-service (Mongo) holds — the durable
# source of truth, read by id so it cannot be confused with a stale projection.
archive_count() {
  local ids out
  ids=$(paste -sd, "$IDS_FILE")
  [[ -n "$ids" ]] || { echo 0; return; }
  out=$(curl -fsS "$BASE_URL/api/messages?ids=$ids" 2>/dev/null || true)
  printf '%s' "${out:-}" | jq -r '.messages | length' 2>/dev/null || echo 0
}

# Ingests `count` messages numbered from `start`, all tagged with this run's
# token so they are individually countable end to end.
ingest_phase() {
  local start="$1" count="$2" label="$3" resp accepted
  resp=$(jq -cn \
      --arg token "$TOKEN" --arg sentAt "$SENT_AT" \
      --argjson start "$start" --argjson count "$count" '
      {messages: [range($start; $start + $count) | {
        externalMessageId: "\($token)-\(.)",
        type: "EMAIL",
        subject: "Ingest resiliency \($token) #\(.)",
        body: "Ingest resiliency proof \($token) message \(.) - must survive a search-service outage.",
        threadId: "thread-\($token)",
        custodian: "resiliency-\($token)",
        participants: [
          {name: "Resiliency Sender", address: "sender@resiliency.local", role: "FROM"},
          {name: "Resiliency Recipient", address: "recipient@resiliency.local", role: "TO"}
        ],
        sentAt: $sentAt
      }]}' \
    | curl -fsS -X POST "$BASE_URL/api/messages/ingest:batch" \
        -H 'Content-Type: application/json' --data-binary @-) || {
    echo "FAIL: batch ingest rejected during '$label'" >&2
    echo "      (the ingest path must keep accepting messages even while a projection is down)" >&2
    exit 1
  }

  accepted=$(printf '%s' "$resp" | jq '[.[] | select(.status == "ACCEPTED")] | length')
  [[ "$accepted" == "$count" ]] || {
    echo "FAIL: expected $count ACCEPTED during '$label', got $accepted" >&2
    printf '%s' "$resp" | jq -c '[.[] | .status] | unique' >&2
    exit 1
  }
  printf '%s' "$resp" | jq -r '.[].messageId' >> "$IDS_FILE"
  echo "[resiliency] $label: $accepted/$count messages accepted (HTTP 202)"
}

# Polls the ES projection until it reaches `want`, or fails after a deadline.
await_indexed() {
  local want="$1" deadline_secs="$2" what="$3" total
  local deadline=$(( $(date +%s) + deadline_secs ))
  while :; do
    total=$(es_count)
    if [[ "$total" == "$want" ]]; then
      echo "[resiliency] $what: index holds $total/$want"
      return 0
    fi
    if [[ $(date +%s) -ge $deadline ]]; then
      echo "FAIL: $what — index held $total, expected $want after ${deadline_secs}s" >&2
      exit 1
    fi
    sleep 1
  done
}

echo "[resiliency] ingestion resiliency proof — token=$TOKEN total=$TOTAL_MESSAGES ($HALF before the outage, $HALF during)"

# --- 1. baseline -------------------------------------------------------------
BASELINE=$(es_count)
[[ "$BASELINE" == "0" ]] || {
  echo "FAIL: expected 0 pre-existing matches for token $TOKEN, got $BASELINE" >&2
  exit 1
}
echo "[resiliency] baseline: 0 matches for this run's token"

# --- 2. ingest with search-service UP (control) ------------------------------
ingest_phase 0 "$HALF" "phase 1 (search-service UP)"
await_indexed "$HALF" "$INDEX_WAIT_SECS" "phase 1 indexed"
echo "[resiliency] control established: the pipeline is healthy before the outage"

# --- 3. stop the downstream projection ---------------------------------------
echo "[resiliency] stopping $SEARCH_SERVICE mid-ingestion to simulate a downstream outage..."
compose stop "$SEARCH_SERVICE"
STOPPED=1

# --- 4. keep ingesting while it is down --------------------------------------
ingest_phase "$HALF" "$HALF" "phase 2 (search-service DOWN)"
echo "[resiliency] PASS: ingestion kept succeeding while $SEARCH_SERVICE was down (no cascade)"

# --- 5. prove nothing is lost, while still down ------------------------------
ARCHIVED=$(archive_count)
[[ "$ARCHIVED" == "$TOTAL_MESSAGES" ]] || {
  echo "FAIL: archive-service holds $ARCHIVED/$TOTAL_MESSAGES messages while $SEARCH_SERVICE is down" >&2
  echo "      (the durable source of truth must be complete regardless of the projection)" >&2
  exit 1
}
echo "[resiliency] PASS: archive-service (Mongo, source of truth) holds all $ARCHIVED/$TOTAL_MESSAGES messages"

STALE_TOTAL=$(es_count)
[[ "$STALE_TOTAL" == "$HALF" ]] || {
  echo "FAIL: expected the index to be stale at $HALF while $SEARCH_SERVICE is down, got $STALE_TOTAL" >&2
  echo "      (if this already reads $TOTAL_MESSAGES the outage was not real and the test proves nothing)" >&2
  exit 1
}
echo "[resiliency] confirmed the outage is real: index still stale at $STALE_TOTAL/$TOTAL_MESSAGES"

LAG_DOWN=$(search_lag)
[[ "$LAG_DOWN" -gt 0 ]] || {
  echo "FAIL: expected LAG > 0 on group $SEARCH_GROUP while $SEARCH_SERVICE is down, got $LAG_DOWN" >&2
  echo "      (without pending lag there is no evidence Kafka retained the events)" >&2
  exit 1
}
echo "[resiliency] PASS: $SEARCH_GROUP lag is $LAG_DOWN on $STORED_TOPIC — unconsumed events retained in Kafka, not lost"

# --- 6. recover --------------------------------------------------------------
echo "[resiliency] restarting $SEARCH_SERVICE..."
compose start "$SEARCH_SERVICE"

echo "[resiliency] waiting up to ${HEALTH_WAIT_SECS}s for $SEARCH_SERVICE to report healthy..."
DEADLINE=$(( $(date +%s) + HEALTH_WAIT_SECS ))
while :; do
  HEALTH=$(docker inspect --format '{{.State.Health.Status}}' \
    "$(compose ps -q "$SEARCH_SERVICE")" 2>/dev/null || echo "unknown")
  [[ "$HEALTH" == "healthy" ]] && break
  if [[ $(date +%s) -ge $DEADLINE ]]; then
    echo "FAIL: $SEARCH_SERVICE not healthy after ${HEALTH_WAIT_SECS}s (last status: $HEALTH)" >&2
    exit 1
  fi
  sleep 2
done
STOPPED=0
echo "[resiliency] $SEARCH_SERVICE is healthy again"

echo "[resiliency] waiting up to ${LAG_WAIT_SECS}s for $SEARCH_GROUP lag to drain to 0..."
DEADLINE=$(( $(date +%s) + LAG_WAIT_SECS ))
while :; do
  LAG_NOW=$(search_lag)
  if [[ "$LAG_NOW" == "0" ]]; then
    echo "[resiliency] PASS: lag drained $LAG_DOWN -> 0, the consumer resumed from its committed offsets"
    break
  fi
  if [[ $(date +%s) -ge $DEADLINE ]]; then
    echo "FAIL: $SEARCH_GROUP lag still $LAG_NOW after ${LAG_WAIT_SECS}s" >&2
    exit 1
  fi
  sleep 2
done

# --- 7. sent == processed ----------------------------------------------------
await_indexed "$TOTAL_MESSAGES" "$INDEX_WAIT_SECS" "post-recovery"

API_TOTAL=$(api_search_total)
[[ "$API_TOTAL" == "$TOTAL_MESSAGES" ]] || {
  echo "FAIL: user-facing GET /api/search returned $API_TOTAL, expected $TOTAL_MESSAGES" >&2
  exit 1
}
echo "[resiliency] PASS: user-facing GET /api/search returns all $API_TOTAL messages"

echo "[resiliency] PASS: ingestion resiliency proven end to end (NFR-2) —"
echo "             sent=$TOTAL_MESSAGES archived=$ARCHIVED indexed=$TOTAL_MESSAGES, of which $HALF were ingested"
echo "             while $SEARCH_SERVICE was down (peak lag $LAG_DOWN). Nothing was lost; token=$TOKEN"
