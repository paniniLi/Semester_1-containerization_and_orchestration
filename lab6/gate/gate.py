#!/usr/bin/env python3
"""Simple Prometheus-driven canary gate for the Lab 6 HTTPRoute."""

from __future__ import annotations

import json
import logging
import math
import os
import signal
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


PROMETHEUS_JOB = "lab6-linkerd-proxy"
METRICS_WINDOW = "1m"
PROMOTION_INTERVAL_SECONDS = 60
REQUEST_TIMEOUT_SECONDS = 10
SERVICE_ACCOUNT_DIR = Path("/var/run/secrets/kubernetes.io/serviceaccount")

LOG = logging.getLogger("release-gate")
STOP = threading.Event()


@dataclass(frozen=True)
class Settings:
    prometheus_url: str = os.getenv(
        "PROMETHEUS_URL",
        "http://monitoring-prometheus-server.monitoring.svc.cluster.local",
    ).rstrip("/")
    release_name: str = os.getenv("RELEASE_NAME", "shop")
    namespace: str = os.getenv("NAMESPACE", "lab6")
    interval_seconds: float = float(os.getenv("CHECK_INTERVAL_SECONDS", "15"))
    min_requests: float = float(os.getenv("MIN_REQUESTS", "20"))
    error_threshold: float = float(os.getenv("ERROR_THRESHOLD", "0.05"))
    average_latency_threshold_seconds: float = float(
        os.getenv("AVERAGE_LATENCY_THRESHOLD_SECONDS", "0.5")
    )
    promotion_step: int = int(os.getenv("PROMOTION_STEP", "10"))

    @property
    def route_name(self) -> str:
        return f"{self.release_name}-api-split"

    @property
    def parent_service(self) -> str:
        return f"{self.release_name}-api"

    @property
    def v1_backend(self) -> str:
        return f"{self.release_name}-api-v1"

    @property
    def v2_backend(self) -> str:
        return f"{self.release_name}-api-v2"

    def validate(self) -> None:
        if self.interval_seconds <= 0:
            raise ValueError("CHECK_INTERVAL_SECONDS must be positive")
        if self.min_requests < 0:
            raise ValueError("MIN_REQUESTS cannot be negative")
        if not 0 <= self.error_threshold <= 1:
            raise ValueError("ERROR_THRESHOLD must be between 0 and 1")
        if self.average_latency_threshold_seconds < 0:
            raise ValueError("AVERAGE_LATENCY_THRESHOLD_SECONDS cannot be negative")
        if not 1 <= self.promotion_step <= 100:
            raise ValueError("PROMOTION_STEP must be between 1 and 100")


@dataclass(frozen=True)
class Decision:
    action: str
    reason: str
    v1_weight: int | None = None
    v2_weight: int | None = None


def promql_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def build_queries(settings: Settings) -> dict[str, str]:
    selector = ",".join(
        (
            f'job="{PROMETHEUS_JOB}"',
            f'parent_namespace="{promql_escape(settings.namespace)}"',
            f'parent_name="{promql_escape(settings.parent_service)}"',
            f'backend_namespace="{promql_escape(settings.namespace)}"',
            f'backend_name="{promql_escape(settings.v2_backend)}"',
        )
    )
    return {
        "requests": (
            "sum(increase(outbound_http_route_backend_response_statuses_total"
            f"{{{selector}}}[{METRICS_WINDOW}]))"
        ),
        "errors": (
            "sum(increase(outbound_http_route_backend_response_statuses_total"
            f'{{{selector},http_status=~"5.."}}[{METRICS_WINDOW}]))'
        ),
        "average_latency": (
            "sum(rate(outbound_http_route_backend_response_duration_seconds_sum"
            f"{{{selector}}}[{METRICS_WINDOW}])) / clamp_min("
            "sum(rate(outbound_http_route_backend_response_duration_seconds_count"
            f"{{{selector}}}[{METRICS_WINDOW}])), 0.001)"
        ),
    }


class PrometheusClient:
    def __init__(self, base_url: str):
        self.query_url = f"{base_url}/api/v1/query"

    def query_scalar(self, query: str) -> float | None:
        url = f"{self.query_url}?{urllib.parse.urlencode({'query': query})}"
        request = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(
            request, timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
            payload = json.load(response)

        if payload.get("status") != "success":
            raise RuntimeError(f"Prometheus query failed: {payload}")

        result = payload.get("data", {}).get("result", [])
        if not result:
            return None
        if len(result) != 1:
            raise RuntimeError(f"expected one Prometheus result, got {len(result)}")

        value = float(result[0]["value"][1])
        return value if math.isfinite(value) else None


class KubernetesHttpRouteClient:
    def __init__(self, settings: Settings):
        host = os.getenv("KUBERNETES_SERVICE_HOST")
        port = os.getenv("KUBERNETES_SERVICE_PORT_HTTPS", "443")
        if not host:
            raise RuntimeError("KUBERNETES_SERVICE_HOST is not set")

        self.token = (SERVICE_ACCOUNT_DIR / "token").read_text(
            encoding="utf-8"
        ).strip()
        self.ssl_context = ssl.create_default_context(
            cafile=str(SERVICE_ACCOUNT_DIR / "ca.crt")
        )
        namespace = urllib.parse.quote(settings.namespace, safe="")
        route_name = urllib.parse.quote(settings.route_name, safe="")
        self.url = (
            f"https://{host}:{port}/apis/gateway.networking.k8s.io/v1/"
            f"namespaces/{namespace}/httproutes/{route_name}"
        )

    def _request(
        self,
        method: str,
        body: Any | None = None,
        content_type: str = "application/json",
    ) -> Any:
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(
            self.url,
            data=data,
            method=method,
            headers={
                "Accept": "application/json",
                "Authorization": f"Bearer {self.token}",
                "Content-Type": content_type,
            },
        )
        try:
            with urllib.request.urlopen(
                request,
                context=self.ssl_context,
                timeout=REQUEST_TIMEOUT_SECONDS,
            ) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            details = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(
                f"Kubernetes API returned HTTP {error.code}: {details}"
            ) from error

    def get(self) -> dict[str, Any]:
        return self._request("GET")

    def patch_weights(
        self,
        route: dict[str, Any],
        v1_backend: str,
        v2_backend: str,
        v1_weight: int,
        v2_weight: int,
    ) -> None:
        backend_refs = route["spec"]["rules"][0]["backendRefs"]
        indexes = {backend["name"]: index for index, backend in enumerate(backend_refs)}
        missing = {v1_backend, v2_backend} - indexes.keys()
        if missing:
            raise RuntimeError(f"HTTPRoute is missing backends: {sorted(missing)}")

        patch = [
            {
                "op": "replace",
                "path": f"/spec/rules/0/backendRefs/{indexes[v1_backend]}/weight",
                "value": v1_weight,
            },
            {
                "op": "replace",
                "path": f"/spec/rules/0/backendRefs/{indexes[v2_backend]}/weight",
                "value": v2_weight,
            },
        ]
        self._request("PATCH", patch, "application/json-patch+json")


def backend_weights(
    route: dict[str, Any], v1_backend: str, v2_backend: str
) -> tuple[int, int]:
    refs = route["spec"]["rules"][0]["backendRefs"]
    weights = {ref["name"]: int(ref.get("weight", 1)) for ref in refs}
    missing = {v1_backend, v2_backend} - weights.keys()
    if missing:
        raise RuntimeError(f"HTTPRoute is missing backends: {sorted(missing)}")
    return weights[v1_backend], weights[v2_backend]


def evaluate(
    settings: Settings,
    request_count: float | None,
    error_count: float | None,
    average_latency_seconds: float | None,
    current_v2_weight: int,
) -> Decision:
    if request_count is None or request_count < settings.min_requests:
        return Decision(
            "HOLD",
            f"insufficient v2 traffic ({request_count or 0:.1f} < {settings.min_requests:.1f})",
        )

    error_rate = (error_count or 0.0) / request_count
    if error_rate > settings.error_threshold:
        return Decision(
            "ROLLBACK",
            f"v2 error rate {error_rate:.2%} > {settings.error_threshold:.2%}",
            100,
            0,
        )

    if average_latency_seconds is None:
        return Decision("HOLD", "v2 average latency is unavailable")

    if average_latency_seconds > settings.average_latency_threshold_seconds:
        return Decision(
            "ROLLBACK",
            "v2 average latency "
            f"{average_latency_seconds:.3f}s > "
            f"{settings.average_latency_threshold_seconds:.3f}s",
            100,
            0,
        )

    if current_v2_weight >= 100:
        return Decision("COMPLETE", "v2 already receives 100% of traffic")

    new_v2_weight = min(100, current_v2_weight + settings.promotion_step)
    return Decision(
        "PROMOTE",
        "v2 error rate and average latency are below thresholds",
        100 - new_v2_weight,
        new_v2_weight,
    )


def run_once(
    settings: Settings,
    prometheus: PrometheusClient,
    kubernetes: KubernetesHttpRouteClient,
    queries: dict[str, str],
    last_promotion_time: float | None,
) -> float | None:
    request_count = prometheus.query_scalar(queries["requests"])
    error_count = prometheus.query_scalar(queries["errors"])
    average_latency_seconds = prometheus.query_scalar(queries["average_latency"])
    route = kubernetes.get()
    current_v1, current_v2 = backend_weights(
        route, settings.v1_backend, settings.v2_backend
    )
    decision = evaluate(
        settings,
        request_count,
        error_count,
        average_latency_seconds,
        current_v2,
    )

    now = time.monotonic()
    if (
        decision.action == "PROMOTE"
        and last_promotion_time is not None
        and now - last_promotion_time < PROMOTION_INTERVAL_SECONDS
    ):
        seconds_left = math.ceil(
            PROMOTION_INTERVAL_SECONDS - (now - last_promotion_time)
        )
        decision = Decision(
            "HOLD",
            f"waiting {seconds_left}s for fresh metrics before next promotion",
        )

    error_rate = None
    if request_count:
        error_rate = (error_count or 0.0) / request_count
    LOG.info(
        "action=%s reason=%s requests=%s error_rate=%s "
        "average_latency_seconds=%s weights=%d/%d",
        decision.action,
        decision.reason,
        "none" if request_count is None else f"{request_count:.1f}",
        "none" if error_rate is None else f"{error_rate:.4f}",
        (
            "none"
            if average_latency_seconds is None
            else f"{average_latency_seconds:.4f}"
        ),
        current_v1,
        current_v2,
    )

    if decision.v1_weight is None or decision.v2_weight is None:
        return last_promotion_time
    if (current_v1, current_v2) == (decision.v1_weight, decision.v2_weight):
        return last_promotion_time

    kubernetes.patch_weights(
        route,
        settings.v1_backend,
        settings.v2_backend,
        decision.v1_weight,
        decision.v2_weight,
    )
    LOG.info(
        "patched HTTPRoute %s/%s weights=%d/%d",
        settings.namespace,
        settings.route_name,
        decision.v1_weight,
        decision.v2_weight,
    )
    if decision.action == "PROMOTE":
        return now
    return None


def reset_weights(
    settings: Settings,
    kubernetes: KubernetesHttpRouteClient,
) -> int:
    try:
        v1_weight = int(os.environ["INITIAL_V1_WEIGHT"])
        v2_weight = int(os.environ["INITIAL_V2_WEIGHT"])
    except KeyError as error:
        raise ValueError(f"{error.args[0]} must be set in reset mode") from error

    if v1_weight < 0 or v2_weight < 0:
        raise ValueError("initial traffic weights cannot be negative")
    if v1_weight + v2_weight != 100:
        raise ValueError("initial traffic weights must add up to 100")

    route = kubernetes.get()
    current_v1, current_v2 = backend_weights(
        route, settings.v1_backend, settings.v2_backend
    )
    if (current_v1, current_v2) != (v1_weight, v2_weight):
        kubernetes.patch_weights(
            route,
            settings.v1_backend,
            settings.v2_backend,
            v1_weight,
            v2_weight,
        )

    LOG.info(
        "reset HTTPRoute %s/%s weights=%d/%d",
        settings.namespace,
        settings.route_name,
        v1_weight,
        v2_weight,
    )
    return 0


def handle_signal(signum: int, _frame: Any) -> None:
    LOG.info("received signal=%d; stopping", signum)
    STOP.set()


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    settings = Settings()
    settings.validate()
    kubernetes = KubernetesHttpRouteClient(settings)

    if os.getenv("RESET_WEIGHTS", "false").lower() == "true":
        return reset_weights(settings, kubernetes)

    queries = build_queries(settings)
    prometheus = PrometheusClient(settings.prometheus_url)

    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)
    LOG.info(
        "started namespace=%s route=%s interval=%.1fs",
        settings.namespace,
        settings.route_name,
        settings.interval_seconds,
    )

    last_promotion_time = None
    while not STOP.is_set():
        try:
            last_promotion_time = run_once(
                settings,
                prometheus,
                kubernetes,
                queries,
                last_promotion_time,
            )
        except Exception:
            LOG.exception("action=HOLD reason=gate iteration failed")
        STOP.wait(settings.interval_seconds)

    return 0


if __name__ == "__main__":
    sys.exit(main())
