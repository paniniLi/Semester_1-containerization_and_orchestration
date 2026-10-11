#!/usr/bin/env bash

set -Eeuo pipefail

KUBE_CONTEXT="${KUBE_CONTEXT:-kind-lab5}"
NAMESPACE="${NAMESPACE:-lab5}"
RELEASE="${RELEASE:-shop}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-5}"

SELECTOR="app.kubernetes.io/name=shop-api,app.kubernetes.io/instance=${RELEASE}"

if ! command -v kubectl >/dev/null 2>&1; then
  echo "Error: kubectl is not installed or is not available in PATH." >&2
  exit 1
fi

if [[ ! "$INTERVAL_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  echo "Error: INTERVAL_SECONDS must be a positive integer." >&2
  exit 1
fi

if ! kubectl \
  --context "$KUBE_CONTEXT" \
  --namespace "$NAMESPACE" \
  get pods \
  --selector "$SELECTOR" \
  --request-timeout=10s \
  >/dev/null; then
  echo "Error: unable to read API pods in context '$KUBE_CONTEXT', namespace '$NAMESPACE'." >&2
  exit 1
fi

if [[ "$(kubectl \
  --context "$KUBE_CONTEXT" \
  --namespace "$NAMESPACE" \
  auth can-i delete pods)" != "yes" ]]; then
  echo "Error: current user is not allowed to delete pods in namespace '$NAMESPACE'." >&2
  exit 1
fi

stop() {
  echo
  echo "Chaos stopped."
  exit 0
}

trap stop INT TERM

echo "Starting API pod chaos."
echo "Context:  $KUBE_CONTEXT"
echo "Namespace: $NAMESPACE"
echo "Selector:  $SELECTOR"
echo "Interval:  ${INTERVAL_SECONDS}s"
echo "Press Ctrl+C to stop."

while true; do
  if ! pod_output="$(kubectl \
    --context "$KUBE_CONTEXT" \
    --namespace "$NAMESPACE" \
    get pods \
    --selector "$SELECTOR" \
    --field-selector status.phase=Running \
    --output custom-columns='NAME:.metadata.name' \
    --no-headers \
    --request-timeout=10s)"; then
    echo "$(date -Iseconds) Failed to list API pods; retrying in ${INTERVAL_SECONDS}s." >&2
    sleep "$INTERVAL_SECONDS"
    continue
  fi

  pods=()
  while IFS= read -r pod; do
    [[ -n "$pod" ]] && pods+=("$pod")
  done <<< "$pod_output"

  if ((${#pods[@]} == 0)); then
    echo "$(date -Iseconds) No running API pods found; retrying in ${INTERVAL_SECONDS}s."
    sleep "$INTERVAL_SECONDS"
    continue
  fi

  target_pod="${pods[RANDOM % ${#pods[@]}]}"
  echo "$(date -Iseconds) Deleting pod $target_pod"

  if ! kubectl \
    --context "$KUBE_CONTEXT" \
    --namespace "$NAMESPACE" \
    delete pod "$target_pod" \
    --wait=false \
    --request-timeout=10s; then
    echo "$(date -Iseconds) Failed to delete pod $target_pod; continuing." >&2
  fi

  sleep "$INTERVAL_SECONDS"
done
