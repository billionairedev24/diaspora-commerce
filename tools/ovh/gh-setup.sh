#!/usr/bin/env bash
# One-time set-up of the single-server OVHcloud environment, run on YOUR machine (docs/runbooks/ovh-vps.md, step 4):
#   make ovh-gh-setup            (or tools/ovh/gh-setup.sh)
# It asks for what only you can give (OVHcloud API values, project id, domain, keys), creates the GitHub Environment
# `ovh-vps` (you as required reviewer) and stores every value as an Environment secret or variable. After that the
# pipeline (.github/workflows/ovh-vps.yml) does everything: it creates the server, installs k3s and deploys the services.
# Re-run it any time to change a value; it shows which keys are set, never their values.
#
# Needs: the GitHub CLI signed in with admin rights on the repository (brew install gh; gh auth login), jq, curl, openssl,
# ssh-keygen. bash 3.2 (macOS) compatible.
#
# What it makes for you through the OVHcloud API, no console: the Public Cloud user `terraform-state` (role ObjectStore
# operator) and its S3 key (the Terraform state lives in an Object Storage bucket). It also generates the pipeline's SSH
# key pair (.ovh/ovh-vps-deploy-key, git-ignored: the private half becomes a secret, the public half goes on the server).
#
# Answers can be put in .ovh/ovh-vps.env (KEY=value lines, git-ignored) to skip the questions. ASK_OPTIONAL=0 skips the
# optional ones. GH_REPO=owner/repo overrides the repository of this checkout; OVH_GH_REVIEWER the reviewer's login.
set -euo pipefail
. "$(dirname "$0")/common.sh"
. "$(dirname "$0")/api.sh"
need gh jq curl openssl ssh-keygen
T=$OVH_ENV
REPO=${GH_REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}
[ -n "$REPO" ] || die "no repository: run inside the checkout or set GH_REPO=owner/repo"
gh auth status >/dev/null 2>&1 || die "gh is not signed in: gh auth login"
echo "== $REPO, environment $T"

# name|secret or var|req, opt or gen|default|what it is
CATALOGUE='OVH_SERVICE_NAME|var|req||Public Cloud project id (32 hex characters, top left of the project page in the OVHcloud console)
TF_STATE_BUCKET|var|req||name for the Object Storage bucket holding the Terraform state (the pipeline creates it; lower-case, 3-63 characters)
OVH_DOMAIN|var|req|test.example.com|public domain of this environment; you create A records for api., auth. and mcp. in front of it (step 6)
ACME_EMAIL|var|req||email for Lets Encrypt expiry notices
OVH_INSTANCE_FLAVOR|var|opt|b3-32|Public Cloud flavor (b3-32 = 8 vCPU / 32 GB; hourly price in the console; billed until the server is destroyed)
DB_MODE|var|req|cluster|cluster = Postgres on the server (disposable test data only) or external = a managed Postgres (Layerbase, OVHcloud Managed PostgreSQL)
EXTERNAL_PG_HOST|var|ext||managed Postgres host name
EXTERNAL_PG_ADMIN_USER|var|ext||managed Postgres admin user (creates one role and database per service)
EXTERNAL_PG_ADMIN_PASSWORD|secret|ext||managed Postgres admin password
EXTERNAL_PG_SSLMODE|var|ext|require|managed Postgres sslmode
ADMIN_EMAIL|var|req||email of the first staff admin (identity BOOTSTRAP_ADMIN_EMAIL)
ADMIN_PHONE|var|req|+15555550100|phone of the first staff admin
ADMIN_PASSWORD|secret|gen||password of the first staff admin (generated and shown once if you leave it empty)
REGISTRY_PULL_USERNAME|var|opt||GitHub login that owns the pull token (empty = the person who runs the workflow)
REGISTRY_PULL_TOKEN|secret|opt||classic GitHub token with read:packages only: the server pulls the images from ghcr.io with it (empty = the run token, which stops working after the run: pods that restart later cannot pull)
CLOUDFLARE_API_TOKEN|secret|opt||Cloudflare API token with Zone: DNS edit: the pipeline then creates the A records api./auth./mcp. itself (empty = you create them)
CLOUDFLARE_ZONE|var|opt||the Cloudflare zone of the domain, e.g. afrotransact.com (with the token above)
STRIPE_SECRET_KEY|secret|opt||Stripe TEST secret key sk_test_
STRIPE_PUBLISHABLE_KEY|secret|opt||Stripe TEST publishable key pk_test_ (gateway-bff will not start without it)
STRIPE_WEBHOOK_SECRET|secret|opt||whsec_ of the platform webhook https://api.<domain>/payments/v1/webhooks/stripe
STRIPE_CONNECT_WEBHOOK_SECRET|secret|opt||whsec_ of the Connect webhook
STRIPE_BILLING_WEBHOOK_SECRET|secret|opt||whsec_ of the billing webhook
RESEND_API_KEY|secret|opt||Resend API key re_ (email over HTTPS: OVHcloud blocks outbound SMTP on 25, 465 and 587)
OPENROUTER_API_KEY|secret|opt||OpenRouter key for Lola
GOOGLE_PLACES_API_KEY|secret|opt||Google Places key (address autocomplete)
GOOGLE_MAPS_API_KEY|secret|opt||Google Routes key (delivery distance)
UPLOADTHING_API_KEY|secret|opt||UploadThing key (media uploads)
USPS_CLIENT_ID|secret|opt||carrier sandbox: USPS client id
USPS_CLIENT_SECRET|secret|opt||carrier sandbox: USPS client secret
UPS_CLIENT_ID|secret|opt||carrier sandbox: UPS client id
UPS_CLIENT_SECRET|secret|opt||carrier sandbox: UPS client secret
FEDEX_CLIENT_ID|secret|opt||carrier sandbox: FedEx client id
FEDEX_CLIENT_SECRET|secret|opt||carrier sandbox: FedEx client secret'

ask() {          # ask <prompt> <default> [secret]
  local a
  if [ "${3:-}" = secret ]; then read -r -s -p "$1: " a </dev/tty; echo >/dev/tty
  else read -r -p "$1${2:+ [$2]}: " a </dev/tty
  fi
  printf '%s' "${a:-$2}"
}
yes_no() { local a; read -r -p "$1 [y/N]: " a </dev/tty; case "$a" in (y | Y | yes) return 0 ;; esac; return 1; }
existing_secrets=$(gh api "repos/$REPO/environments/$T/secrets" --paginate -q '.secrets[].name' 2>/dev/null || true)
existing_vars=$(gh api "repos/$REPO/environments/$T/variables" --paginate -q '.variables[].name' 2>/dev/null || true)
is_set() { printf '%s\n%s\n' "$existing_secrets" "$existing_vars" | grep -qx "$1"; }
put() {          # put <secret|var> <name> <value>
  if [ "$1" = secret ]; then printf '%s' "$3" | gh secret set "$2" --env "$T" --repo "$REPO" >/dev/null
  else gh variable set "$2" --env "$T" --repo "$REPO" --body "$3" >/dev/null
  fi
  echo "   set $1 $2"
}

# ---- 1. the environment: you are the required reviewer, deployments only from main ---------------------------------------
reviewer=${OVH_GH_REVIEWER:-$(gh api user -q .login)}
reviewer_id=$(gh api "users/$reviewer" -q .id)
body=$(printf '{"reviewers":[{"type":"User","id":%s}],"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' "$reviewer_id")
if printf '%s' "$body" | gh api -X PUT "repos/$REPO/environments/$T" --input - >/dev/null 2>&1; then
  echo "environment $T: required reviewer $reviewer (every run waits for your approval)"
else
  # Required reviewers on a private repository need a paid GitHub plan: the environment without them.
  printf '%s' '{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' \
    | gh api -X PUT "repos/$REPO/environments/$T" --input - >/dev/null \
    || die "GitHub refused to create the environment $T (admin rights? Environments in a private repository need a paid plan)"
  echo "warning: GitHub refused a required reviewer on $T (plan?): anyone with write access can run the workflow; destroy still needs a typed confirmation" >&2
fi
for b in ${OVH_GH_BRANCHES:-main}; do
  gh api -X POST "repos/$REPO/environments/$T/deployment-branch-policies" -f name="$b" -f type=branch >/dev/null 2>&1 || true
done
echo "environment $T: deployments from ${OVH_GH_BRANCHES:-main}"

# ---- 2. location --------------------------------------------------------------------------------------------------------
echo "== location"
loc=$(env_get OVH_LOCATION)
if [ -z "$loc" ]; then
  current=$(gh variable get OVH_LOCATION --env "$T" --repo "$REPO" 2>/dev/null || true)
  cat >/dev/tty <<'TXT'
   Where it runs (OVH_LOCATION):
     us-east-va  Vint Hill, Virginia  - OVHcloud US (us.ovhcloud.com), the default; close to a Virginia database
     us-west-or  Hillsboro, Oregon    - OVHcloud US
     ca-bhs      Beauharnois, Quebec  - OVHcloud Canada (ca.ovhcloud.com)
   OVHcloud US and Canada are separate companies with separate accounts, API endpoints and tokens: the token and the
   project below must come from the account of the location you pick.
TXT
  loc=$(ask "OVH_LOCATION" "${current:-us-east-va}")
fi
ovh_location "$loc"
put var OVH_LOCATION "$OVH_LOCATION"
echo "   $OVH_LOCATION: $(loc_get label) - account at $(loc_get site)"

# ---- 3. OVHcloud API credentials -----------------------------------------------------------------------------------------
echo "== OVHcloud API credentials ($(loc_get site) account)"
token_url="$(loc_get token_url)?GET=/cloud/*&POST=/cloud/*&PUT=/cloud/*&DELETE=/cloud/*"
if [ -n "$(env_get OVH_APPLICATION_KEY)" ] || ! is_set OVH_APPLICATION_KEY; then
  echo "   token page (open it signed in to $(loc_get site); the rights are pre-filled; validity 30 days; no IP restriction):"
  echo "   $token_url"
  for k in OVH_APPLICATION_KEY OVH_APPLICATION_SECRET OVH_CONSUMER_KEY; do
    v=$(env_get "$k"); [ -n "$v" ] || v=$(ask "$k" "" secret)
    [ -n "$v" ] || die "$k is required"
    put secret "$k" "$v"; export "$k=$v"
  done
else
  echo "   OVHcloud credentials already set (put them in .ovh/$T.env to replace)"
fi

# ---- 4. the catalogue ------------------------------------------------------------------------------------------------------
echo "== variables and secrets"
pid=$(env_get OVH_SERVICE_NAME)
db_mode=cluster
[ -z "$(env_get DB_MODE)" ] || db_mode=$(env_get DB_MODE)
if [ -z "$(env_get DB_MODE)" ] && is_set DB_MODE; then db_mode=$(gh variable get DB_MODE --env "$T" --repo "$REPO" 2>/dev/null || echo cluster); fi
generated_admin=''
while IFS='|' read -r name kind req default help <&3; do
  case "$req" in
    (ext) [ "$db_mode" = external ] || continue ;;
  esac
  v=$(env_get "$name")
  if [ -z "$v" ]; then
    case "$req" in
      (gen)
        is_set "$name" && continue
        need openssl
        v=$(openssl rand -hex 10); generated_admin=$v
        put "$kind" "$name" "$v"; continue ;;
      (opt) if is_set "$name" || [ "${ASK_OPTIONAL:-1}" = 0 ]; then continue; fi ;;
      (req|ext) if is_set "$name"; then echo "   keep $name"; [ "$name" != DB_MODE ] || db_mode=$(gh variable get DB_MODE --env "$T" --repo "$REPO" 2>/dev/null || echo cluster); continue; fi ;;
    esac
    if [ "$name" = TF_STATE_BUCKET ]; then default="afrotransact-tfstate-$(printf '%s' "$pid" | cut -c1-8)"; fi
    if [ "$req" = opt ] && [ -z "$default" ]; then
      yes_no "Set $name now? ($help)" || continue
      v=$(ask "$name" "" "$kind")
    else
      v=$(ask "$name - $help" "$default" "$kind")
    fi
    [ -n "$v" ] || { [ "$req" = opt ] && continue; die "$name is required"; }
  fi
  case "$name" in
    (OVH_SERVICE_NAME) printf '%s' "$v" | grep -Eq '^[0-9a-f]{32}$' || die "OVH_SERVICE_NAME is the 32-character project id (hex)"; pid=$v ;;
    (DB_MODE) case "$v" in (cluster | external) db_mode=$v ;; (*) die "DB_MODE must be cluster or external" ;; esac ;;
    (STRIPE_SECRET_KEY) case "$v" in (sk_test_*) ;; (*) die "STRIPE_SECRET_KEY must be a TEST key (sk_test_)" ;; esac ;;
    (TF_STATE_BUCKET) printf '%s' "$v" | grep -Eq '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$' || die "TF_STATE_BUCKET: lower-case letters, digits and hyphens, 3-63 characters" ;;
  esac
  put "$kind" "$name" "$v"
done 3<<EOF
$CATALOGUE
EOF
[ -z "$generated_admin" ] || echo "   the staff admin password is $generated_admin (shown once: keep it; sign in as the ADMIN_EMAIL above)"

# ---- 4a. the Terraform state's S3 user and key, through the API (no console) ---------------------------------------------
echo "== Terraform state user ($OVH_STATE_USER)"
[ -n "$pid" ] || pid=$(gh variable get OVH_SERVICE_NAME --env "$T" --repo "$REPO" 2>/dev/null || true)
[ -n "$pid" ] || die "OVH_SERVICE_NAME is needed to find or create the state user"
if [ -z "${OVH_APPLICATION_KEY:-}" ]; then
  echo "   the API values were kept from an earlier run: the pipeline finds or creates the state user itself on its first run"
else
  ovh_api_init
  OVH_SERVICE_NAME=$pid state_keys
  put secret TF_STATE_ACCESS_KEY "$STATE_ACCESS_KEY"; put secret TF_STATE_SECRET_KEY "$STATE_SECRET_KEY"
  unset STATE_SECRET_KEY
fi

# ---- 5. the pipeline's SSH key pair ------------------------------------------------------------------------------------------
echo "== SSH key for the pipeline"
keyfile="$OVH_REPO/.ovh/ovh-vps-deploy-key"
if [ ! -f "$keyfile" ]; then
  mkdir -p "$(dirname "$keyfile")"
  ssh-keygen -q -t ed25519 -N '' -C "afrotransact-ovh-pipeline" -f "$keyfile"
  echo "   new key pair $keyfile (+ .pub); keep it: it is also how you log in to the server yourself"
fi
put secret OVH_VPS_SSH_PRIVATE_KEY "$(cat "$keyfile")"
put var OVH_SSH_PUBLIC_KEY "$(cat "$keyfile.pub")"

echo
echo "Done. Next: Actions > ovh-vps > Run workflow: action 'plan', then 'provision-and-deploy' (docs/runbooks/ovh-vps.md, step 5)."
echo "Remember: the server is billed by the hour from the moment it is created until you run 'destroy'."
