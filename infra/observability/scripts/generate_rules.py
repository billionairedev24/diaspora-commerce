#!/usr/bin/env python3
"""Generates infra/observability/rules/slo-*.yaml (multi-window, multi-burn-rate SLO rules) and the
PrometheusRule custom resources in infra/k8s/platform/observability/prometheus-rules/ from every rule file.

  python3 infra/observability/scripts/generate_rules.py           # write
  python3 infra/observability/scripts/generate_rules.py --check   # fail if generated files are stale (CI / make helm-lint)

Burn-rate policy (Google SRE workbook, 30-day budget):
  page   : 1h & 5m  > 14.4 x budget   (2% of budget in 1h)   or  6h & 30m > 6 x budget (5% in 6h)
  ticket : 1d & 2h  > 3 x budget      (10% in 1d)            or  3d & 6h  > 1 x budget (10% in 3d)
"""
import pathlib
import sys

import yaml  # PyYAML

ROOT = pathlib.Path(__file__).resolve().parents[3]
RULES = ROOT / "infra" / "observability" / "rules"
CRS = ROOT / "infra" / "k8s" / "platform" / "observability" / "prometheus-rules"
RUNBOOK = "https://github.com/billionairedev24/diaspora-commerce/blob/main/docs/runbooks/{}.md"
WINDOWS = ["5m", "30m", "1h", "2h", "6h", "1d", "3d"]

# Metric contract the owning services must expose (documented in infra/local/README.md#metrics-contract).
SLOS = [
    {
        "id": "checkout", "alert": "CheckoutSuccessBudgetBurn", "owner": "orders", "objective": 0.999,
        "summary": "Checkout success SLO (99.9%) is burning its error budget",
        "bad": 'sum by (env) (rate(checkout_attempts_total{{outcome="failure"}}[{w}]))',
        "total": 'sum by (env) (rate(checkout_attempts_total{{outcome=~"success|failure"}}[{w}]))',
    },
    {
        "id": "search_latency", "alert": "SearchLatencyBudgetBurn", "owner": "search", "objective": 0.95,
        "summary": "Search p95 < 150 ms SLO is burning its error budget",
        "good": 'sum by (env) (rate(http_server_request_duration_seconds_bucket{{service="search", route=~"GET /v1/(search|suggest)", le="0.15"}}[{w}]))',
        "total": 'sum by (env) (rate(http_server_request_duration_seconds_count{{service="search", route=~"GET /v1/(search|suggest)"}}[{w}]))',
    },
    {
        "id": "payment_webhook_lag", "alert": "PaymentWebhookLagBudgetBurn", "owner": "payments", "objective": 0.99,
        "summary": "Payment webhooks are not processed within 60 s often enough",
        "good": 'sum by (env) (rate(payments_webhook_lag_seconds_bucket{{le=~"60(\\\\.0)?"}}[{w}]))',
        "total": 'sum by (env) (rate(payments_webhook_lag_seconds_count[{w}]))',
    },
    {
        "id": "payout_on_time", "alert": "PayoutOnTimeBudgetBurn", "owner": "payments", "objective": 0.995,
        "summary": "Seller payouts on-time SLO (99.5%) is burning its error budget",
        "bad": 'sum by (env) (rate(payouts_completed_total{{on_time="false"}}[{w}]))',
        "total": 'sum by (env) (rate(payouts_completed_total[{w}]))',
    },
    {
        "id": "notification_delivery", "alert": "NotificationDeliveryBudgetBurn", "owner": "notifications", "objective": 0.99,
        "summary": "Notifications are not delivered within 30 s often enough",
        "good": 'sum by (env) (rate(notifications_delivery_duration_seconds_bucket{{le=~"30(\\\\.0)?"}}[{w}]))',
        "total": 'sum by (env) (rate(notifications_delivery_duration_seconds_count[{w}]))',
    },
]


def ratio(s, w):
    total = s["total"].format(w=w)
    if "bad" in s:
        return f'({s["bad"].format(w=w)})\n/\n({total} > 0)'
    return f'1 - (\n  ({s["good"].format(w=w)})\n  /\n  ({total} > 0)\n)'


def slo_file(s):
    budget = round(1 - s["objective"], 6)
    lbl = {"slo": s["id"]}
    rec = [{"record": "slo:sli_error:ratio_rate" + w, "expr": ratio(s, w), "labels": lbl} for w in WINDOWS]
    rec.append({"record": "slo:sli_error:ratio_rate30d",
                "expr": f'avg_over_time(slo:sli_error:ratio_rate1h{{slo="{s["id"]}"}}[30d])', "labels": lbl})
    rec.append({"record": "slo:error_budget:ratio", "expr": f"vector({budget})", "labels": lbl})
    rec.append({"record": "slo:objective:ratio", "expr": f"vector({s['objective']})", "labels": lbl})

    def r(w):
        return f'slo:sli_error:ratio_rate{w}{{slo="{s["id"]}"}}'

    def burn(long, short, factor):
        return f"({r(long)} > ({factor} * {budget}) and {r(short)} > ({factor} * {budget}))"

    common_ann = {
        "runbook_url": RUNBOOK.format(s["alert"]),
        "dashboard": "/d/at-business-slo",
    }
    alerts = [
        {
            "alert": s["alert"],
            "expr": burn("1h", "5m", 14.4) + "\nor\n" + burn("6h", "30m", 6),
            "for": "2m",
            "labels": {"severity": "page", "slo": s["id"], "team": s["owner"]},
            "annotations": dict(common_ann, summary=s["summary"] + " (fast burn)",
                                description=f'{{{{ $labels.env }}}}: error ratio {{{{ $value | humanizePercentage }}}} — at this rate the 30-day budget ({budget:g}) is gone in < 2 days.'),
        },
        {
            "alert": s["alert"],
            "expr": burn("1d", "2h", 3) + "\nor\n" + burn("3d", "6h", 1),
            "for": "15m",
            "labels": {"severity": "ticket", "slo": s["id"], "team": s["owner"]},
            "annotations": dict(common_ann, summary=s["summary"] + " (slow burn)",
                                description=f'{{{{ $labels.env }}}}: error ratio {{{{ $value | humanizePercentage }}}} — the 30-day budget ({budget:g}) will be exhausted before the window ends.'),
        },
    ]
    return {"groups": [
        {"name": f"slo-{s['id']}-recording", "interval": "30s", "rules": rec},
        {"name": f"slo-{s['id']}-alerts", "rules": alerts},
    ]}


def _repr_str(dumper, data):
    style = "|" if "\n" in data else None
    return dumper.represent_scalar("tag:yaml.org,2002:str", data, style=style)


yaml.SafeDumper.add_representer(str, _repr_str)
yaml.SafeDumper.ignore_aliases = lambda *_: True


def dump(obj):
    return yaml.safe_dump(obj, sort_keys=False, width=200)


def to_cr(name, rules_doc):
    return {
        "apiVersion": "monitoring.coreos.com/v1",
        "kind": "PrometheusRule",
        "metadata": {
            "name": f"afrotransact-{name}",
            "namespace": "observability",
            "labels": {"release": "kube-prometheus-stack", "app.kubernetes.io/part-of": "afrotransact"},
        },
        "spec": rules_doc,
    }


def main():
    check = "--check" in sys.argv
    outputs = {}
    header = "# GENERATED by infra/observability/scripts/generate_rules.py — do not edit by hand.\n"
    for s in SLOS:
        outputs[RULES / f"slo-{s['id'].replace('_', '-')}.yaml"] = header + dump(slo_file(s))
    # Every rule file (generated + hand-written) also becomes a PrometheusRule CR for kube-prometheus-stack.
    sources = sorted(set(RULES.glob("*.yaml")) | set(outputs))
    for f in sources:
        text = outputs.get(f) or f.read_text()
        doc = yaml.safe_load(text)
        cr_header = f"# GENERATED from infra/observability/rules/{f.name} by generate_rules.py — do not edit by hand.\n"
        outputs[CRS / f.name] = cr_header + dump(to_cr(f.stem, doc))
    stale = []
    for path, text in outputs.items():
        if check:
            if not path.exists() or path.read_text() != text:
                stale.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            print(f"wrote {path.relative_to(ROOT)}")
    if check and stale:
        print("stale generated rule files (run make rules):\n  " + "\n  ".join(stale))
        sys.exit(1)


if __name__ == "__main__":
    main()
