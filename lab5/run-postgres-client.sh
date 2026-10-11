#!/usr/bin/env bash

set -Eeuo pipefail

KUBE_CONTEXT="${KUBE_CONTEXT:-kind-lab5}"
NAMESPACE="${NAMESPACE:-lab5}"
POD_NAME="${POD_NAME:-postgres-client}"
IMAGE="${IMAGE:-ghcr.io/cloudnative-pg/postgresql:18.6-system-trixie}"
SECRET_NAME="${SECRET_NAME:-shop-postgres-app}"
CLIENT_IDENTITY="${CLIENT_IDENTITY:-postgres-client}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-2}"
CONNECT_TIMEOUT_SECONDS="${CONNECT_TIMEOUT_SECONDS:-2}"

if ! command -v kubectl >/dev/null 2>&1; then
  echo "Error: kubectl is not installed or is not available in PATH." >&2
  exit 1
fi

if ! kubectl \
  --context "$KUBE_CONTEXT" \
  --namespace "$NAMESPACE" \
  get secret "$SECRET_NAME" \
  --request-timeout=10s \
  >/dev/null; then
  echo "Error: PostgreSQL application secret '$SECRET_NAME' was not found in namespace '$NAMESPACE'." >&2
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
  apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: ${POD_NAME}
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/name: ${CLIENT_IDENTITY}
    app.kubernetes.io/part-of: shop
    app.kubernetes.io/component: connectivity-test
spec:
  restartPolicy: Never
  terminationGracePeriodSeconds: 1
  containers:
    - name: postgres-client
      image: ${IMAGE}
      imagePullPolicy: IfNotPresent
      env:
        - name: PGHOST
          valueFrom:
            secretKeyRef:
              name: ${SECRET_NAME}
              key: host
        - name: PGPORT
          valueFrom:
            secretKeyRef:
              name: ${SECRET_NAME}
              key: port
        - name: PGDATABASE
          valueFrom:
            secretKeyRef:
              name: ${SECRET_NAME}
              key: dbname
        - name: PGUSER
          valueFrom:
            secretKeyRef:
              name: ${SECRET_NAME}
              key: username
        - name: PGPASSWORD
          valueFrom:
            secretKeyRef:
              name: ${SECRET_NAME}
              key: password
        - name: PGCONNECT_TIMEOUT
          value: "${CONNECT_TIMEOUT_SECONDS}"
        - name: INTERVAL_SECONDS
          value: "${INTERVAL_SECONDS}"
      command:
        - /bin/sh
        - -c
      args:
        - |
          successful_requests=0
          failed_requests=0

          while true; do
            if output=\$(psql \
              --no-password \
              --set=ON_ERROR_STOP=1 \
              --tuples-only \
              --no-align \
              --command='SELECT 1;' \
              2>&1);
            then
              successful_requests=\$((successful_requests + 1))
              result="SUCCESS"
              details="query_result=\$(printf '%s' "\$output" | tr -d '[:space:]')"
            else
              failed_requests=\$((failed_requests + 1))
              result="FAILED"
              details="error=\$(printf '%s' "\$output" | tr '\\n' ' ')"
            fi

            total_requests=\$((successful_requests + failed_requests))
            echo "\$(date -u '+%Y-%m-%dT%H:%M:%SZ') \${result} total=\${total_requests} success=\${successful_requests} failed=\${failed_requests} \${details}"
            sleep "\$INTERVAL_SECONDS"
          done
EOF

kubectl \
  --context "$KUBE_CONTEXT" \
  --namespace "$NAMESPACE" \
  wait \
  --for=condition=Ready \
  "pod/$POD_NAME" \
  --timeout=2m

echo "PostgreSQL client is running with network identity '$CLIENT_IDENTITY'."
echo "Follow its output with:"
echo "kubectl --context $KUBE_CONTEXT logs --namespace $NAMESPACE --follow $POD_NAME"
