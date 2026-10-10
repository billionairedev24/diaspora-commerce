#!/usr/bin/env python3
"""Scale idle preview namespaces to zero and wake them again (S182).

Runs as a CronJob in the dev cluster (infra/k8s/platform/previews). A preview namespace is any namespace labelled
`afrotransact.com/preview=true` (per-PR namespaces `afrotransact-pr-<n>`). Every run, for each such namespace:

  * keep-awake    annotation `afrotransact.com/keep-awake: "true"` -> never scaled down.
  * idle          no HTTP requests to any workload of the namespace for IDLE_MINUTES (Prometheus: the services'
                  http_server_requests_* counters, labelled with the namespace by the ServiceMonitors), the namespace
                  is older than IDLE_MINUTES, or the current time is outside the optional uptime window
                  (UPTIME_DAYS / UPTIME_HOURS, e.g. "Mon-Fri" / "07-20" in UPTIME_TZ).
                  -> every Deployment / StatefulSet / Argo Rollout with replicas > 0 is scaled to 0 through its
                     `scale` subresource; the previous replica counts and the resulting metadata.generation values
                     are kept in the namespace annotation `afrotransact.com/idle-state` (JSON), next to
                     `afrotransact.com/scaled-to-zero-at`. The scaler never edits the workloads themselves.
  * wake          a scaled-down namespace wakes (replicas restored) when
                  - someone annotates it: kubectl annotate ns <ns> afrotransact.com/wake="$(date -u +%FT%TZ)" --overwrite
                  - or a new version is deployed (a workload's spec changed, so metadata.generation moved past the one
                    recorded at scale-down: Argo CD synced a new commit of the PR).
                  A woken namespace is not considered idle again for IDLE_MINUTES.

Preview Applications must not fight the scaler: no HPA (autoscaling.enabled=false) and Argo CD
`ignoreDifferences` on /spec/replicas with `RespectIgnoreDifferences=true`.

Environment: PROMETHEUS_URL, IDLE_MINUTES (default 30), UPTIME_DAYS, UPTIME_HOURS, UPTIME_TZ (default UTC),
DRY_RUN=true (log only). Standard library only; talks to the API server with the pod's service account.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from zoneinfo import ZoneInfo

SA = "/var/run/secrets/kubernetes.io/serviceaccount"
API = os.environ.get("KUBE_API", "https://kubernetes.default.svc")
PROM = os.environ.get("PROMETHEUS_URL", "http://kube-prometheus-stack-prometheus.observability:9090")
IDLE = int(os.environ.get("IDLE_MINUTES", "30"))
DRY_RUN = os.environ.get("DRY_RUN", "false").lower() == "true"
LABEL = "afrotransact.com/preview=true"
A_KEEP = "afrotransact.com/keep-awake"
A_WAKE = "afrotransact.com/wake"
A_DOWN = "afrotransact.com/scaled-to-zero-at"
A_UP = "afrotransact.com/woken-at"
A_STATE = "afrotransact.com/idle-state"
DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]

WORKLOADS = [
    ("apis/apps/v1", "deployments"),
    ("apis/apps/v1", "statefulsets"),
    ("apis/argoproj.io/v1alpha1", "rollouts"),
]


def now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def iso(t: dt.datetime) -> str:
    return t.strftime("%Y-%m-%dT%H:%M:%SZ")


def parse(ts: str | None) -> dt.datetime | None:
    if not ts:
        return None
    try:
        return dt.datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except ValueError:
        return None


class Kube:
    def __init__(self) -> None:
        with open(f"{SA}/token", encoding="utf-8") as fh:
            self.token = fh.read().strip()
        self.ctx = ssl.create_default_context(cafile=f"{SA}/ca.crt")

    def call(self, method: str, path: str, body: dict | None = None, ctype: str = "application/merge-patch+json"):
        req = urllib.request.Request(f"{API}/{path}", method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Accept", "application/json")
        if body is not None:
            req.add_header("Content-Type", ctype)
        try:
            with urllib.request.urlopen(req, context=self.ctx, timeout=30) as resp:
                return json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as err:
            if err.code == 404:
                return None
            raise


def in_uptime(t: dt.datetime) -> bool:
    days, hours = os.environ.get("UPTIME_DAYS", ""), os.environ.get("UPTIME_HOURS", "")
    if not days and not hours:
        return True
    local = t.astimezone(ZoneInfo(os.environ.get("UPTIME_TZ", "UTC")))
    if days:
        first, _, last = days.lower().partition("-")
        lo, hi = DAYS.index(first[:3]), DAYS.index((last or first)[:3])
        if not lo <= local.weekday() <= hi:
            return False
    if hours:
        start, _, end = hours.partition("-")
        if not int(start) <= local.hour < int(end):
            return False
    return True


def requests_in_window(ns: str) -> float | None:
    """HTTP requests served in the namespace over the idle window; None when Prometheus cannot answer."""
    q = (f'sum(increase(http_server_requests_seconds_count{{namespace="{ns}"}}[{IDLE}m]))'
         f' or sum(increase(http_server_requests_total{{namespace="{ns}"}}[{IDLE}m]))')
    url = f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": q})
    try:
        with urllib.request.urlopen(url, timeout=20) as resp:
            data = json.loads(resp.read())
    except (urllib.error.URLError, TimeoutError, ValueError) as err:
        print(f"  prometheus unavailable ({err}); not scaling {ns}", file=sys.stderr)
        return None
    result = data.get("data", {}).get("result", [])
    return sum(float(r["value"][1]) for r in result) if result else 0.0


def workloads(kube: Kube, ns: str):
    for group, kind in WORKLOADS:
        listing = kube.call("GET", f"{group}/namespaces/{ns}/{kind}")
        for item in (listing or {}).get("items", []):
            yield group, kind, item


def annotate_ns(kube: Kube, ns: str, annotations: dict) -> None:
    if not DRY_RUN:
        kube.call("PATCH", f"api/v1/namespaces/{ns}", {"metadata": {"annotations": annotations}})


def scale(kube: Kube, group: str, kind: str, ns: str, name: str, replicas: int) -> None:
    kube.call("PATCH", f"{group}/namespaces/{ns}/{kind}/{name}/scale", {"spec": {"replicas": replicas}})


def scale_down(kube: Kube, ns: str, reason: str) -> None:
    print(f"{ns}: scaling to zero ({reason})")
    state = {}
    for group, kind, item in workloads(kube, ns):
        name, replicas = item["metadata"]["name"], int(item["spec"].get("replicas", 1) or 0)
        if replicas == 0:
            continue
        print(f"  {kind}/{name}: {replicas} -> 0")
        if DRY_RUN:
            continue
        scale(kube, group, kind, ns, name, 0)
        after = kube.call("GET", f"{group}/namespaces/{ns}/{kind}/{name}") or {}
        state[f"{kind}/{name}"] = {"group": group, "replicas": replicas,
                                   "generation": int(after.get("metadata", {}).get("generation", 0))}
    annotate_ns(kube, ns, {A_DOWN: iso(now()), A_UP: None, A_STATE: json.dumps(state, sort_keys=True)})


def load_state(ann: dict) -> dict:
    try:
        state = json.loads(ann.get(A_STATE) or "{}")
    except ValueError:
        return {}
    return state if isinstance(state, dict) else {}


def wake(kube: Kube, ns: str, ann: dict, reason: str) -> None:
    print(f"{ns}: waking ({reason})")
    for ref, entry in load_state(ann).items():
        kind, _, name = ref.partition("/")
        current = kube.call("GET", f"{entry['group']}/namespaces/{ns}/{kind}/{name}")
        if current is None:
            continue  # deleted while asleep
        replicas = max(int(current["spec"].get("replicas", 0) or 0), int(entry["replicas"]))
        print(f"  {ref}: -> {replicas}")
        if not DRY_RUN:
            scale(kube, entry["group"], kind, ns, name, replicas)
    annotate_ns(kube, ns, {A_DOWN: None, A_UP: iso(now()), A_WAKE: None, A_STATE: None})


def redeployed(kube: Kube, ns: str, ann: dict) -> bool:
    for ref, entry in load_state(ann).items():
        kind, _, name = ref.partition("/")
        current = kube.call("GET", f"{entry['group']}/namespaces/{ns}/{kind}/{name}")
        if current and int(current["metadata"].get("generation", 0)) > int(entry["generation"]):
            return True
    return False


def reconcile(kube: Kube, ns_obj: dict) -> None:
    meta = ns_obj["metadata"]
    ns, ann = meta["name"], meta.get("annotations") or {}
    t = now()
    down_at = parse(ann.get(A_DOWN))
    if down_at:
        wake_at = parse(ann.get(A_WAKE))
        if wake_at and wake_at >= down_at:
            wake(kube, ns, ann, f"{A_WAKE} annotation")
        elif ann.get(A_WAKE) and not wake_at:
            wake(kube, ns, ann, f"{A_WAKE} annotation")
        elif redeployed(kube, ns, ann):
            wake(kube, ns, ann, "new version deployed")
        else:
            print(f"{ns}: asleep since {iso(down_at)}")
        return
    if ann.get(A_KEEP, "").lower() == "true":
        print(f"{ns}: keep-awake")
        return
    if not in_uptime(t):
        scale_down(kube, ns, "outside the uptime window")
        return
    grace = max(filter(None, [parse(meta.get("creationTimestamp")), parse(ann.get(A_UP))]), default=t)
    if t - grace < dt.timedelta(minutes=IDLE):
        print(f"{ns}: within the {IDLE} min grace period")
        return
    served = requests_in_window(ns)
    if served is None:
        return
    if served < 1:
        scale_down(kube, ns, f"no requests in {IDLE} min")
    else:
        print(f"{ns}: active ({served:.0f} requests in {IDLE} min)")


def main() -> int:
    kube = Kube()
    listing = kube.call("GET", "api/v1/namespaces?labelSelector=" + urllib.parse.quote(LABEL))
    items = (listing or {}).get("items", [])
    print(f"{len(items)} preview namespace(s); idle after {IDLE} min{' (dry run)' if DRY_RUN else ''}")
    failed = 0
    for ns_obj in items:
        try:
            reconcile(kube, ns_obj)
        except Exception as err:  # keep going: one broken namespace must not block the others
            failed += 1
            print(f"{ns_obj['metadata']['name']}: error {err}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
