#!/usr/bin/env bash

set -u

URL="${1:-http://192.168.49.2:30082/health}"
LOG_FILE="${2:-curl-ddos.log}"
INTERVAL_NANOSECONDS=1000000000
REQUEST_TIMEOUT_SECONDS=10

BODY_FILE="$(mktemp)"

cleanup() {
  rm -f -- "$BODY_FILE"
}

stop() {
  exit 0
}

trap cleanup EXIT
trap stop INT TERM

json_escape() {
  local value="$1"

  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\b'/\\b}"
  value="${value//$'\f'/\\f}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\t'/\\t}"

  printf '%s' "$value"
}

echo "Sending requests to $URL once per second."
echo "Writing results to $LOG_FILE. Press Ctrl+C to stop."

NEXT_REQUEST_AT="$(date +%s%N)"

while true; do
  NOW="$(date +%s%N)"
  DELAY=$((NEXT_REQUEST_AT - NOW))

  if ((DELAY > 0)); then
    printf -v SLEEP_DURATION '%d.%09d' \
      "$((DELAY / 1000000000))" \
      "$((DELAY % 1000000000))"
    sleep "$SLEEP_DURATION"
  fi

  : > "$BODY_FILE"
  TIMESTAMP="$(date '+%Y-%m-%dT%H:%M:%S.%3N%:z')"

  HTTP_CODE="$(
    curl \
      --silent \
      --show-error \
      --max-time "$REQUEST_TIMEOUT_SECONDS" \
      --output "$BODY_FILE" \
      --write-out '%{http_code}' \
      "$URL"
  )"

  if [[ "$HTTP_CODE" =~ ^[0-9]+$ ]]; then
    HTTP_CODE=$((10#$HTTP_CODE))
  else
    HTTP_CODE=0
  fi

  BODY="$(<"$BODY_FILE")"
  ESCAPED_BODY="$(json_escape "$BODY")"

  printf \
    '{"timestamp":"%s","response":{"code":%d,"body":"%s"}}\n' \
    "$TIMESTAMP" \
    "$HTTP_CODE" \
    "$ESCAPED_BODY" \
    >> "$LOG_FILE"

  NEXT_REQUEST_AT=$((NEXT_REQUEST_AT + INTERVAL_NANOSECONDS))
  NOW="$(date +%s%N)"
  if ((NEXT_REQUEST_AT < NOW)); then
    NEXT_REQUEST_AT=$((NOW + INTERVAL_NANOSECONDS))
  fi
done
