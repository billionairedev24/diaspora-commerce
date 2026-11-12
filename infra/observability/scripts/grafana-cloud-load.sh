#!/usr/bin/env bash
# Loads the alert rules and dashboards of this repository into the Grafana Cloud stack (rules into the hosted Prometheus/Mimir
# ruler with mimirtool, dashboards through the Grafana HTTP API with curl). Idempotent: run it again after every change.
#
#   infra/observability/scripts/grafana-cloud-load.sh check              offline: lint the rule files, validate the dashboard JSON (no credentials)
#   infra/observability/scripts/grafana-cloud-load.sh rules              sync the launch rule files to the ruler (namespace = file name; `sync` also deletes namespaces not listed, so
#                                                                        do not hand-create rules in this stack)
#   infra/observability/scripts/grafana-cloud-load.sh dashboards         upload the launch dashboards to the folder "AfroTransact"
#   infra/observability/scripts/grafana-cloud-load.sh lokirules          OPTIONAL: sync infra/observability/loki-rules/*.yaml to the Loki ruler (UNVERIFIED against Grafana Cloud)
#   infra/observability/scripts/grafana-cloud-load.sh alertmanager       OPTIONAL: load alertmanager.yaml into the Mimir Alertmanager
#   infra/observability/scripts/grafana-cloud-load.sh all                rules + dashboards
#   (alert e-mail recipients: ONE list in grafana-cloud/alert-recipients.env, or ALERT_EMAIL_RECIPIENTS in the environment)
#   DRY_RUN=1 ...                                                          print what would be sent, send nothing
#
# Credentials come from the environment, never from this repository (the owner's machine, or a CI secret):
#   GRAFANA_URL            default https://afrotransact.grafana.net   (the stack URL, not a secret)
#   GRAFANA_SA_TOKEN       dashboards: a Grafana service-account token (Administration > Users and access > Service accounts, role Editor)
#   MIMIR_ADDRESS          rules: the Hosted Prometheus URL WITHOUT /api/prom, e.g. https://prometheus-prod-NN-<region>.grafana.net
#   MIMIR_TENANT_ID        rules: the numeric Hosted Prometheus "Username / Instance ID"
#   LOKI_ADDRESS / LOKI_TENANT_ID   lokirules only: the Hosted logs URL WITHOUT /loki/api/v1/push, and its numeric user id (token: MIMIR_API_KEY)
#   MIMIR_API_KEY          rules: an access-policy token with rules:read + rules:write (NOT the Alloy token, which has metrics:write only)
#   PROM_DS_UID            dashboards: uid of the Prometheus data source (default grafanacloud-prom, UNVERIFIED: see Connections > Data sources)
#   LOKI_DS_UID            dashboards: uid of the Loki data source (default grafanacloud-logs, UNVERIFIED)
#   DEFAULT_ENV            dashboards: value the `env` variable starts with (default prod)
# Needs: python3 (PyYAML), curl for dashboards, mimirtool for rules (https://grafana.com/docs/mimir/latest/manage/tools/mimirtool/).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
obs="$root/infra/observability"
GRAFANA_URL="${GRAFANA_URL:-https://afrotransact.grafana.net}"
PROM_DS_UID="${PROM_DS_UID:-grafanacloud-prom}"
LOKI_DS_UID="${LOKI_DS_UID:-grafanacloud-logs}"
DEFAULT_ENV="${DEFAULT_ENV:-prod}"
DRY_RUN="${DRY_RUN:-}"

# What the launch loads. The SLO files are not loaded yet: their metrics (checkout_attempts_total, payments_webhook_lag_seconds,
# payouts_completed_total, notifications_delivery_duration_seconds) are in the metrics contract but not in the code.
# elasticsearch.yaml and postgres.yaml need exporters that do not run on the lean platform.
RULE_FILES=(services.yaml kafka.yaml inventory.yaml payments-transfer-recovery.yaml backup.yaml catalog-tax-gate.yaml checkout-tax-estimate.yaml launch.yaml)
LOKI_RULE_FILES=(backup.yaml)   # LogQL alerts on the backup_metrics log line (infra/observability/loki-rules)
DASHBOARDS=(launch-platform.json launch-payments.json platform-overview.json jvm.json kafka-consumer-lag.json)

# Renders alertmanager.yaml with the recipient list; fails on a placeholder/invalid address. Prints the rendered file path.
render_alertmanager() {
  need python3
  local out; out="$(mktemp)"
  python3 - "$obs/grafana-cloud" "$out" <<'PY'
import os, re, sys, pathlib, yaml
d, out = pathlib.Path(sys.argv[1]), sys.argv[2]
rec = os.environ.get("ALERT_EMAIL_RECIPIENTS")
if not rec:
    for line in (d / "alert-recipients.env").read_text().splitlines():
        if line.startswith("ALERT_EMAIL_RECIPIENTS="):
            rec = line.split("=", 1)[1].strip()
addrs = [a.strip() for a in (rec or "").split(",") if a.strip()]
assert addrs, "ALERT_EMAIL_RECIPIENTS is empty"
for a in addrs:
    assert re.fullmatch(r"[^@\s]+@[^@\s]+\.[A-Za-z]{2,}", a), f"invalid recipient {a}"
    assert not re.search(r"example\.(invalid|com|org)|\.invalid$|\.test$", a, re.I), f"placeholder recipient {a}"
text = (d / "alertmanager.yaml").read_text().replace("__ALERT_EMAIL_RECIPIENTS__", ",".join(addrs))
assert "__ALERT" not in text and "example.invalid" not in text.lower(), "placeholder left in alertmanager routing"
doc = yaml.safe_load(text)
rcv = {r["name"]: r for r in doc["receivers"]}
assert rcv["ops-email"]["email_configs"][0]["to"] == ",".join(addrs)
assert not rcv["trial-quiet"].get("email_configs"), "trial-quiet must stay silent"
order = [(r["receiver"], tuple(r["matchers"])) for r in doc["route"]["routes"]]
assert ("ops-email", ('env="prod"',)) in order, "prod must route to email"
assert ("ops-email", ('env="trial"', 'severity="page"')) in order, "trial page must route to email"
assert order.index(("ops-email", ('env="trial"', 'severity="page"'))) < order.index(("trial-quiet", ('env="trial"',))), "trial routes out of order"
pathlib.Path(out).write_text(text)
print(f"alertmanager routing ok: {len(addrs)} recipients", file=sys.stderr)
PY
  echo "$out"
}

die() { echo "error: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || die "$1 not found"; }
req() { [[ -n "${!1:-}" ]] || die "$1 is not set (see the header of this script)"; }

check() {
  need python3
  python3 - "$obs" "${RULE_FILES[@]}" <<'PY'
import sys, yaml, pathlib
obs, files = pathlib.Path(sys.argv[1]), sys.argv[2:]
seen = set()
for f in files:
    doc = yaml.safe_load((obs / "rules" / f).read_text())
    for g in doc["groups"]:
        for r in g["rules"]:
            name = r.get("alert") or r.get("record")
            assert name, (f, g["name"])
            if "alert" in r:
                assert r["labels"].get("severity") in ("page", "ticket"), (f, name, "severity")
                assert name not in seen, ("duplicate alert", name)
                seen.add(name)
print(f"rules ok: {len(seen)} alerts in {len(files)} files")
PY
  local d
  for d in "${DASHBOARDS[@]}"; do python3 -m json.tool "$obs/grafana/dashboards/$d" >/dev/null || die "invalid JSON: $d"; done
  echo "dashboards ok: ${#DASHBOARDS[@]} files"
  rm -f "$(render_alertmanager)"
  if command -v mimirtool >/dev/null; then
    local args=() f; for f in "${RULE_FILES[@]}"; do args+=("$obs/rules/$f"); done
    mimirtool rules lint "${args[@]}" && mimirtool rules check "${args[@]}" && echo "mimirtool lint/check ok"
  else
    echo "note: mimirtool not installed, PromQL not parsed (install it, or run promtool check rules)"
  fi
}

rules() {
  need mimirtool; req MIMIR_ADDRESS; req MIMIR_TENANT_ID; req MIMIR_API_KEY
  local args=() f; for f in "${RULE_FILES[@]}"; do args+=("$obs/rules/$f"); done
  if [[ -n "$DRY_RUN" ]]; then
    echo "DRY RUN: mimirtool rules diff --address=$MIMIR_ADDRESS --id=$MIMIR_TENANT_ID --key=*** --rule-files=${RULE_FILES[*]} (one rule namespace per file)"; return
  fi
  # `sync` makes the ruler equal to the files (removes rules deleted from git). The stack is ours alone.
  mimirtool rules sync --address="$MIMIR_ADDRESS" --id="$MIMIR_TENANT_ID" --key="$MIMIR_API_KEY" --rule-files="$(IFS=,; echo "${args[*]}")" 2>&1 \
    || die "rules sync failed (token needs rules:write; address must not end with /api/prom)"
  echo "rules synced to $MIMIR_ADDRESS"
}

lokirules() {
  need mimirtool; req LOKI_ADDRESS; req LOKI_TENANT_ID; req MIMIR_API_KEY
  local args=() f; for f in "${LOKI_RULE_FILES[@]}"; do args+=("$obs/loki-rules/$f"); done
  if [[ -n "$DRY_RUN" ]]; then echo "DRY RUN: mimirtool rules sync --backend=loki --address=$LOKI_ADDRESS --rule-files=${LOKI_RULE_FILES[*]}"; return; fi
  mimirtool rules sync --backend=loki --address="$LOKI_ADDRESS" --id="$LOKI_TENANT_ID" --key="$MIMIR_API_KEY" --rule-files="$(IFS=,; echo "${args[*]}")"
}

alertmanager() {
  need mimirtool; req MIMIR_ADDRESS; req MIMIR_TENANT_ID; req MIMIR_API_KEY
  local cfg; cfg="$(render_alertmanager)"
  if [[ -n "$DRY_RUN" ]]; then echo "DRY RUN: mimirtool alertmanager load $cfg"; cat "$cfg"; return; fi
  mimirtool alertmanager load "$cfg" --address="$MIMIR_ADDRESS" --id="$MIMIR_TENANT_ID" --key="$MIMIR_API_KEY"
}

dashboards() {
  need python3; need curl; [[ -n "$DRY_RUN" ]] || req GRAFANA_SA_TOKEN
  local d payload folder_uid="afrotransact"
  if [[ -z "$DRY_RUN" ]]; then
    # Folder "AfroTransact" (create if missing; a 412/409 means it exists).
    curl -sS -o /dev/null -X POST "$GRAFANA_URL/api/folders" -H "Authorization: Bearer $GRAFANA_SA_TOKEN" -H 'Content-Type: application/json' \
      -d "{\"uid\":\"$folder_uid\",\"title\":\"AfroTransact\"}" || true
  fi
  for d in "${DASHBOARDS[@]}"; do
    # Point the data-source uids at this stack, make `env` start on DEFAULT_ENV (trial noise never shows by default), wrap for the API.
    payload="$(python3 - "$obs/grafana/dashboards/$d" "$PROM_DS_UID" "$LOKI_DS_UID" "$DEFAULT_ENV" "$folder_uid" <<'PY'
import json, sys
path, prom, loki, env, folder = sys.argv[1:6]
raw = open(path).read().replace('"uid": "prometheus"', f'"uid": "{prom}"').replace('"uid": "loki"', f'"uid": "{loki}"')
dash = json.loads(raw)
for v in dash.get("templating", {}).get("list", []):
    if v.get("name") == "env":
        v["current"] = {"selected": True, "text": [env], "value": [env]}
        v["includeAll"] = False
        v["allValue"] = None
dash["editable"] = False
print(json.dumps({"dashboard": dash, "folderUid": folder, "overwrite": True, "message": "grafana-cloud-load.sh"}))
PY
)"
    if [[ -n "$DRY_RUN" ]]; then echo "DRY RUN: POST $GRAFANA_URL/api/dashboards/db  $d ($(wc -c <<<"$payload") bytes)"; continue; fi
    curl -sS -f -X POST "$GRAFANA_URL/api/dashboards/db" -H "Authorization: Bearer $GRAFANA_SA_TOKEN" -H 'Content-Type: application/json' \
      -d "$payload" >/dev/null || die "upload failed for $d (token role, folder uid or data-source uid wrong?)"
    echo "uploaded $d"
  done
}

case "${1:-}" in
  check) check ;;
  rules) check; rules ;;
  dashboards) dashboards ;;
  alertmanager) alertmanager ;;
  lokirules) lokirules ;;
  all) check; rules; dashboards ;;
  *) sed -n '2,25p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
