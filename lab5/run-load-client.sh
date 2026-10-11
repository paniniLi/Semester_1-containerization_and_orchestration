#!/usr/bin/env bash

set -Eeuo pipefail

KUBE_CONTEXT="${KUBE_CONTEXT:-kind-lab5}"
NAMESPACE="${NAMESPACE:-lab5}"
POD_NAME="${POD_NAME:-load-client}"
IMAGE="${IMAGE:-curlimages/curl:latest}"
TARGET_URL="${TARGET_URL:-http://shop-api.lab5.svc.cluster.local:8080/health}"
PUSHGATEWAY_URL="${PUSHGATEWAY_URL:-http://monitoring-prometheus-pushgateway.monitoring.svc.cluster.local:9091/metrics/job/load-client/namespace/lab5/instance/load-client}"

if ! command -v kubectl >/dev/null 2>&1; then
  echo "Error: kubectl is not installed or is not available in PATH." >&2
  exit 1
fi

if ! kubectl \
  --context "$KUBE_CONTEXT" \
  --namespace monitoring \
  get service monitoring-prometheus-pushgateway \
  --request-timeout=10s \
  >/dev/null; then
  echo "Error: Prometheus Pushgateway is not installed in namespace 'monitoring'." >&2
  echo "Upgrade the monitoring release with lab5/install/monitoring.values.yaml first." >&2
  exit 1
fi

kubectl \
  --context "$KUBE_CONTEXT" \
  --namespace "$NAMESPACE" \
  delete pod "$POD_NAME" \
  --ignore-not-found \
  --wait=true

kubectl \
  --context "$KUBE_CONTEXT" \
  --namespace "$NAMESPACE" \
  run "$POD_NAME" \
  --image "$IMAGE" \
  --image-pull-policy IfNotPresent \
  --restart Never \
  --labels app.kubernetes.io/name=load-client,app.kubernetes.io/part-of=shop \
  --env "TARGET_URL=$TARGET_URL" \
  --env "PUSHGATEWAY_URL=$PUSHGATEWAY_URL" \
  --command -- sh -c '
    successful_requests=0
    failed_requests=0

    push_metrics() {
      printf "%s\n" \
        "# HELP load_client_requests_total Requests performed by the load client." \
        "# TYPE load_client_requests_total counter" \
        "load_client_requests_total{result=\"success\"} ${successful_requests}" \
        "load_client_requests_total{result=\"failed\"} ${failed_requests}" \
      | curl \
          --silent \
          --show-error \
          --fail \
          --max-time 3 \
          --data-binary @- \
          "$PUSHGATEWAY_URL" \
          >/dev/null
    }

    push_metrics || echo "Unable to publish initial Prometheus metrics" >&2

    while true; do
      if curl \
        --silent \
        --show-error \
        --fail \
        --connect-timeout 1 \
        --max-time 3 \
        --output /dev/null \
        "$TARGET_URL";
      then
        successful_requests=$((successful_requests + 1))
        result="SUCCESS"
      else
        failed_requests=$((failed_requests + 1))
        result="FAILED"
      fi

      total_requests=$((successful_requests + failed_requests))
      echo "$(date -u "+%Y-%m-%dT%H:%M:%SZ") ${result} total=${total_requests} success=${successful_requests} failed=${failed_requests}"

      if [ $((total_requests % 5)) -eq 0 ]; then
        push_metrics || echo "Unable to publish Prometheus metrics" >&2
      fi

      sleep 0.2
    done
  '

kubectl \
  --context "$KUBE_CONTEXT" \
  --namespace "$NAMESPACE" \
  wait \
  --for=condition=Ready \
  "pod/$POD_NAME" \
  --timeout=2m

echo "Load client is running. Follow its output with:"
echo "kubectl --context $KUBE_CONTEXT logs --namespace $NAMESPACE --follow $POD_NAME"
