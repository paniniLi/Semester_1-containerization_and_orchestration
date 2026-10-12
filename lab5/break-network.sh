#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALUES="$ROOT/lab5/install/values.yaml"
BACKUP="/tmp/lab5-values-before-fault.yaml"
ANSWER="/tmp/lab5-fault-answer.txt"

if [[ "${1:-}" == "restore" ]]; then
    if [[ ! -f "$BACKUP" ]]; then
        echo "Backup not found. Nothing to restore."
        exit 1
    fi

    cp "$BACKUP" "$VALUES"

    helm upgrade --install shop "$ROOT/lab5/install" \
        -n lab5 \
        --wait \
        --timeout 5m

    rm -f "$BACKUP" "$ANSWER"

    echo "Original network configuration restored."
    exit 0
fi

if [[ -f "$BACKUP" ]]; then
    echo "A fault is already active."
    echo "Restore it first with:"
    echo "$0 restore"
    exit 1
fi

cp "$VALUES" "$BACKUP"

FAULT=$((RANDOM % 4 + 1))

case "$FAULT" in
    1)
        sed -i \
          '/^    api:/,/^    allow-api-egress:/ s/port: 5432/port: 15432/' \
          "$VALUES"

        echo "api-egress-postgres-wrong-port" > "$ANSWER"
        ;;
    2)
        sed -i \
          '/^    postgres:/,/^    allow-pushgateway-egress:/ s/app.kubernetes.io\/name: shop-api/app.kubernetes.io\/name: shop-api-broken/' \
          "$VALUES"

        echo "postgres-ingress-api-wrong-label" > "$ANSWER"
        ;;
    3)
        sed -i \
          '/^    worker:/,/^    allow-worker-egress:/ s/port: 5432/port: 15432/' \
          "$VALUES"

        echo "worker-egress-postgres-wrong-port" > "$ANSWER"
        ;;
    4)
        sed -i \
          '/^    allow-dns:/,/^    api:/ s/k8s-app: kube-dns/k8s-app: kube-dns-broken/' \
          "$VALUES"

        echo "dns-wrong-label" > "$ANSWER"
        ;;
esac

helm lint "$ROOT/lab5/install" >/dev/null

helm upgrade --install shop "$ROOT/lab5/install" \
    -n lab5 \
    --wait \
    --timeout 5m

echo
echo "A random network fault has been injected."
echo "Do not open $ANSWER yet."
echo "Find the broken connection using tests and Hubble."