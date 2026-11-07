#!/usr/bin/env bash
# DiscoveryHub disposition demo (FR-5 + FR-4.6): proves that a disposition
# pass deletes an expired, unheld message while a held message past the same
# retention window survives.
#
# Flow:
#   1. Ingest two CHAT messages, both backdated well past the CURRENT CHAT
#      retention policy (read, never modified) — one "protected" custodian,
#      one "doomed" custodian.
#   2. Create a case and place a hold scoped to the protected custodian.
#   3. Wait for the hold to apply.
#   4. Trigger a manual disposition run and poll until it completes.
#   5. Verify: protected message still readable (200), doomed message gone (404).
#
# Run from the repo root against a stack already up (`docker compose up -d`).
# Exits non-zero on failure.
#
# IMPORTANT: retention is a GLOBAL per-comm-type policy. An earlier version
# of this script shortened it (e.g. to PT20S) to avoid waiting — but against
# a populated corpus (the 10k-message generator backdates sentAt up to ~7
# days) that makes the ENTIRE existing CHAT corpus eligible too, and the very
# next disposition run (manual or the @Scheduled tick, every 2 min by
# default) deletes all of it, not just the two demo messages. This version
# never touches the retention policy at all: it reads whatever is currently
# configured and backdates only the two demo messages' sentAt far enough past
# it to be individually eligible, leaving every other message untouched.
#
#   BASE_URL       api-gateway base url (default http://localhost:8080)
#   HOLD_WAIT_SECS max time to wait for the hold to apply (default 30)
#   RUN_WAIT_SECS  max time to wait for the disposition run to finish (default 30)
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
HOLD_WAIT_SECS="${HOLD_WAIT_SECS:-30}"
RUN_WAIT_SECS="${RUN_WAIT_SECS:-30}"

command -v jq >/dev/null || { echo "FAIL: jq is required" >&2; exit 2; }

TOKEN="disp$(date +%s)$RANDOM"
PROTECTED_CUSTODIAN="protected-$TOKEN"
DOOMED_CUSTODIAN="doomed-$TOKEN"

CHAT_RETENTION_ISO=$(curl -fsS "$BASE_URL/api/holds/retention-policy" \
  | jq -r '.policies[] | select(.commType=="CHAT") | .duration')
[[ -n "$CHAT_RETENTION_ISO" ]] || { echo "FAIL: could not read the current CHAT retention policy" >&2; exit 1; }
echo "[demo] current CHAT retention is $CHAT_RETENTION_ISO (left unchanged — see script header)"

# Backdate the demo messages' sentAt to (now - retention - 1 day), so only
# these two are past the cutoff — every other CHAT message keeps its real,
# recent timestamp and stays untouched.
CHAT_RETENTION_SECS=$(python3 -c "
import re, sys
iso = sys.argv[1]
m = re.fullmatch(r'P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?', iso)
if not m or not any(m.groups()):
    print(0); sys.exit()
d, h, mi, s = (int(x) if x else 0 for x in m.groups())
print(d*86400 + h*3600 + mi*60 + s)
" "$CHAT_RETENTION_ISO")
[[ "$CHAT_RETENTION_SECS" -gt 0 ]] || { echo "FAIL: could not parse CHAT retention '$CHAT_RETENTION_ISO' as an ISO-8601 duration" >&2; exit 1; }
BACKDATE_SECS=$(( CHAT_RETENTION_SECS + 86400 ))
NOW_EPOCH=$(date -u +%s)
DEMO_SENT_AT=$(date -u -r $(( NOW_EPOCH - BACKDATE_SECS )) +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
  || date -u -d "@$(( NOW_EPOCH - BACKDATE_SECS ))" +%Y-%m-%dT%H:%M:%SZ)
echo "[demo] backdating demo messages to $DEMO_SENT_AT (retention + 1 day in the past) — the rest of the corpus is untouched"

ingest_message() {
  local custodian="$1" label="$2"
  curl -fsS -X POST "$BASE_URL/api/messages/ingest" \
    -H 'Content-Type: application/json' \
    -d "{
      \"externalMessageId\": \"$label-$TOKEN\",
      \"type\": \"CHAT\",
      \"subject\": \"Disposition demo ($label)\",
      \"body\": \"Disposition demo payload $TOKEN — $label message.\",
      \"threadId\": \"thread-$label-$TOKEN\",
      \"custodian\": \"$custodian\",
      \"participants\": [
        {\"name\": \"Demo Sender\", \"address\": \"sender@demo.local\", \"role\": \"FROM\"},
        {\"name\": \"Demo Recipient\", \"address\": \"recipient@demo.local\", \"role\": \"TO\"}
      ],
      \"sentAt\": \"$DEMO_SENT_AT\"
    }"
}

echo "[demo] ingesting protected message (custodian=$PROTECTED_CUSTODIAN)..."
PROTECTED_RESP=$(ingest_message "$PROTECTED_CUSTODIAN" "protected")
PROTECTED_ID=$(echo "$PROTECTED_RESP" | jq -r '.messageId // empty')
[[ -n "$PROTECTED_ID" ]] || { echo "FAIL: protected message ingest did not return a messageId: $PROTECTED_RESP" >&2; exit 1; }
echo "[demo] protected messageId=$PROTECTED_ID"

echo "[demo] ingesting doomed message (custodian=$DOOMED_CUSTODIAN)..."
DOOMED_RESP=$(ingest_message "$DOOMED_CUSTODIAN" "doomed")
DOOMED_ID=$(echo "$DOOMED_RESP" | jq -r '.messageId // empty')
[[ -n "$DOOMED_ID" ]] || { echo "FAIL: doomed message ingest did not return a messageId: $DOOMED_RESP" >&2; exit 1; }
echo "[demo] doomed messageId=$DOOMED_ID"

echo "[demo] creating case..."
CASE_RESP=$(curl -fsS -X POST "$BASE_URL/api/cases" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"Disposition demo $TOKEN\",\"description\":\"FR-5 demo\",\"matterType\":\"INVESTIGATION\",\"owner\":\"demo-script\"}")
CASE_ID=$(echo "$CASE_RESP" | jq -r '.id // empty')
[[ -n "$CASE_ID" ]] || { echo "FAIL: case creation did not return an id: $CASE_RESP" >&2; exit 1; }
echo "[demo] caseId=$CASE_ID"

echo "[demo] placing hold on custodian=$PROTECTED_CUSTODIAN..."
HOLD_RESP=$(curl -fsS -X POST "$BASE_URL/api/holds" \
  -H 'Content-Type: application/json' \
  -d "{\"caseId\":\"$CASE_ID\",\"custodians\":[\"$PROTECTED_CUSTODIAN\"],\"placedBy\":\"demo-script\"}")
HOLD_ID=$(echo "$HOLD_RESP" | jq -r '.holdId // empty')
[[ -n "$HOLD_ID" ]] || { echo "FAIL: hold placement did not return a holdId: $HOLD_RESP" >&2; exit 1; }
echo "[demo] holdId=$HOLD_ID (status=$(echo "$HOLD_RESP" | jq -r '.status'))"

echo "[demo] waiting up to ${HOLD_WAIT_SECS}s for the hold to apply..."
DEADLINE=$(( $(date +%s) + HOLD_WAIT_SECS ))
while :; do
  HOLD_STATUS=$(curl -fsS "$BASE_URL/api/holds/$HOLD_ID" | jq -r '.status // empty')
  APPLIED=$(curl -fsS "$BASE_URL/api/holds/$HOLD_ID" | jq -r '.appliedCount // 0')
  if [[ "$HOLD_STATUS" == "ACTIVE" && "$APPLIED" -ge 1 ]]; then
    echo "[demo] hold is ACTIVE, appliedCount=$APPLIED"
    break
  fi
  if [[ $(date +%s) -ge $DEADLINE ]]; then
    echo "FAIL: hold $HOLD_ID did not reach ACTIVE with appliedCount>=1 after ${HOLD_WAIT_SECS}s (status=$HOLD_STATUS, applied=$APPLIED)" >&2
    exit 1
  fi
  sleep 1
done

echo "[demo] triggering a manual disposition run (both demo messages are already past retention by construction)..."
RUN_HTTP_BODY=$(mktemp)
RUN_HTTP_CODE=$(curl -s -o "$RUN_HTTP_BODY" -w '%{http_code}' -X POST "$BASE_URL/api/holds/disposition/run")
if [[ "$RUN_HTTP_CODE" == "409" ]]; then
  # The @Scheduled tick (every 2 min by default) beat us to the lock — that's
  # fine, it does the same work; just track whatever run is already going.
  echo "[demo] a scheduled disposition pass was already running (409); tracking it instead"
  RUN_ID=$(curl -fsS "$BASE_URL/api/holds/disposition/runs/latest" | jq -r '.id // empty')
else
  RUN_RESP=$(cat "$RUN_HTTP_BODY")
  RUN_ID=$(echo "$RUN_RESP" | jq -r '.id // empty')
  [[ "$RUN_HTTP_CODE" == "202" && -n "$RUN_ID" ]] || { echo "FAIL: disposition run request failed (http=$RUN_HTTP_CODE): $RUN_RESP" >&2; rm -f "$RUN_HTTP_BODY"; exit 1; }
fi
rm -f "$RUN_HTTP_BODY"
[[ -n "$RUN_ID" ]] || { echo "FAIL: could not determine a disposition run id to track" >&2; exit 1; }
echo "[demo] tracking disposition run $RUN_ID"

echo "[demo] polling up to ${RUN_WAIT_SECS}s for the run to finish..."
DEADLINE=$(( $(date +%s) + RUN_WAIT_SECS ))
while :; do
  LATEST=$(curl -fsS "$BASE_URL/api/holds/disposition/runs/latest")
  RUN_STATUS=$(echo "$LATEST" | jq -r '.status // empty')
  if [[ "$RUN_STATUS" != "RUNNING" && -n "$RUN_STATUS" ]]; then
    echo "[demo] run finished: status=$RUN_STATUS deleted=$(echo "$LATEST" | jq -r '.deleted') skippedOnHold=$(echo "$LATEST" | jq -r '.skippedOnHold') errors=$(echo "$LATEST" | jq -r '.errors')"
    break
  fi
  if [[ $(date +%s) -ge $DEADLINE ]]; then
    echo "FAIL: disposition run $RUN_ID still RUNNING after ${RUN_WAIT_SECS}s" >&2
    exit 1
  fi
  sleep 1
done

echo "[demo] verifying protected message ($PROTECTED_ID) is still readable..."
PROTECTED_HTTP=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/messages/$PROTECTED_ID")
[[ "$PROTECTED_HTTP" == "200" ]] || { echo "FAIL: expected 200 for held message $PROTECTED_ID, got $PROTECTED_HTTP" >&2; exit 1; }
echo "[demo] PASS: held message survived (200)"

echo "[demo] verifying doomed message ($DOOMED_ID) was deleted..."
DOOMED_HTTP=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/messages/$DOOMED_ID")
[[ "$DOOMED_HTTP" == "404" ]] || { echo "FAIL: expected 404 for expired unheld message $DOOMED_ID, got $DOOMED_HTTP" >&2; exit 1; }
echo "[demo] PASS: expired unheld message was deleted (404)"

echo "[demo] PASS: disposition deletes past-retention messages except held ones (FR-5, FR-4.2/4.6) — caseId=$CASE_ID holdId=$HOLD_ID runId=$RUN_ID"
