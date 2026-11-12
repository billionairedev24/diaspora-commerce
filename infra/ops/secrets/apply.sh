#!/usr/bin/env bash
# Apply SOPS/age-encrypted Kubernetes Secrets to the production cluster. Secrets are NOT synced by Argo CD (it cannot decrypt
# them and prod is manual-sync anyway): this is the one place they enter the cluster. Idempotent; run before the first sync and
# again whenever a secret changes (then restart the affected Deployment: pods read envFrom at start).
#
#   infra/ops/secrets/apply.sh [DIR]          DIR defaults to infra/ops/secrets/prod (*.yaml, SOPS-encrypted)
#   SOPS_AGE_KEY_FILE=~/.config/sops/age/keys.txt infra/ops/secrets/apply.sh
#   infra/ops/secrets/apply.sh --plain DIR    DIR holds UNENCRYPTED files (a fresh render in a temp dir); never commit those
#
# What it does: creates the namespaces (with the labels the NetworkPolicies match on), refuses Secrets that still hold an empty
# value for a MANDATORY key, then `sops -d | kubectl apply`. Needs: kubectl (context = the production cluster), sops, python3.
# Rendering the files in the first place: infra/ovh-vps/secrets.sh with PROFILE=prod SECRETS_OUT (infra/ops/secrets/README.md).
set -euo pipefail

plain=false
if [[ "${1:-}" == "--plain" ]]; then plain=true; shift; fi
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dir="${1:-$root/prod}"
[[ -d "$dir" ]] || { echo "error: $dir is not a directory" >&2; exit 1; }
command -v kubectl >/dev/null || { echo "error: kubectl not found" >&2; exit 1; }
$plain || command -v sops >/dev/null || { echo "error: sops not found (https://github.com/getsops/sops)" >&2; exit 1; }

ctx="$(kubectl config current-context)"
echo "kubectl context: $ctx"
if [[ "${CONFIRM_CONTEXT:-}" != "$ctx" ]]; then
  echo "refusing: export CONFIRM_CONTEXT='$ctx' to confirm this is the cluster you mean" >&2
  exit 1
fi

# Namespaces: service namespace carries part-of=afrotransact; platform namespaces carry afrotransact-platform. Argo CD later
# adopts them (managedNamespaceMetadata / the namespaces component), labels are identical.
for n in afrotransact-prod; do
  kubectl get ns "$n" >/dev/null 2>&1 || kubectl create ns "$n"
  kubectl label ns "$n" app.kubernetes.io/part-of=afrotransact afrotransact.com/env=prod --overwrite
done
for n in data kafka temporal ops observability ingress; do
  kubectl get ns "$n" >/dev/null 2>&1 || kubectl create ns "$n"
  kubectl label ns "$n" app.kubernetes.io/part-of=afrotransact-platform --overwrite
done

# Keys that must not be empty in production (an empty value starts the pod and fails later, at the worst moment).
MANDATORY='afrotransact-prod/payments-secrets:STRIPE_SECRET_KEY,STRIPE_WEBHOOK_SECRET
afrotransact-prod/seller-secrets:STRIPE_SECRET_KEY,STRIPE_CONNECT_WEBHOOK_SECRET
afrotransact-prod/gateway-bff-secrets:STRIPE_PUBLISHABLE_KEY,SESSION_SIGNING_KEY,OIDC_CLIENT_SECRET
afrotransact-prod/notifications-secrets:RESEND_API_KEY
afrotransact-prod/identity-secrets:SIGNING_KEY_PEM,IDENTITY_DATA_KEY,BOOTSTRAP_ADMIN_PASSWORD
afrotransact-prod/ai-gateway-secrets:OPENROUTER_API_KEY
ingress/cloudflared:token
ops/pg-backup:AGE_RECIPIENT,BACKUP_REMOTE,RCLONE_CONFIG_BACKUP_ACCESS_KEY_ID,RCLONE_CONFIG_BACKUP_SECRET_ACCESS_KEY'

check_mandatory() { # check_mandatory < decrypted-yaml ; prints the problems, exit 1 when any
  MANDATORY="$MANDATORY" python3 -c '
import base64, os, sys, yaml
doc = yaml.safe_load(sys.stdin)
if not doc or doc.get("kind") != "Secret":
    sys.exit(0)
ref = doc["metadata"].get("namespace", "") + "/" + doc["metadata"]["name"]
data = {k: base64.b64decode(v).decode("utf-8", "replace") for k, v in (doc.get("data") or {}).items()}
data.update(doc.get("stringData") or {})
bad = []
for line in os.environ["MANDATORY"].splitlines():
    r, keys = line.split(":")
    if r == ref:
        bad += [k for k in keys.split(",") if not data.get(k, "").strip()]
if bad:
    print(ref + ": empty mandatory key(s): " + ", ".join(bad), file=sys.stderr)
    sys.exit(1)
'
}

fail=0
shopt -s nullglob
files=("$dir"/*.yaml)
((${#files[@]} > 0)) || { echo "error: no *.yaml in $dir" >&2; exit 1; }
for f in "${files[@]}"; do
  if $plain; then decrypted="$(cat "$f")"; else decrypted="$(sops -d "$f")"; fi
  if ! printf '%s' "$decrypted" | check_mandatory; then fail=1; continue; fi
  printf '%s' "$decrypted" | kubectl apply -f - >/dev/null
  echo "applied $(basename "$f")"
done
((fail == 0)) || { echo "some Secrets were NOT applied (see above)" >&2; exit 1; }
echo "done. Restart workloads that read a changed Secret: kubectl -n afrotransact-prod rollout restart deploy/<service>"
