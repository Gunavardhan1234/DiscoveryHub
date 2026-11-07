#!/usr/bin/env bash
# DiscoveryHub end-to-end checkpoint smoke script (fullplan.md Day-1 M5).
#
# Ingests one message, then polls search until the message is indexed in
# Elasticsearch. The team runs this at every 13:00 / 17:30 checkpoint on a
# clean `docker compose up` stack. Exits non-zero on failure.
#
#   INGEST_URL  ingest-service base url (default http://localhost:8081)
#   SEARCH_URL  search-service base url (default http://localhost:8083)
#   WAIT_SECS   max time to wait for the index to catch up (default 30)
set -euo pipefail

INGEST_URL="${INGEST_URL:-http://localhost:8081}"
SEARCH_URL="${SEARCH_URL:-http://localhost:8083}"
WAIT_SECS="${WAIT_SECS:-30}"

command -v jq >/dev/null || { echo "FAIL: jq is required" >&2; exit 2; }

# A unique token so this run never collides with previously seeded messages.
TOKEN="smoke$(date +%s)$RANDOM"
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "[smoke] ingesting one message (token=$TOKEN)..."
INGEST_BODY=$(cat <<EOF
{
  "externalMessageId": "smoke-$TOKEN",
  "type": "EMAIL",
  "subject": "Smoke check $TOKEN",
  "body": "DiscoveryHub smoke-test payload $TOKEN — please index me.",
  "threadId": "thread-smoke-$TOKEN",
  "custodian": "smoke-custodian",
  "participants": [
    {"name": "Smoke Sender", "address": "sender@smoke.local", "role": "FROM"},
    {"name": "Smoke Recipient", "address": "recipient@smoke.local", "role": "TO"}
  ],
  "sentAt": "$NOW"
}
EOF
)

RESP=$(curl -fsS -X POST "$INGEST_URL/api/messages/ingest" \
  -H 'Content-Type: application/json' -d "$INGEST_BODY") || {
    echo "FAIL: ingest request failed" >&2
    exit 1
}
MESSAGE_ID=$(echo "$RESP" | jq -r '.messageId // empty')
STATUS=$(echo "$RESP" | jq -r '.status // empty')
echo "[smoke] ingest accepted: messageId=$MESSAGE_ID status=$STATUS"
[[ "$STATUS" == "ACCEPTED" ]] || {
  echo "FAIL: ingest status was '$STATUS', expected ACCEPTED (duplicate or rejected)" >&2
  exit 1
}

echo "[smoke] polling search until the message appears (up to ${WAIT_SECS}s)..."
DEADLINE=$(( $(date +%s) + WAIT_SECS ))
while :; do
  HITS_JSON=$(curl -sS "$SEARCH_URL/api/search?q=$TOKEN&size=10" 2>/dev/null || echo '{"total":0,"hits":[]}')
  TOTAL=$(echo "$HITS_JSON" | jq -r '.total // 0')
  if [[ "$TOTAL" -gt 0 ]]; then
    echo "[smoke] PASS: search returned total=$TOTAL for q=$TOKEN"
    echo "$HITS_JSON" | jq '.hits[0]'
    exit 0
  fi
  if [[ $(date +%s) -ge $DEADLINE ]]; then
    echo "FAIL: message $MESSAGE_ID not found in search after ${WAIT_SECS}s" >&2
    echo "$HITS_JSON" >&2
    exit 1
  fi
  sleep 1
done
