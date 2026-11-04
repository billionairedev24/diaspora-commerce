#!/usr/bin/env python3
"""Per-environment configuration guard (S188 environment configuration, S182 sizing defaults).

Checks every service against the environment rules in docs/engineering/environments.md:

  profiles     Java: application.yml + application-{local,dev,uat,prod}.yml; Go: config/{local,dev,uat,prod}.yaml;
               Helm: values.yaml + values-{dev,uat,prod}.yaml with `env:` matching the file.
  docs pages   API reference pages (Swagger UI, Scalar, Redoc) on in local/dev/uat, off in prod:
               Java at.api-docs.enabled (+ springdoc api-docs / swagger-ui disabled in prod), Go docsEnabled.
  sampling     trace sampling 100% dev (and local), 50% uat, 10% prod: Java management.tracing.sampling.probability
               (literal or the ${TRACE_SAMPLING:<default>} default), Go telemetry.sampleRatio, Helm telemetry.sampling
               (injected as TRACE_SAMPLING, so it wins over the profile default).
  sizing       (S182) dev: 1 replica, no HPA, tolerates the spot pool; uat: tolerates the spot pool; prod: no spot
               toleration, at least 2 replicas or an HPA with min >= 2 (1 when prod's sizing is the lean single-node
               profile, infra/tofu/envs/prod/sizing.tf node_size = "lean", docs/engineering/cloud-cost.md); every env sets CPU/memory requests and a
               memory limit.
  image        no `latest` tag in any values file.
  stripe       (S282) Stripe keys come only from the environment / secret store: gateway-bff's stripe.publishableKey is
               empty in every config/<env>.yaml (STRIPE_PUBLISHABLE_KEY; the BFF refuses to start without a pk_test_ key
               in dev/uat or a pk_live_ key in prod, so the storefront's dev-only Stripe stand-in is local-only);
               payments' at.payments.stripe.secret-key / webhook-secret have no default outside local and
               require-live-key is true in prod only; the Helm charts list the Stripe keys their secret store must hold.
  plan fees    (S298) seller's kill switch at.seller.billing.collect-from-earnings (COLLECT_PLAN_FEES_FROM_EARNINGS) is on
               in local and dev (profile default and Helm values-dev), and wherever it can be on, the seller-service client
               at identity holds the payments:plan-fees scope and the Helm chart sets PAYMENTS_URL (otherwise every
               withholding fails and the card pays).
  timeouts     (payments idempotency) gateway-bff's upstreams.orders.slowTimeout is above orders'
               at.orders.checkout-response-timeout in every environment, and upstreams.deliveryRuns.slowTimeout above
               delivery-runs' orders timeout: the BFF never reports a failure for a checkout or late add that still
               completes (a retry would then look like a new purchase).

  python3 tools/ci/check_env_config.py                 # whole repository; exit 1 on violations
  python3 tools/ci/check_env_config.py --format github # ::error annotations
  python3 tools/ci/check_env_config.py --service cart  # one service (repeatable)

Standard library + PyYAML. Exit 0 clean, 1 violations, 2 usage error.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
ENVS = ["local", "dev", "uat", "prod"]
DEPLOYED = ["dev", "uat", "prod"]
SAMPLING = {"local": 1.0, "dev": 1.0, "uat": 0.5, "prod": 0.1}
DOCS_ON = {"local": True, "dev": True, "uat": True, "prod": False}
SPOT_KEY = "afrotransact.com/spot"
PLACEHOLDER = re.compile(r"^\$\{([A-Z0-9_]+)(?::([^}]*))?\}$")


class Report:
    def __init__(self, fmt: str) -> None:
        self.fmt = fmt
        self.errors: list[tuple[str, str]] = []
        self.checked = 0

    def error(self, path: pathlib.Path, msg: str) -> None:
        self.errors.append((str(path.relative_to(ROOT)), msg))

    def emit(self) -> int:
        for path, msg in self.errors:
            if self.fmt == "github":
                print(f"::error file={path}::{msg}")
            else:
                print(f"{path}: {msg}")
        status = "OK" if not self.errors else "FAIL"
        print(f"check_env_config: {status} - {len(self.errors)} violation(s) in {self.checked} service(s)")
        return 1 if self.errors else 0


def load(path: pathlib.Path) -> dict:
    try:
        docs = [d for d in yaml.safe_load_all(path.read_text(encoding="utf-8")) if d]
    except yaml.YAMLError as err:
        raise SystemExit(f"{path}: invalid YAML: {err}")
    merged: dict = {}
    for d in docs:  # Spring allows several documents; later ones override
        if isinstance(d, dict):
            merged = deep_merge(merged, d)
    return merged


def deep_merge(a: dict, b: dict) -> dict:
    out = dict(a)
    for k, v in b.items():
        out[k] = deep_merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) else v
    return out


def get(d: dict, dotted: str):
    """Spring-style lookup: nested keys or flattened 'a.b' keys at any level."""
    parts = dotted.split(".")
    for i in range(len(parts), 0, -1):
        head, rest = ".".join(parts[:i]), parts[i:]
        if isinstance(d, dict) and head in d:
            return get(d[head], ".".join(rest)) if rest else d[head]
    return None


def resolve(value):
    """Literal value or the default of a ${ENV:default} placeholder (None when there is no default)."""
    if isinstance(value, str):
        m = PLACEHOLDER.match(value.strip())
        if m:
            return yaml.safe_load(m.group(2)) if m.group(2) is not None else None
    return value


def as_bool(value) -> bool | None:
    value = resolve(value)
    if isinstance(value, bool):
        return value
    if isinstance(value, str) and value.lower() in ("true", "false"):
        return value.lower() == "true"
    return None


def as_float(value) -> float | None:
    value = resolve(value)
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


# ------------------------------------------------------------------------------------------------ Java
def check_java(svc_dir: pathlib.Path, rep: Report) -> None:
    res = svc_dir / "src/main/resources"
    base_file = res / "application.yml"
    if not base_file.exists():
        rep.error(svc_dir, "missing src/main/resources/application.yml")
        return
    base = load(base_file)
    for env in ENVS:
        f = res / f"application-{env}.yml"
        if not f.exists():
            rep.error(f, f"missing Spring profile application-{env}.yml")
            continue
        eff = deep_merge(base, load(f))
        docs = as_bool(get(eff, "at.api-docs.enabled"))
        docs = True if docs is None else docs  # the platform default is on
        if docs != DOCS_ON[env]:
            rep.error(f, f"at.api-docs.enabled is {str(docs).lower()}; API reference pages must be "
                         f"{'on' if DOCS_ON[env] else 'off'} in {env}")
        if env == "prod":
            for key in ("springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled"):
                if as_bool(get(eff, key)) is not False:
                    rep.error(f, f"{key} must be false in prod")
        prob = as_float(get(eff, "management.tracing.sampling.probability"))
        if prob is None or abs(prob - SAMPLING[env]) > 1e-9:
            rep.error(f, f"management.tracing.sampling.probability resolves to {prob}; {env} samples {SAMPLING[env]}")


# ------------------------------------------------------------------------------------------------ Go
def check_go(svc_dir: pathlib.Path, rep: Report) -> None:
    for env in ENVS:
        f = svc_dir / "config" / f"{env}.yaml"
        if not f.exists():
            rep.error(svc_dir, f"missing config/{env}.yaml")
            continue
        cfg = load(f)
        docs = as_bool(cfg.get("docsEnabled"))
        if docs != DOCS_ON[env]:
            rep.error(f, f"docsEnabled is {docs}; API reference pages must be {'on' if DOCS_ON[env] else 'off'} in {env}")
        ratio = as_float(get(cfg, "telemetry.sampleRatio"))
        if ratio is None or abs(ratio - SAMPLING[env]) > 1e-9:
            rep.error(f, f"telemetry.sampleRatio is {ratio}; {env} samples {SAMPLING[env]}")


# ------------------------------------------------------------------------------------------------ Helm
def prod_min_replicas() -> int:
    """2 for the HA profile; 1 when prod runs the lean single-node profile (one node cannot spread two replicas)."""
    sizing = pathlib.Path(__file__).resolve().parents[2] / "infra" / "tofu" / "envs" / "prod" / "sizing.tf"
    try:
        lean = re.search(r'^\s*node_size\s*=\s*"lean"', sizing.read_text(), re.M) is not None
    except OSError:
        lean = False
    return 1 if lean else 2


def tolerates_spot(values: dict) -> bool:
    return any(isinstance(t, dict) and t.get("key") == SPOT_KEY for t in values.get("tolerations") or [])


def check_helm(chart: pathlib.Path, rep: Report) -> None:
    base_file = chart / "values.yaml"
    if not base_file.exists():
        rep.error(chart, "missing deploy/helm/values.yaml")
        return
    base = load(base_file)
    for f in [base_file] + sorted(chart.glob("values-*.yaml")):
        tag = str(get(load(f), "image.tag") or "")
        if tag == "latest":
            rep.error(f, "image.tag must never be latest (Trivy KSV-0013; the deploy pipeline writes the git sha)")
    for env in DEPLOYED:
        f = chart / f"values-{env}.yaml"
        if not f.exists():
            rep.error(chart, f"missing deploy/helm/values-{env}.yaml")
            continue
        own = load(f)
        v = deep_merge(base, own)
        if own.get("env") != env:
            rep.error(f, f"env is {own.get('env')!r}; must be {env!r}")
        sampling = as_float(get(v, "telemetry.sampling"))
        if sampling is None or abs(sampling - SAMPLING[env]) > 1e-9:
            rep.error(f, f"telemetry.sampling is {sampling}; {env} samples {SAMPLING[env]}")
        res = v.get("resources") or {}
        req, lim = res.get("requests") or {}, res.get("limits") or {}
        if not req.get("cpu") or not req.get("memory") or not lim.get("memory"):
            rep.error(f, "resources must set requests.cpu, requests.memory and limits.memory")
        hpa = v.get("autoscaling") or {}
        replicas = int(v.get("replicas") or 0)
        if env == "dev":
            if hpa.get("enabled"):
                rep.error(f, "dev runs a fixed single replica: autoscaling.enabled must be false")
            if replicas != 1:
                rep.error(f, f"dev runs 1 replica (found {replicas})")
            if not tolerates_spot(v):
                rep.error(f, f"dev workloads must tolerate the spot pool ({SPOT_KEY})")
        elif env == "uat":
            if not tolerates_spot(v):
                rep.error(f, f"uat workloads must tolerate the spot pool ({SPOT_KEY})")
        else:
            if tolerates_spot(v):
                rep.error(f, f"prod must not tolerate the spot pool ({SPOT_KEY}); spot capacity is for dev/uat")
            floor = int(hpa.get("min") or 0) if hpa.get("enabled") else replicas
            need = prod_min_replicas()
            if floor < need:
                rep.error(f, f"prod needs at least {need} replicas (HPA min or replicas; found {floor})")


# ------------------------------------------------------------------------------------------------ Stripe (S282)
STRIPE_SECRETS = {
    "gateway-bff": ["STRIPE_PUBLISHABLE_KEY"],
    "payments": ["STRIPE_SECRET_KEY", "STRIPE_WEBHOOK_SECRET"],
}


def chart_secret_keys(values: pathlib.Path) -> set[str]:
    """Keys listed in the chart's '# Secret keys expected at …' comment (what the ExternalSecret extracts)."""
    keys: set[str] = set()
    listing = False
    for line in values.read_text(encoding="utf-8").splitlines():
        if line.startswith("# Secret keys expected"):
            listing = True
            continue
        if listing:
            m = re.match(r"^#\s+-\s+([A-Z0-9_]+)", line)
            if not m:
                break
            keys.add(m.group(1))
    return keys


def check_stripe(svc_dir: pathlib.Path, rep: Report) -> None:
    name = svc_dir.name
    if name not in STRIPE_SECRETS:
        return
    values = svc_dir / "deploy/helm/values.yaml"
    if values.exists():
        missing = [k for k in STRIPE_SECRETS[name] if k not in chart_secret_keys(values)]
        if missing:
            rep.error(values, f"secret store keys must list {', '.join(missing)} (Stripe keys never live in values or config)")
    if name == "gateway-bff":
        for env in ENVS:
            f = svc_dir / "config" / f"{env}.yaml"
            if f.exists() and str(get(load(f), "stripe.publishableKey") or "").strip():
                rep.error(f, "stripe.publishableKey must be empty: the key comes only from STRIPE_PUBLISHABLE_KEY")
        return
    res = svc_dir / "src/main/resources"
    base = load(res / "application.yml") if (res / "application.yml").exists() else {}
    for env in DEPLOYED:
        f = res / f"application-{env}.yml"
        if not f.exists():
            continue
        eff = deep_merge(base, load(f))
        for key, var in (("secret-key", "STRIPE_SECRET_KEY"), ("webhook-secret", "STRIPE_WEBHOOK_SECRET")):
            raw = get(eff, f"at.payments.stripe.{key}")
            m = PLACEHOLDER.match(str(raw or "").strip())
            if not m or m.group(1) != var or m.group(2) is not None:
                rep.error(f, f"at.payments.stripe.{key} must be ${{{var}}} with no default in {env} (found {raw!r})")
        live = as_bool(get(eff, "at.payments.stripe.require-live-key")) is True
        if live != (env == "prod"):
            rep.error(f, f"at.payments.stripe.require-live-key must be {str(env == 'prod').lower()} in {env}")


def duration_seconds(value) -> float | None:
    """'25s', '1500ms', '2m' (or a ${VAR:default} placeholder's default) in seconds."""
    v = resolve(value)
    if v is None:
        return None
    m = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*(ms|s|m|h)?\s*", str(v))
    if not m:
        return None
    return float(m.group(1)) * {"ms": 0.001, "s": 1, "m": 60, "h": 3600, None: 1}[m.group(2)]


def check_timeouts(rep: Report) -> None:
    bff = ROOT / "go/services/gateway-bff/config"
    orders = ROOT / "java/services/orders/src/main/resources"
    runs = ROOT / "java/services/delivery-runs/src/main/resources"
    if not (bff.is_dir() and orders.is_dir() and runs.is_dir()):
        return
    base_orders, base_runs = load(orders / "application.yml"), load(runs / "application.yml")
    for env in ENVS:
        cfg = load(bff / f"{env}.yaml")
        o_env = orders / f"application-{env}.yml"
        r_env = runs / f"application-{env}.yml"
        o = deep_merge(base_orders, load(o_env)) if o_env.exists() else base_orders
        r = deep_merge(base_runs, load(r_env)) if r_env.exists() else base_runs
        pairs = [("orders", get(o, "at.orders.checkout-response-timeout"), "orders' checkout-response-timeout"),
                 ("deliveryRuns", get(r, "at.delivery-runs.orders.timeout"), "delivery-runs' orders timeout")]
        for name, downstream, what in pairs:
            up = get(cfg, f"upstreams.{name}") or {}
            slow = duration_seconds(up.get("slowTimeout", up.get("timeout")))
            limit = duration_seconds(downstream)
            if slow is None or limit is None:
                rep.error(bff / f"{env}.yaml", f"upstreams.{name}.slowTimeout or {what} is missing")
            elif slow <= limit:
                rep.error(bff / f"{env}.yaml", f"upstreams.{name}.slowTimeout ({slow:g}s) must be above {what} "
                                              f"({limit:g}s)")


def check_plan_fees(rep: Report) -> None:
    seller = ROOT / "java/services/seller"
    identity = ROOT / "java/services/identity/src/main/resources"
    res = seller / "src/main/resources"
    helm = seller / "deploy/helm"
    if not (res.is_dir() and identity.is_dir() and helm.is_dir()):
        return
    base = load(res / "application.yml")
    values = load(helm / "values.yaml")
    for env in ENVS:
        env_file = res / f"application-{env}.yml"
        profile = deep_merge(base, load(env_file)) if env_file.exists() else base
        on = as_bool(get(profile, "at.seller.billing.collect-from-earnings"))
        helm_on = None
        if env in DEPLOYED:
            merged = deep_merge(values, load(helm / f"values-{env}.yaml"))
            env_vars = merged.get("extraEnv") or {}
            helm_on = as_bool(str(env_vars.get("COLLECT_PLAN_FEES_FROM_EARNINGS", "")).lower() or None)
            if helm_on is not False and not env_vars.get("PAYMENTS_URL"):
                rep.error(helm / f"values-{env}.yaml", "plan fees from earnings can be on but extraEnv.PAYMENTS_URL is not set")
        if env in ("local", "dev") and on is not True:
            rep.error(env_file if env_file.exists() else res / "application.yml",
                      "at.seller.billing.collect-from-earnings must default to true in the " + env + " profile (S298)")
        if env == "dev" and helm_on is not True:
            rep.error(helm / "values-dev.yaml", "extraEnv.COLLECT_PLAN_FEES_FROM_EARNINGS must be \"true\" in dev (S298)")
        if on or helm_on:
            id_cfg = load(identity / f"application-{env}.yml")
            clients = get(id_cfg, "at.identity.clients") or get(id_cfg, "identity.clients") or []
            scopes = next((c.get("scopes") or [] for c in clients if isinstance(c, dict) and c.get("client-id") == "seller-service"), None)
            if scopes is None or "payments:plan-fees" not in scopes:
                rep.error(identity / f"application-{env}.yml",
                          "the seller-service client needs the payments:plan-fees scope while plan fees may be paid from earnings")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--format", choices=["text", "github"], default="text")
    ap.add_argument("--service", action="append", default=[])
    args = ap.parse_args()
    rep = Report(args.format)
    services = sorted(ROOT.glob("java/services/*/pom.xml")) + sorted(ROOT.glob("go/services/*/go.mod"))
    for marker in services:
        svc_dir = marker.parent
        if args.service and svc_dir.name not in args.service:
            continue
        rep.checked += 1
        (check_java if marker.name == "pom.xml" else check_go)(svc_dir, rep)
        check_stripe(svc_dir, rep)
        chart = svc_dir / "deploy/helm"
        if chart.is_dir():
            check_helm(chart, rep)
        else:
            rep.error(svc_dir, "missing deploy/helm chart")
    if not args.service:
        check_timeouts(rep)
        check_plan_fees(rep)
    if args.service and rep.checked == 0:
        print(f"no such service: {', '.join(args.service)}", file=sys.stderr)
        return 2
    return rep.emit()


if __name__ == "__main__":
    sys.exit(main())
