#!/usr/bin/env bash
# Guided, resumable deploy of the TEST environment: OVHcloud (backend, one k3s server) + Cloudflare (three web apps, DNS).
#   make deploy-guide                         (or tools/deploy/guide.sh)
#   --status            where you are (no network)       --explain   the whole plan and the why of each phase (runs nothing)
#   --doctor            re-verify what is deployed (DNS, certificates, health, headers; read-only)
#   --checklist         every value: set / MISSING / optional skipped (never the value)
#   --set <NAME>        collect or change ONE value with its full help (make deploy-guide ARGS='--set STRIPE_SECRET_KEY')
#   --from <phase>      start at a phase and carry on     --only <phase>   run one phase
#   --dry-run           print what each phase would do; runs nothing, asks nothing, writes nothing
#   --yes               answer the NON-billable y/N questions with yes (typed confirmations of billable steps are never skipped)
# Phases: 0 preflight, 1 accounts, 2 values, 3 database, 4 flavor, 5 server, 6 dns, 7 cloudflare, 8 smoke, 9 stop (number or name).
# Progress: .ovh/deploy-guide.state (git-ignored, no secrets). Docs: docs/runbooks/deploy-guide.md. bash 3.2 compatible.
# Test hooks (tools/deploy/test): GUIDE_ROOT, GUIDE_TTY (scripted answers), GUIDE_STATE, GUIDE_SLEEP, GUIDE_ASCII, GUIDE_FORCE_COLOR.
set -o pipefail
GUIDE_ROOT=${GUIDE_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}
cd "$GUIDE_ROOT" || exit 1
. tools/deploy/lib/ui.sh
. tools/ovh/common.sh
. tools/ovh/api.sh
CAT_FILE="$GUIDE_ROOT/tools/deploy/catalogue.conf"
. tools/deploy/lib/catalogue.sh
. tools/deploy/lib/collect.sh
. tools/ovh/gh-lib.sh

ENVN=$OVH_ENV
STATE=${GUIDE_STATE:-$GUIDE_ROOT/.ovh/deploy-guide.state}
PHASES="preflight accounts values database flavor server dns cloudflare smoke stop"
NPH=9
DRY=0 MODE=run FROM='' ONLY='' SETNAME=''
REPO='' DOMAIN='' SFH=www SHA='' SERVER_IP=''
GUIDE_TMP=$(mktemp -d "${TMPDIR:-/tmp}/deploy-guide.XXXXXX") || exit 1
trap 'rm -rf "$GUIDE_TMP"' EXIT

# ---- state (non-secret facts only: a key is a-z0-9_. ; values are plain words, ids, hosts, dates) ------------------------------
state_get() { [ -f "$STATE" ] || return 0; awk -v k="$1" 'index($0, k "=") == 1 { v = substr($0, length(k) + 2) } END { print v }' "$STATE"; }
state_set() {
  [ "$DRY" = 0 ] || return 0
  case "$1" in ('' | *[!a-z0-9_.]*) echo "internal: bad state key $1" >&2; return 1 ;; esac
  mkdir -p "$(dirname "$STATE")"
  [ -f "$STATE" ] || printf '# deploy-guide progress: facts only, never secrets. Delete this file to start over.\n' > "$STATE"
  grep -v "^$1=" "$STATE" > "$STATE.new" 2>/dev/null; printf '%s=%s\n' "$1" "$2" >> "$STATE.new"; mv "$STATE.new" "$STATE"
}
state_del() { [ "$DRY" = 0 ] && [ -f "$STATE" ] && { grep -v "^$1=" "$STATE" > "$STATE.new"; mv "$STATE.new" "$STATE"; }; return 0; }
now() { date -u +%Y-%m-%dT%H:%MZ; }

phase_num() { local i=0 w; for w in $PHASES; do { [ "$w" = "$1" ] || [ "$i" = "$1" ]; } && { echo "$i"; return 0; }; i=$((i + 1)); done; return 1; }
id_of() { local i=0 w; for w in $PHASES; do [ "$i" = "$1" ] && { echo "$w"; return; }; i=$((i + 1)); done; }
title_of() {
  case "$1" in
    (preflight) echo "Preflight: tools, GitHub, images" ;; (accounts) echo "Accounts you create in a browser" ;; (values) echo "Your values, into GitHub" ;;
    (database) echo "Database: throwaway or kept" ;; (flavor) echo "Server size and region" ;; (server) echo "Create the server and deploy the backend" ;;
    (dns) echo "DNS and certificates" ;; (cloudflare) echo "Cloudflare: settings and the three web apps" ;; (smoke) echo "Smoke tests" ;; (stop) echo "Stop the billing" ;;
  esac
}

# ---- what each phase is: shown by the run, by --explain and by --dry-run ------------------------------------------------------
phase_what() {
  case "$1" in
    (preflight) echo "Checks your tools (gh, jq, curl, openssl, ssh-keygen), that gh is signed in with admin rights, that your commit is on origin/main (the workflows run from main), that the three workflows exist there, and that the 26 container images exist for that commit (or builds them with the ovh-images workflow)." ;;
    (accounts) echo "Walks you through the things only you can do in a browser: OVHcloud account and payment method, a Public Cloud project, a Cloudflare account, your domain on Cloudflare's nameservers, a Cloudflare API token, Stripe test keys, a Resend key. It checks what can be checked: project id format, nameservers, the token." ;;
    (values) echo "Asks for every value the pipeline needs, one at a time, with what it is, why and how to get it, validates each, checks the ones an API can check, and stores them as secrets and variables of the GitHub Environment ovh-vps. Makes the SSH key pair and the Terraform state user for you." ;;
    (database) echo "Choose where PostgreSQL runs: in a pod on the server (cluster: data dies with the server) or a managed Postgres you keep (external, for example Layerbase)." ;;
    (flavor) echo "Shows the region and the server size you chose and asks you to read the hourly price in the OVHcloud console before anything is created." ;;
    (server) echo "Runs the ovh-vps workflow: first plan (nothing created), then provision-and-deploy: creates the Public Cloud instance, the DNS records (if a Cloudflare token is set), installs k3s and deploys the 26 services. You approve each run on GitHub." ;;
    (dns) echo "Proves the four backend host names (api, auth, mcp, grafana) point at the server, the Let's Encrypt certificates are real and valid, and the API and sign-in answer over https." ;;
    (cloudflare) echo "Checks the Cloudflare zone settings the apps rely on (Add visitor location headers, Always Use HTTPS, SSL Full strict), asks about the Workers plan, then runs the ovh-web workflow that deploys storefront, Seller Center and staff console as Workers on your domain and the apex redirect." ;;
    (smoke) echo "Checks health endpoints, the sign-in page and its enforced CSP, the web apps' Report-Only CSP header, the CSP report endpoint, rebuilds the search index, and tells you how seeding works for this environment." ;;
    (stop) echo "Explains how to stop the billing (destroy the server, not scale it down) and what is kept; can run the destroy for you after a typed confirmation." ;;
  esac
}
phase_cost() {
  case "$1" in
    (preflight) echo "Free. If it builds images: GitHub Actions minutes (free on public repositories), about 20 to 40 minutes. No OVHcloud or Cloudflare cost." ;;
    (accounts) echo "Free to create. An OVH Public Cloud project costs nothing until an instance exists. Cloudflare free plan is enough to start." ;;
    (values) echo "Free. Creates the GitHub Environment and the Terraform state user (free). Nothing is created at OVHcloud that bills." ;;
    (database) echo "cluster: no extra bill, but ALL DATA IS LOST when the server is destroyed. external: the provider's monthly fee, running or not." ;;
    (flavor) echo "Nothing yet. The flavor is the largest cost line: billed by the hour from creation until destroy. Read its price in the console." ;;
    (server) echo "BILLABLE. The instance is billed by the hour from the moment it is created until you destroy it, even when idle or scaled down. Plan alone costs nothing but a few cents of state storage." ;;
    (dns) echo "Free." ;;
    (cloudflare) echo "Free plan, or Workers Paid (about 5 USD a month) if a Worker is too big for the free size limit. Check Cloudflare's current price." ;;
    (smoke) echo "Free. The search reindex is a Job on the server you already pay for." ;;
    (stop) echo "Saves money. Destroy deletes the server and its disk (and a Postgres running on it); it cannot be undone." ;;
  esac
}
phase_why() {
  case "$1" in
    (preflight) echo "A first run fails in boring ways: a missing tool, not admin on the repository, a commit that is not on main, images that were never built. Finding that out before anything bills is the point." ;;
    (accounts) echo "These need your identity, payment method or domain registrar; no script can do them for you." ;;
    (values) echo "The workflows read everything from the GitHub Environment, so secrets never live in the repository or on your disk." ;;
    (database) echo "It is the one choice you cannot undo cheaply: the wrong answer either loses data or costs money for nothing." ;;
    (flavor) echo "Prices are not verified in this repository and change; only the console is right." ;;
    (server) echo "The only step that bills. The plan first shows what would be created; the confirmation is typed so it cannot be a reflex." ;;
    (dns) echo "Let's Encrypt needs DNS to reach the server before it issues certificates, and the web apps need api. and auth. to exist." ;;
    (cloudflare) echo "The web apps run on Cloudflare, not on the server; the home page detects the visitor's city from Cloudflare's location headers." ;;
    (smoke) echo "Green workflows are not a working site. These checks prove what a visitor and the browser actually get." ;;
    (stop) echo "A test server nobody remembers is the most common surprise bill." ;;
  esac
}
phase_verify() {
  case "$1" in
    (preflight) echo "PASS when tools exist, gh is admin on the repo, HEAD is on origin/main, the workflows exist and the images exist (or you chose to rely on the per-service pipelines)." ;;
    (accounts) echo "PASS when you confirmed each browser step, the project id has the right format and the domain's nameservers are Cloudflare's." ;;
    (values) echo "PASS when every required value of phase 2 is set in GitHub (names only are read)." ;;
    (database) echo "PASS when DB_MODE is set and, for external, host, user, password and sslmode are set." ;;
    (flavor) echo "PASS when the location is known and the flavor name is well formed. The real check is the plan in the next phase." ;;
    (server) echo "PASS when the provision-and-deploy run succeeded and the server address is known." ;;
    (dns) echo "PASS when the four hosts resolve to the server (grey cloud), the certificates are Let's Encrypt and not near expiry, and api/auth answer." ;;
    (cloudflare) echo "PASS when www, seller and admin answer over https and the bare domain 301-redirects to www with path and query kept." ;;
    (smoke) echo "PASS when health, sign-in CSP, report-only CSP and the CSP report endpoint all check out." ;;
    (stop) echo "Done when you have read it (or destroyed the server)." ;;
  esac
}
phase_dry() {
  case "$1" in
    (preflight) echo "check gh, jq, curl, openssl, ssh-keygen on PATH|gh auth status; gh repo view (admin?)|git fetch origin main; HEAD must be on origin/main|gh workflow list (ovh-vps, ovh-web, ovh-images present)|gh run list ovh-images for the commit; else offer: gh workflow run ovh-images.yml (typed confirmation)" ;;
    (accounts) echo "print click paths for OVHcloud, Cloudflare, Stripe, Resend and wait for 'done' on each|validate the 32-hex project id|dig/DoH: nameservers of your zone are Cloudflare's|optionally verify a Cloudflare token (GET /user/tokens/verify, held in memory only)" ;;
    (values) echo "create the GitHub Environment ovh-vps (you as required reviewer, deployments from main only)|ask each catalogue value with help, validate, live-check, store with gh secret set / gh variable set|find or create the Public Cloud user terraform-state through the OVH API|generate .ovh/ovh-vps-deploy-key and store its halves" ;;
    (database) echo "show cluster vs external; require typing 'throwaway' for cluster|gh variable set DB_MODE; collect EXTERNAL_PG_* for external" ;;
    (flavor) echo "show location and flavor; ask for the hourly price you read in the console|gh variable set OVH_INSTANCE_FLAVOR" ;;
    (server) echo "completeness gate: refuse while a required value is missing|gh workflow run ovh-vps.yml -f action=plan (after typing 'plan')|gh workflow run ovh-vps.yml -f action=provision-and-deploy -f image-tag=<sha> (after typing 'create'): BILLING STARTS|wait for your approval on GitHub, poll the run, summarise a failure from its log|read the server IPv4 from the run log" ;;
    (dns) echo "A lookup for api/auth/mcp/grafana vs the server IP|openssl s_client: issuer, expiry|curl https://api.<domain>/bff/healthz and auth openid-configuration" ;;
    (cloudflare) echo "optionally read zone settings with your token (managed transform, always HTTPS, SSL mode)|ask about the Workers plan|gh workflow run ovh-web.yml -f apps=... -f domain=... -f storefront-host=... (after typing 'deploy')|curl www/seller/admin and the apex redirect" ;;
    (smoke) echo "curl health, sign-in page (CSP header), web CSP-Report-Only header|POST a labelled sample to /api/csp-report (expects 204)|gh workflow run ovh-vps.yml -f action=reindex-search (after typing 'reindex')|explain seeding" ;;
    (stop) echo "print how to destroy and what is kept|optionally: gh workflow run ovh-vps.yml -f action=destroy -f confirm-destroy=ovh-vps (after typing the word)" ;;
  esac
}
phase_after() { # what just happened | if it failed
  case "$1" in
    (preflight) echo "The wizard looked at your machine, your GitHub access and the commit it will deploy; it changed nothing. If images were missing it started the ovh-images build.|docs/runbooks/ovh-vps.md step 3; troubleshooting row 'no image at <sha> for: ...' (ovh-vps.md). gh not signed in: run gh auth login." ;;
    (accounts) echo "You created the accounts and the token the pipeline needs and the wizard checked what it could. Nothing was stored.|docs/runbooks/cloudflare-setup.md steps 1 to 4; nameservers not yet Cloudflare's: wait, it can take hours." ;;
    (values) echo "Your answers are now secrets and variables of the GitHub Environment ovh-vps; the state user and SSH key exist. Nothing at OVHcloud bills yet.|ovh-vps.md troubleshooting: 'plan fails with an authentication error' (wrong OVHcloud company or expired token). Re-run: make deploy-guide ARGS='--only values'." ;;
    (database) echo "DB_MODE is stored. It is read when the server is deployed.|Re-run: make deploy-guide ARGS='--only database'. Layerbase access rules: its dashboard." ;;
    (flavor) echo "The flavor is stored. The plan in the next phase proves OVHcloud offers it.|ovh-vps.md troubleshooting: 'plan fails: flavor ... is not offered'." ;;
    (server) echo "GitHub Actions created the instance, the DNS records, installed k3s and deployed the services. The server is billing now.|ovh-vps.md troubleshooting table (flavor, authentication, 'no image at', cannot SSH, ImagePullBackOff, CrashLoopBackOff)." ;;
    (dns) echo "The wizard proved names, certificates and the API from the outside, like a visitor.|ovh-vps.md step 6 and rows 'Certificate stays False', and cloudflare-setup.md row '522/525 on api. or auth.'." ;;
    (cloudflare) echo "The three web apps are Workers on your domain and the bare domain redirects to www.|cloudflare-setup.md troubleshooting: size limit, 'already has a DNS record', error 10000, redirect rows." ;;
    (smoke) echo "Health, sign-in, CSP and search were exercised from the outside.|docs/runbooks/csp-violations-spike.md; ovh-vps.md rows 'CrashLoopBackOff' and 'Authorization errors'." ;;
    (stop) echo "You know how to stop the billing.|ovh-vps.md step 9: check the Public Cloud project shows no instance." ;;
  esac
}

# ---- context ------------------------------------------------------------------------------------------------------------------
ensure_ctx() {
  [ "$DRY" = 0 ] || return 0
  [ -n "$REPO" ] || REPO=${GH_REPO:-$(state_get repo)}
  if [ -z "$REPO" ]; then REPO=$(gh repo view --json nameWithOwner 2>/dev/null | jq -r '.nameWithOwner // empty'); [ -z "$REPO" ] || state_set repo "$REPO"; fi
  [ -n "$REPO" ] || { bad "cannot find the GitHub repository: run gh auth login, or set GH_REPO=owner/repo"; return 1; }
  [ "$GH_LISTED" = 1 ] || gh_refresh
  DOMAIN=$(state_get domain); [ -n "$DOMAIN" ] || DOMAIN=$(gh_var OVH_DOMAIN)
  SFH=$(gh_var OVH_STOREFRONT_HOST); [ -n "$SFH" ] || SFH=www
  SHA=$(state_get image_sha)
  SERVER_IP=$(state_get server.ip)
  [ -z "$(state_get location)" ] || OVH_LOCATION=$(state_get location)
  [ -n "${OVH_LOCATION:-}" ] || OVH_LOCATION=$(gh_var OVH_LOCATION)
  [ -n "$OVH_LOCATION" ] || OVH_LOCATION=us-east-va
  ovh_location "$OVH_LOCATION"
}
need_domain() { [ -n "$DOMAIN" ] || { bad "the domain is not known yet: run the 'accounts' and 'values' phases first"; return 1; }; }

# ---- workflow runs ------------------------------------------------------------------------------------------------------------
latest_run_id() { gh run list --workflow "$1" --branch main --limit 1 --json databaseId 2>/dev/null | jq -r '.[0].databaseId // 0'; }
NEW_RUN=0
new_run_seen() { local id; id=$(latest_run_id "$1"); [ "$id" -gt "$2" ] 2>/dev/null && NEW_RUN=$id; }
RUN_ID='' RUN_STATUS='' RUN_CONCLUSION='' RUN_URL='' ANNOUNCED=''
start_run() { # start_run workflow.yml [gh args]: RUN_ID
  local wf=$1 prev; shift
  prev=$(latest_run_id "$wf")
  if ! gh workflow run "$wf" --ref main "$@" >/dev/null 2>"$GUIDE_TMP/err"; then
    bad "GitHub would not start $wf: $(mask < "$GUIDE_TMP/err" | tail -1)"; hint "Is the workflow on main? Is Actions enabled? (preflight checks both)"; return 1
  fi
  NEW_RUN=0
  poll "waiting for GitHub to queue the run" 3 120 new_run_seen "$wf" "$prev" || { bad "no new run appeared for $wf"; return 1; }
  RUN_ID=$NEW_RUN
}
run_probe() {
  local j; j=$(gh run view "$1" --json status,conclusion,url 2>/dev/null) || return 1
  RUN_STATUS=$(printf '%s' "$j" | jq -r '.status // ""'); RUN_CONCLUSION=$(printf '%s' "$j" | jq -r '.conclusion // ""'); RUN_URL=$(printf '%s' "$j" | jq -r '.url // ""')
  if [ "$RUN_STATUS" = waiting ] && [ -z "$ANNOUNCED" ]; then
    ANNOUNCED=1; [ "$UI_TTY" != 1 ] || printf '\r\033[K'
    warn "The run is waiting for YOUR approval (you are the required reviewer): open $RUN_URL > Review deployments > $ENVN > Approve."
  fi
  [ "$RUN_STATUS" = completed ]
}
watch_run() { # watch_run ID label: 0 when the run succeeded
  ANNOUNCED=''
  RUN_URL="https://github.com/$REPO/actions/runs/$1"; say "  run $1: $RUN_URL"
  poll "$2" 15 7200 run_probe "$1" || { warn "still running after 2 hours; check $RUN_URL, then run this phase again to re-attach"; return 1; }
  [ "$RUN_CONCLUSION" = success ]
}
failure_summary() { # failure_summary ID kind(images|server|web)
  local log pat
  log=$(gh run view "$1" --log-failed 2>&1 | mask | tail -n 25)
  blank; bad "last lines of the failed step (credentials masked):"
  printf '%s\n' "$log" | sed "s/^/    $C_DIM/; s/\$/$C_RESET/"
  case "$2" in
    (server) match_hint "$log" \
      'is not offered => ovh-vps.md row "plan fails: flavor ... is not offered": set the flavor (phase 4) to one the console lists for the region' \
      'authenticat|INVALID_CREDENTIAL|401|403 => ovh-vps.md row "plan fails with an authentication error": token expired or from the other OVHcloud company (US vs Canada); rerun phase 2' \
      'no image at => ovh-vps.md row "no image at <sha> for": build the images (phase 0, ovh-images) and run again; nothing was created' \
      'does not answer on port 22|cannot log in => ovh-vps.md row "The run cannot SSH in": instance still starting or SSH key mismatch; run again, or destroy and apply' \
      'ImagePullBackOff => ovh-vps.md row ImagePullBackOff: set REGISTRY_PULL_TOKEN (phase 2) and run the services action' \
      'CrashLoopBackOff => ovh-vps.md row CrashLoopBackOff: kubectl logs deploy/<service>; usually a missing third-party key (gateway-bff needs STRIPE_PUBLISHABLE_KEY)' \
      'is empty: run make => ovh-vps.md "Check the inputs": a required value is missing; run phase 2' \
      'quota|Quota => not in the runbook table: OVH Public Cloud > Quota and regions: ask for more quota for the flavor' ;;
    (web) match_hint "$log" \
      'size limit|too large|Script startup => cloudflare-setup.md row "Script startup/size limit exceeded": upgrade to Workers Paid, run phase 7 again' \
      'already has a DNS record|already exists => cloudflare-setup.md row "custom domain already has a DNS record": delete that record in Cloudflare DNS (step 2.4)' \
      '10000|not authorized => cloudflare-setup.md row "Authentication error 10000": token lacks a permission or its Zone Resources exclude the domain' \
      'differs from the OVH_STOREFRONT_HOST => ovh-vps.md row "redirect_uri mismatch": make storefront-host equal OVH_STOREFRONT_HOST' \
      'must be secrets|Decide => cloudflare-setup.md row "ovh-web stops at Decide": set the Cloudflare values (phase 2)' ;;
    (images) match_hint "$log" 'denied|permission => ovh-images: the workflow needs packages: write; Settings > Actions > General > Workflow permissions' ;;
  esac
  hint "full log: gh run view $1 --log-failed   ($RUN_URL)"
}
match_hint() { # match_hint LOG 'regex => hint'...: every hint whose regex matches the log
  local log=$1 pair; shift
  for pair in "$@"; do
    if printf '%s' "$log" | grep -Eiq "${pair%% => *}"; then warn "${pair#* => }"; fi
  done
}

# ---- checks shared by the phases and --doctor (each prints its own PASS/FAIL line and returns 0/1) -------------------------------
quiet() { "$@" >/dev/null 2>&1; }
BACKEND_HOSTS="api auth mcp grafana"
chk_dns() {
  local h ips bad=0 line=''
  [ -n "$SERVER_IP" ] || { verdict_warn "server address unknown: cannot compare DNS (run the server phase)"; return 1; }
  for h in $BACKEND_HOSTS; do
    ips=$(a_lookup "$h.$DOMAIN" | tr '\n' ' ')
    case " $ips " in (*" $SERVER_IP "*) ;; (*) bad=1; line="$line $h.$DOMAIN->${ips:-nothing}" ;; esac
  done
  if [ "$bad" = 0 ]; then verdict_pass "api, auth, mcp and grafana .$DOMAIN all resolve to $SERVER_IP"; return 0; fi
  verdict_fail "not pointing at $SERVER_IP:$line"
  hint "Fix: type A, DNS only (grey cloud), content $SERVER_IP, in Cloudflare DNS; remove a CAA record that excludes letsencrypt.org (ovh-vps.md step 6). DNS can take minutes."
  return 1
}
chk_proxied() { # a grey cloud is required on api. and auth.
  http "https://api.$DOMAIN/bff/healthz"
  if [ "$(hdr server | tr 'A-Z' 'a-z')" = cloudflare ]; then verdict_fail "api.$DOMAIN is proxied (orange cloud): Let's Encrypt cannot validate. Turn the cloud grey in Cloudflare DNS (cloudflare-setup.md row 522/525)"; return 1; fi
  return 0
}
chk_cert() { # chk_cert HOST
  local pem info issuer
  pem=$(openssl s_client -connect "$1:443" -servername "$1" </dev/null 2>/dev/null)
  info=$(printf '%s' "$pem" | openssl x509 -noout -issuer -enddate 2>/dev/null)
  if [ -z "$info" ]; then verdict_fail "$1: no certificate served yet (cert-manager still issuing, or DNS/port 80 not reaching the server)"; hint "ovh-vps.md row 'Certificate stays False': kubectl describe certificate -n afrotransact-dev"; return 1; fi
  issuer=$(printf '%s' "$info" | sed -n 's/^issuer= *//p')
  case "$issuer" in
    (*STAGING* | *Fake* | *fake*) verdict_fail "$1: certificate is from a staging CA ($issuer)"; return 1 ;;
    (*"Let's Encrypt"* | *"Lets Encrypt"* | *R1[0-9]* | *E[5-9]*) ;;
    (*) verdict_fail "$1: certificate issuer is '$issuer', not Let's Encrypt (a self-signed Traefik default means the real one is not ready)"; return 1 ;;
  esac
  if ! printf '%s' "$pem" | openssl x509 -noout -checkend 604800 >/dev/null 2>&1; then verdict_fail "$1: certificate expires within 7 days or already expired"; return 1; fi
  verdict_pass "$1: Let's Encrypt, $(printf '%s' "$info" | sed -n 's/^notAfter=//p')"
}
chk_api() {
  http "https://api.$DOMAIN/bff/healthz"
  [ "$HTTP_CODE" = 200 ] && verdict_pass "https://api.$DOMAIN/bff/healthz answers 200" || { verdict_fail "api health answered $HTTP_CODE (000 = no connection)"; hint "ovh-vps.md: run the status action; a pod may still be starting (gateway-bff needs STRIPE_PUBLISHABLE_KEY)"; return 1; }
  http "https://auth.$DOMAIN/.well-known/openid-configuration"
  local iss; iss=$(body | jq -r '.issuer // empty' 2>/dev/null)
  if [ "$HTTP_CODE" = 200 ] && [ "$iss" = "https://auth.$DOMAIN" ]; then verdict_pass "identity issuer is $iss"; return 0; fi
  verdict_fail "identity answered $HTTP_CODE, issuer '${iss:-none}' (expected https://auth.$DOMAIN)"; hint "ovh-vps.md row 'Authorization errors' / identity pod logs"; return 1
}
web_host() { case "$1" in (storefront) [ "$SFH" = apex ] && echo "$DOMAIN" || echo "www.$DOMAIN" ;; (seller) echo "seller.$DOMAIN" ;; (admin) echo "admin.$DOMAIN" ;; esac; }
chk_hosts() {
  local a h bad=0
  for a in storefront seller admin; do
    h=$(web_host "$a"); http "https://$h/"
    case "$HTTP_CODE" in
      (2?? | 3??) verdict_pass "https://$h answers $HTTP_CODE" ;;
      (*) verdict_fail "https://$h answers $HTTP_CODE (000: no certificate or no Worker yet; 5xx: api. not reachable)"; bad=1 ;;
    esac
  done
  [ "$bad" = 0 ]
}
chk_apex() {
  if [ "$SFH" = apex ]; then verdict_pass "storefront-host is apex: no redirect expected"; return 0; fi
  http "https://$DOMAIN/p/x?a=1"
  local loc; loc=$(hdr location)
  if [ "$HTTP_CODE" = 301 ] && [ "$loc" = "https://www.$DOMAIN/p/x?a=1" ]; then verdict_pass "https://$DOMAIN/p/x?a=1 -> 301 $loc (path and query kept)"; return 0; fi
  verdict_fail "apex answered $HTTP_CODE, location '${loc:-none}' (expected 301 to https://www.$DOMAIN/p/x?a=1)"
  hint "cloudflare-setup.md rows 'The bare domain does not redirect' and 'custom domain already has a DNS record': delete an old A/CNAME on the apex and run the web phase again"
  return 1
}
chk_signin_csp() {
  http "https://auth.$DOMAIN/login"
  local csp; csp=$(hdr content-security-policy)
  if [ "$HTTP_CODE" = 200 ] && printf '%s' "$csp" | grep -q "default-src 'none'" && printf '%s' "$csp" | grep -q "frame-ancestors 'none'"; then
    verdict_pass "sign-in page loads (200) with its enforced CSP (default-src 'none', frame-ancestors 'none')"; return 0; fi
  verdict_fail "sign-in page: HTTP $HTTP_CODE, CSP header ${csp:+present but not the expected policy}${csp:-missing}"
  hint "docs/engineering/security-headers.md (identity section). A proxy that strips headers, or an old image."; return 1
}
chk_web_csp() {
  local a h bad=0 ro enf
  for a in storefront seller admin; do
    h=$(web_host "$a"); http "https://$h/"
    ro=$(hdr content-security-policy-report-only); enf=$(hdr content-security-policy)
    if [ -n "$ro" ] && printf '%s' "$ro" | grep -q "nonce-"; then verdict_pass "$h: nonce CSP in Report-Only (violations are logged, nothing is blocked)"
    elif [ -n "$enf" ]; then verdict_warn "$h: CSP is ENFORCED (CSP_MODE=enforce): expected report-only for a first deploy"
    else verdict_fail "$h: no CSP header (HTTP $HTTP_CODE); expected Content-Security-Policy-Report-Only with a nonce"; bad=1; fi
  done
  [ "$bad" = 0 ]
}
chk_csp_report() { # chk_csp_report post|get
  local a h bad=0
  for a in storefront seller admin; do
    h=$(web_host "$a")
    if [ "$1" = post ]; then
      http "https://$h/api/csp-report" -X POST -H 'Content-Type: application/csp-report' --data '{"csp-report":{"document-uri":"https://'"$h"'/guide-smoke","violated-directive":"img-src","blocked-uri":"https://guide-smoke.invalid"}}'
      [ "$HTTP_CODE" = 204 ] && verdict_pass "$h/api/csp-report accepted a sample report (204)" || { verdict_fail "$h/api/csp-report answered $HTTP_CODE (expected 204)"; bad=1; }
    else
      http "https://$h/api/csp-report"
      case "$HTTP_CODE" in (204 | 405) verdict_pass "$h/api/csp-report is reachable ($HTTP_CODE)" ;; (*) verdict_fail "$h/api/csp-report answered $HTTP_CODE"; bad=1 ;; esac
    fi
  done
  [ "$bad" = 0 ]
}

# ---- Cloudflare read helpers (token held in memory only) -------------------------------------------------------------------------
cf_token_prompt() {
  [ -z "$GUIDE_CF_TOKEN" ] || return 0
  blank
  info "  Optional: let the wizard CHECK your Cloudflare zone. It reads settings only, keeps the token in memory for this run, never stores or prints it."
  confirm_yn "Paste your Cloudflare API token now?" n || return 1
  local t
  while :; do
    read_secret "CLOUDFLARE_API_TOKEN (hidden, Enter to skip):"; t=$REPLY
    [ -n "$t" ] || return 1
    validate cftoken "$t" || { bad "$V_MSG"; continue; }
    live_check cf_token "$t" && { t=''; return 0; }
    t=''
  done
}
cf_get() { bearer_get "https://api.cloudflare.com/client/v4$1" "$GUIDE_CF_TOKEN"; }

# ---- phases ---------------------------------------------------------------------------------------------------------------------
p_preflight() {
  local t miss='' out perm branch head tip behind
  blank; bold "1. Tools on this machine"
  for t in gh jq curl openssl ssh-keygen; do
    if command -v "$t" >/dev/null 2>&1; then ok "$t"; else bad "$t is not installed ($([ "$t" = ssh-keygen ] && echo "part of OpenSSH: macOS has it, Debian: apt install openssh-client" || echo "macOS: brew install $t"))"; miss="$miss $t"; fi
  done
  command -v dig >/dev/null 2>&1 && ok "dig (optional)" || warn "dig not found: DNS checks use DNS over HTTPS through curl instead"
  hint "wrangler and tofu are NOT needed here: the workflows run them."
  [ -z "$miss" ] || { verdict_fail "missing:$miss"; return 1; }
  blank; bold "2. GitHub access"
  gh auth status >/dev/null 2>&1 || { verdict_fail "gh is not signed in. Run: gh auth login   (an account with admin rights on the repository)"; return 1; }
  ok "gh is signed in"
  out=$(gh repo view ${GH_REPO:+"$GH_REPO"} --json nameWithOwner,viewerPermission,defaultBranchRef 2>/dev/null)
  REPO=$(printf '%s' "$out" | jq -r '.nameWithOwner // empty'); perm=$(printf '%s' "$out" | jq -r '.viewerPermission // empty'); branch=$(printf '%s' "$out" | jq -r '.defaultBranchRef.name // empty')
  [ -n "$REPO" ] || { verdict_fail "cannot read the repository: run inside the checkout or set GH_REPO=owner/repo"; return 1; }
  state_set repo "$REPO"
  [ "$perm" = ADMIN ] && ok "repository $REPO, you are ADMIN" || { verdict_fail "you are '$perm' on $REPO: admin is needed to create the Environment and its secrets"; return 1; }
  [ "$branch" = main ] && ok "default branch is main" || warn "default branch is '$branch': the workflows are started with --ref main and must exist there"
  blank; bold "3. The commit that will be deployed"
  git fetch origin main -q 2>/dev/null || warn "could not fetch origin/main (offline?): using what this checkout knows"
  head=$(git rev-parse HEAD 2>/dev/null); tip=$(git rev-parse origin/main 2>/dev/null)
  [ -n "$tip" ] || { verdict_fail "no origin/main in this checkout"; return 1; }
  if ! git merge-base --is-ancestor "$head" "$tip" 2>/dev/null; then
    verdict_fail "your HEAD ($(printf '%s' "$head" | cut -c1-8)) is not on origin/main: the workflows deploy what is on main. Push or merge, then run again."; return 1; fi
  [ "$head" = "$tip" ] && ok "HEAD is origin/main ($(printf '%s' "$tip" | cut -c1-8))" || {
    behind=$(git rev-list --count "$head..$tip" 2>/dev/null); warn "your checkout is $behind commit(s) behind origin/main; the wizard uses origin/main's tip. git pull to match."; }
  [ -z "$(git status --porcelain 2>/dev/null)" ] || warn "you have uncommitted changes: they are not deployed (only what is on main is)"
  SHA=$tip; state_set image_sha "$SHA"
  blank; bold "4. The three workflows exist on the default branch"
  local wl w
  wl=$(gh workflow list --json name 2>/dev/null | jq -r '.[].name')
  for w in ovh-images ovh-vps ovh-web; do
    printf '%s\n' "$wl" | grep -qx "$w" && ok "workflow $w" || { verdict_fail "workflow $w not found on $branch: they must be merged to the default branch (workflow_dispatch only works from there)"; return 1; }
  done
  hint "No repository-level variables are needed: everything is in the Environment $ENVN (phase 2)."
  blank; bold "5. Container images for $(printf '%s' "$SHA" | cut -c1-8)"
  if gh run list --workflow ovh-images.yml --branch main --limit 30 --json headSha,conclusion 2>/dev/null | jq -e --arg s "$SHA" 'map(select(.headSha == $s and .conclusion == "success")) | length > 0' >/dev/null; then
    ok "an ovh-images run succeeded for this commit"; state_set images ok; verdict_pass "preflight"; return 0
  fi
  warn "no successful ovh-images run for this commit. The per-service pipelines may have built the images; the ovh-vps run re-checks all 26 before creating anything."
  choose "What do you want to do about the images?" 1 \
    "Build all 26 now with the ovh-images workflow (recommended)" \
    "Skip: I know the per-service pipelines built this commit" \
    "Stop here"
  case "$CHOICE" in
    (3) quit_now ;;
    (2) state_set images skipped; ok "relying on the per-service pipelines"; verdict_pass "preflight (images not proven here)"; return 0 ;;
  esac
  { echo "Starts 26 image builds on GitHub Actions at $SHA (Trivy scan, SBOM, signature)."
    echo "Cost: Actions minutes (free on public repositories), about 20 to 40 minutes."
    echo "Nothing is created at OVHcloud or Cloudflare."; } | panel "EFFECT" yellow
  confirm_type build "Start the image build?" || { warn "not started"; return 1; }
  start_run ovh-images.yml || return 1
  if watch_run "$RUN_ID" "building 26 images"; then ok "images built"; state_set images ok; verdict_pass "preflight"; return 0; fi
  failure_summary "$RUN_ID" images; verdict_fail "the image build did not succeed"; return 1
}

p_accounts() {
  local loc site step ns zone dom pid k
  ensure_ctx || return 1
  loc=$(state_get location); [ -n "$loc" ] || loc=${OVH_LOCATION:-us-east-va}
  ovh_location "$loc"; site=$(loc_get site)
  while :; do
    { echo "1. Go to $site and create an account (OVHcloud US and Canada are separate companies: use the one of your location $OVH_LOCATION)."
      echo "2. Control Panel, your name (top right) > Billing > Payment methods: add a card."
      echo "3. Public Cloud > Create a project (free; nothing bills until a server exists)."
      echo "4. Open the project: its id (32 hex characters) is top left and in the URL after /project/."; } | panel "WHAT YOU NEED - OVHcloud (browser)" cyan
    wait_done "OVHcloud account, payment method and project exist?" && break
  done
  while :; do
    read_in "Paste the Public Cloud project id (not a secret):"; pid=$REPLY
    validate hex32 "$pid" && { ok "project id has the right format"; break; }; bad "$V_MSG"
  done
  while :; do
    { echo "1. dash.cloudflare.com/sign-up: create the account, confirm the email, turn on two-factor (Profile > Authentication)."
      echo "2. Add a domain: enter the REGISTERED domain (example.com), plan Free. A name two levels deep (test.example.com) needs a paid certificate: avoid it."
      echo "3. Cloudflare shows two nameservers. At your registrar replace the domain's nameservers with them, click Check nameservers."
      echo "4. DNS > Records: delete any CAA record that excludes letsencrypt.org, and any A/AAAA/CNAME on the bare domain, www, seller and admin."
      echo "5. SSL/TLS > Overview: Full (strict)."
      echo "6. Create an API token (My Profile > API Tokens > Create Custom Token) with: Account/Workers Scripts/Edit, Account/Account Settings/Read, Zone/Workers Routes/Edit, Zone/DNS/Edit, Zone/Zone/Read. Account: yours. Zone: only your domain."
      echo "   Keep the token for the next phase, where it is asked for and stored in GitHub."; } | panel "WHAT YOU NEED - Cloudflare (browser)" cyan
    wait_done "Cloudflare account, domain and token ready?" && break
  done
  while :; do
    read_in "Your domain (the Cloudflare zone, for example example.com):"; dom=$REPLY
    validate domain "$dom" || { bad "$V_MSG"; continue; }
    ns=$(ns_lookup "$dom" | tr '\n' ' ')
    if printf '%s' "$ns" | grep -qi cloudflare; then verdict_pass "$dom nameservers are Cloudflare's"; break; fi
    verdict_fail "$dom nameservers are '${ns:-not found}', not Cloudflare's. Change them at the registrar (it can take a few hours)."
    choose "What now?" 1 "Check again" "Continue anyway (the web phase will fail until it is Active)" "Stop and come back later"
    case "$CHOICE" in (2) break ;; (3) quit_now ;; esac
  done
  state_set domain "$dom"; DOMAIN=$dom
  [ "$(printf '%s' "$dom" | tr -cd '.' | wc -c | tr -d ' ')" -lt 2 ] || warn "$dom has three or more labels: unless it is its own zone, www.$dom needs a paid Advanced Certificate"
  if cf_token_prompt; then
    cf_get /accounts; say "  Cloudflare account(s) this token sees (the id is CLOUDFLARE_ACCOUNT_ID, asked in the next phase):"
    body | jq -r '.result[]? | "    " + .name + " = " + .id'
  fi
  { echo "Stripe (dashboard.stripe.com): switch to Test mode (toggle top right), Developers > API keys: you will copy the Publishable key (pk_test_) and Secret key (sk_test_) in the next phase."
    echo "Resend (resend.com): create an account, Domains > add and verify your sending domain, API Keys > Create API Key (sending access): copy re_... in the next phase. Optional: without it no email is sent."; } | panel "WHAT YOU NEED - Stripe and Resend (browser)" cyan
  wait_done "Stripe test keys visible and (optionally) a Resend key created?" || true
  state_set accounts_confirmed "$(now)"
  verdict_pass "accounts"; return 0
}

p_values() {
  local m
  ensure_ctx || return 1
  { echo "The GitHub Environment $ENVN is created with you as the required reviewer, and deployments allowed only from main."
    echo "Values go to GitHub straight from your prompt; secrets are hidden as you type and are never printed or written to disk."; } | panel "WHAT HAPPENS" cyan
  confirm_yn "Create or update the GitHub Environment $ENVN on $REPO?" y || return 1
  gh_env_ensure "$REPO" "$ENVN" || return 1
  gh_refresh
  collect_phase 2 || return 1
  gh_refresh
  blank; bold "Checklist of phase 2"; checklist all | head -60
  m=$(missing_phase 2)
  if [ -n "$m" ]; then verdict_fail "required values still missing: $m"; hint "Run again: make deploy-guide ARGS='--only values'"; return 1; fi
  local z; z=$(gh_var CLOUDFLARE_ZONE); DOMAIN=$(gh_var OVH_DOMAIN); state_set domain "$DOMAIN"
  [ -z "$z" ] || [ "$z" = "$DOMAIN" ] || warn "OVH_DOMAIN ($DOMAIN) differs from the Cloudflare zone ($z): names below the zone need a paid Advanced Certificate (cloudflare-setup.md step 2.7)"
  verdict_pass "every required value of phase 2 is set"; return 0
}
missing_phase() {
  local n out=''
  for n in $(cat_names_phase "$1"); do needed_now "$n" || continue; is_set "$n" || out="$out $n"; done
  printf '%s' "${out# }"
}

p_database() {
  local cur n
  ensure_ctx || return 1
  cur=$(db_mode_now)
  { echo "cluster  - Postgres runs as a pod on the server's disk. No extra bill. Destroy the server and EVERY ROW IS GONE. Right for a throwaway test."
    echo "external - a managed Postgres (Layerbase in Virginia is the repository's choice; OVHcloud Managed PostgreSQL also works). Data survives a destroy. A separate monthly fee, running or not."
    echo "Never run your own production database: backups, point-in-time recovery and failover are what lose data when done by hand."; } | panel "CHOOSE" yellow
  info "  Current: DB_MODE=$cur"
  choose "Where should PostgreSQL run?" "$([ "$cur" = external ] && echo 2 || echo 1)" "cluster  (throwaway test data)" "external (managed Postgres I keep)"
  if [ "$CHOICE" = 1 ]; then
    { echo "You chose cluster: ALL DATABASE CONTENT IS DELETED when the server is destroyed, including products, orders and users."; } | panel "DATA LOSS" red
    confirm_type throwaway "Is this data disposable?" || { warn "not changed"; return 1; }
    gh_put var DB_MODE cluster && ok "DB_MODE = cluster"
  else
    gh_put var DB_MODE external && ok "DB_MODE = external"
    hint "If the provider restricts access by IP, add the server's address once phase 5 prints it."
    for n in $(cat_names_phase 3 | grep EXTERNAL); do collect_one "$n" || return 1; done
  fi
  gh_refresh
  n=$(missing_phase 3)
  [ -z "$n" ] || { verdict_fail "missing for DB_MODE=$(db_mode_now): $n"; return 1; }
  verdict_pass "DB_MODE=$(db_mode_now)"; return 0
}

p_flavor() {
  local fl price
  ensure_ctx || return 1
  { echo "Region: $OVH_LOCATION. OVH does not meter outbound traffic in its US and European regions (not Singapore or Sydney)."
    echo "Open the OVHcloud console: Public Cloud > your project > Instances > Create an instance. Choose region $OVH_LOCATION and read the HOURLY PRICE of the flavor you pick. This repository does not know current prices."
    echo "The default b3-32 (8 vCPU, 32 GB) is the biggest cost line. 16 GB is too small for the stack."; } | panel "COST - read the price yourself" yellow
  collect_one OVH_INSTANCE_FLAVOR || return 1
  fl=$(gh_var OVH_INSTANCE_FLAVOR); [ -n "$fl" ] || fl=b3-32
  while :; do
    read_in "Hourly price you saw in the console for $fl in $OVH_LOCATION (a number, to show you looked):"; price=$REPLY
    printf '%s' "$price" | grep -Eq '^[0-9]+([.,][0-9]+)?$' && break; bad "type the number you saw, for example 0.40"
  done
  state_set flavor_ack "$fl"
  verdict_pass "location $OVH_LOCATION, flavor $fl (OVHcloud checks the flavor in the plan)"; return 0
}

gate() { # gate server|web: refuse while a required value is missing
  local m
  gh_refresh; m=$(missing_for "$1")
  [ -z "$m" ] && return 0
  { echo "These required values are not set: $m"; echo; echo "Nothing billable or outward-facing starts until they are. Set them with:  make deploy-guide ARGS='--only values'  (or database, flavor)."; } | panel "BLOCKED" red
  checklist missing
  return 1
}

p_server() {
  local fl db existing_ip plan_run
  ensure_ctx || return 1
  gate server || { verdict_fail "required values missing"; return 1; }
  ok "all required values are set"
  SHA=$(state_get image_sha); [ -n "$SHA" ] || SHA=$(git rev-parse origin/main 2>/dev/null)
  [ -n "$SHA" ] || { bad "no commit known: run the preflight phase"; return 1; }
  fl=$(gh_var OVH_INSTANCE_FLAVOR); [ -n "$fl" ] || fl=b3-32; db=$(db_mode_now)
  say "  repository $REPO   domain $DOMAIN   location $OVH_LOCATION   flavor $fl   database $db   images $(printf '%s' "$SHA" | cut -c1-8)"
  existing_ip=$(state_get server.ip)
  if [ -n "$existing_ip" ] && [ -z "$(state_get destroyed)" ]; then
    warn "a server was already created by this wizard ($existing_ip)."
    choose "What now?" 1 "Keep it: skip to verification" "Deploy again on the same server (action deploy: no new instance, services updated)" "Stop"
    case "$CHOICE" in
      (1) SERVER_IP=$existing_ip; verdict_pass "server $existing_ip already exists"; return 0 ;;
      (3) quit_now ;;
    esac
    server_run deploy "$SHA" || return 1; return 0
  fi
  # resume an unfinished run
  plan_run=$(state_get server.run)
  if [ -n "$plan_run" ]; then
    run_probe "$plan_run"
    if [ "$RUN_STATUS" = completed ]; then
      if [ "$RUN_CONCLUSION" = success ]; then server_finish "$plan_run"; return $?; fi
      warn "the recorded run $plan_run ended as '$RUN_CONCLUSION'; starting over"; state_del server.run
    else
      info "  Re-attaching to the unfinished run $plan_run"
      if watch_run "$plan_run" "waiting for the deploy run"; then server_finish "$plan_run"; return $?; fi
      failure_summary "$plan_run" server; verdict_fail "the run did not succeed"; return 1
    fi
  fi
  if [ "$(state_get server.plan_ok)" != "$fl-$OVH_LOCATION" ]; then
    { echo "plan creates the Terraform state bucket and user (a few cents of storage) and shows what WOULD be created. No server is created, nothing bills for compute."
      echo "GitHub holds the run until you approve it as the required reviewer."; } | panel "STEP A - plan (free)" cyan
    confirm_type plan "Run the plan?" || { warn "skipped: the server step needs a passing plan"; return 1; }
    start_run ovh-vps.yml -f action=plan -f "image-tag=$SHA" || return 1
    if watch_run "$RUN_ID" "tofu plan"; then ok "plan succeeded: OVHcloud accepts the flavor and the credentials"; state_set server.plan_ok "$fl-$OVH_LOCATION"
    else failure_summary "$RUN_ID" server; verdict_fail "plan failed: nothing was created"; return 1; fi
  else ok "plan already passed for $fl in $OVH_LOCATION"; fi
  server_run provision-and-deploy "$SHA"
}
server_run() { # server_run ACTION SHA
  { echo "Creates a Public Cloud instance ($fl in $OVH_LOCATION) and deploys 26 services on it. BILLING STARTS WHEN THE INSTANCE EXISTS, by the hour, until you run destroy (the 'down' action does NOT stop it)."
    echo "Database: $(db_mode_now). $([ "$(db_mode_now)" = cluster ] && echo 'Cluster: the data is lost on destroy.' || echo 'External: kept by your provider.')"
    echo "Also: DNS records at Cloudflare (if your token is set), k3s, certificates. About 30 to 45 minutes. You approve the run on GitHub."
    echo "Stop it later with: make deploy-guide ARGS='--only stop'."; } | panel "COST AND EFFECT - billable" red
  confirm_type create "Create the server and deploy?" || { warn "cancelled: nothing was created"; return 1; }
  start_run ovh-vps.yml -f "action=$1" -f "image-tag=$2" || return 1
  state_set server.run "$RUN_ID"; state_set server.sha "$2"; state_set server.created_at "$(now)"
  if watch_run "$RUN_ID" "provisioning and deploying (30-45 min)"; then server_finish "$RUN_ID"; return $?; fi
  failure_summary "$RUN_ID" server; verdict_fail "the run did not succeed. If the instance was created it IS billing: fix and run this phase again (it re-uses it), or destroy (phase 9)."; return 1
}
server_finish() {
  local ip
  ip=$(gh run view "$1" --log 2>/dev/null | sed -n 's/.*server: \([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\).*/\1/p' | head -1)
  if [ -z "$ip" ]; then
    warn "could not read the server address from the run log"
    read_in "Type the server IPv4 (shown in the run summary, or the OVHcloud console):"; ip=$REPLY
  fi
  printf '%s' "$ip" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' || { verdict_fail "'$ip' is not an IPv4 address"; return 1; }
  SERVER_IP=$ip; state_set server.ip "$ip"; state_del server.run; state_del destroyed
  verdict_pass "run succeeded; the server is $ip (billing since $(state_get server.created_at))"
  hint "ssh -i .ovh/ovh-vps-deploy-key ubuntu@$ip"
  return 0
}

p_dns() {
  local h
  ensure_ctx || return 1; need_domain || return 1
  SERVER_IP=$(state_get server.ip)
  if [ -z "$SERVER_IP" ]; then read_in "Server IPv4 (not in the state; from the run summary):"; SERVER_IP=$REPLY; state_set server.ip "$SERVER_IP"; fi
  if ! is_set CLOUDFLARE_API_TOKEN; then
    { echo "No Cloudflare token is stored, so the pipeline could not make the records. Create four A records, DNS only (grey cloud), all pointing at $SERVER_IP:"
      for h in $BACKEND_HOSTS; do echo "  $h.$DOMAIN"; done; } | panel "DO THIS BY HAND" yellow
    wait_done "Records created?" || true
  fi
  poll "waiting for DNS to resolve to $SERVER_IP" 10 900 quiet chk_dns || true
  chk_dns || return 1
  quiet chk_proxied || { chk_proxied; return 1; }
  ok "api. is DNS only (not proxied)"
  poll "waiting for the Let's Encrypt certificates (usually a few minutes)" 10 900 quiet chk_cert "api.$DOMAIN" || true
  local rc=0
  chk_cert "api.$DOMAIN" || rc=1
  chk_cert "auth.$DOMAIN" || rc=1
  for h in mcp grafana; do chk_cert "$h.$DOMAIN" || verdict_warn "$h is not essential for the first test"; done
  [ "$rc" = 0 ] || return 1
  chk_api || return 1
  verdict_pass "DNS, certificates and API are fine"; return 0
}

p_cloudflare() {
  local zid v m h plan wf_apps run
  ensure_ctx || return 1; need_domain || return 1
  gate web || { verdict_fail "required values missing"; return 1; }
  ok "Cloudflare values are set"
  blank; bold "Zone settings the apps rely on"
  if cf_token_prompt; then
    local zone; zone=$(gh_var CLOUDFLARE_ZONE); cf_get "/zones?name=$zone"; zid=$(body | jq -r '.result[0].id // empty')
    if [ -z "$zid" ]; then verdict_warn "the token does not see the zone $zone: settings checked by hand below"
    else
      cf_get "/zones/$zid/settings/ssl"; v=$(body | jq -r '.result.value // empty')
      case "$v" in (strict | full) [ "$v" = strict ] && verdict_pass "SSL/TLS mode is Full (strict)" || verdict_warn "SSL mode is '$v': set Full (strict)" ;; (*) hint "SSL mode not readable with this token (it has no Zone Settings Read: by design)" ;; esac
      cf_get "/zones/$zid/settings/always_use_https"; v=$(body | jq -r '.result.value // empty')
      [ "$v" = on ] && verdict_pass "Always Use HTTPS is on" || hint "Always Use HTTPS: ${v:-not readable with this token}"
      cf_get "/zones/$zid/managed_headers"
      v=$(body | jq -r '[.result.managed_request_headers[]? | select(.id == "add_visitor_location_headers") | .enabled] | .[0] // empty')
      if [ "$v" = true ]; then verdict_pass "Add visitor location headers is ON (city detection works)"; elif [ "$v" = false ]; then verdict_fail "Add visitor location headers is OFF: turn it on (Rules > Settings > Managed Transforms)"; else hint "managed transforms not readable with this token"; fi
      for h in "$DOMAIN" "www.$DOMAIN" "seller.$DOMAIN" "admin.$DOMAIN"; do
        cf_get "/zones/$zid/dns_records?name=$h"
        m=$(body | jq -r '[.result[]? | select(.type == "A" or .type == "AAAA" or .type == "CNAME") | .type] | join(",")')
        if [ -n "$m" ]; then
          if [ -n "$(state_get web.deployed)" ]; then hint "$h has a $m record (expected after an earlier web deploy)"
          else verdict_warn "$h already has a $m record: Workers Custom Domains refuse to overwrite it. Delete it first (cloudflare-setup.md step 2.4)"; fi
        fi
      done
    fi
  fi
  { echo "1. Rules > Settings > Managed Transforms > turn ON 'Add visitor location headers' (the home page detects the visitor's city from them; LOCATION_DETECT_ZIP is a local-only override, do not set it here)."
    echo "2. SSL/TLS > Overview: Full (strict).   3. SSL/TLS > Edge Certificates: Always Use HTTPS on."
    echo "Unlike infra/cloudflare (production zone), this test setup turns on no WAF, Turnstile or rate limits."; } | panel "ZONE SETTINGS TO CONFIRM" cyan
  confirm_yn "Are all three on in your zone?" n || { verdict_fail "turn them on, then run: make deploy-guide ARGS='--only cloudflare'"; return 1; }
  blank
  { echo "The free Workers plan limits the size of a Worker; Next.js apps built with OpenNext are often too big. Workers Paid is about 5 USD a month (check Cloudflare's current price). The first deploy tells you: the error is 'size limit exceeded'."; } | panel "WORKERS PLAN" yellow
  choose "Which Workers plan is the account on?" 1 "Free (start here; upgrade only if the deploy says too large)" "Paid" "Not sure"
  case "$CHOICE" in (1) plan=free ;; (2) plan=paid ;; (*) plan=unknown ;; esac
  state_set workers_plan "$plan"
  { echo "Deploys storefront, Seller Center and staff console as Cloudflare Workers on $DOMAIN (www./seller./admin.; storefront-host=$SFH) and, with www, the small apex redirect Worker."
    echo "Outward-facing: the sites go live on your domain. Cost: free plan, or Workers Paid if needed. Each app takes 5 to 10 minutes."
    echo "storefront-host must equal OVH_STOREFRONT_HOST ($SFH), which the backend was deployed with."; } | panel "EFFECT - public deploy" yellow
  confirm_type deploy "Deploy the web apps?" || { warn "cancelled"; return 1; }
  start_run ovh-web.yml -f "apps=storefront seller-center staff-console" -f "domain=$DOMAIN" -f "storefront-host=$SFH" || return 1
  state_set web.run "$RUN_ID"
  if ! watch_run "$RUN_ID" "building and deploying three Workers (5-10 min each)"; then failure_summary "$RUN_ID" web; verdict_fail "web deploy failed"; return 1; fi
  state_del web.run; state_set web.deployed "$(now)"
  ok "workflow finished"
  poll "waiting for the custom-domain certificates (first one can take minutes)" 10 900 quiet chk_hosts || true
  local rc=0
  chk_hosts || rc=1
  chk_apex || rc=1
  http "http://www.$DOMAIN/"; case "$HTTP_CODE" in (301 | 302 | 307 | 308) verdict_pass "http:// redirects to https (Always Use HTTPS)" ;; (*) verdict_warn "http://www.$DOMAIN answered $HTTP_CODE: turn on Always Use HTTPS" ;; esac
  [ "$rc" = 0 ] || return 1
  info "  Not checkable from outside: open https://www.$DOMAIN in a browser: the deliver-to city should be your own (needs the location headers)."
  verdict_pass "three web apps are up"; return 0
}

p_smoke() {
  local rc=0 sha ans
  ensure_ctx || return 1; need_domain || return 1
  SERVER_IP=$(state_get server.ip)
  chk_api || rc=1
  chk_signin_csp || rc=1
  chk_web_csp || rc=1
  chk_csp_report post || rc=1
  [ "$rc" = 0 ] || { verdict_fail "a smoke check failed"; return 1; }
  blank
  { echo "Search starts EMPTY. The reindex action rebuilds the index from catalog, offers and inventory (a Job on the server; minutes; safe to repeat)."; } | panel "SEARCH REINDEX" cyan
  if confirm_type reindex "Run the reindex now?"; then
    sha=$(state_get server.sha); [ -n "$sha" ] || sha=$(state_get image_sha)
    start_run ovh-vps.yml -f action=reindex-search -f "image-tag=$sha" || return 1
    if watch_run "$RUN_ID" "reindexing search"; then ok "reindex done"; else failure_summary "$RUN_ID" server; warn "reindex failed; it is safe to retry (phase 8 again)"; fi
  else warn "skipped: search stays empty until you run the reindex-search action"; fi
  blank
  { echo "The demo seeder (tools/seed/seed.py, make seed) refuses to run against non-local service URLs unless SEED_ALLOW_REMOTE=1, and always refuses APP_ENV=prod or uat."
    echo "Only set SEED_ALLOW_REMOTE=1 on purpose, for this test environment, and never for anything with real customers."
    echo "GAP: the seeder talks to each service directly (catalog, offers, ...) and those are NOT exposed on public hosts; only /bff, /identity and a few paths are. Seeding this environment needs an SSH tunnel plus kubectl port-forward and a SEED_TOKEN; that is not automated or verified here."; } | panel "DEMO DATA" yellow
  if ! is_set STRIPE_WEBHOOK_SECRET; then
    { echo "Stripe webhook: Developers > Webhooks > Add endpoint: https://api.$DOMAIN/payments/v1/webhooks/stripe, then copy its signing secret (whsec_). Without it payments never confirm."; } | panel "STRIPE WEBHOOK" cyan
    if confirm_yn "Add the whsec_ secret now?" n; then
      collect_one STRIPE_WEBHOOK_SECRET
      if confirm_type apply "Apply it to the server now (ovh-vps action services)?"; then
        sha=$(state_get server.sha); start_run ovh-vps.yml -f action=services -f "image-tag=${sha:-$(state_get image_sha)}" && watch_run "$RUN_ID" "re-deploying the services" && ok "services updated"
      fi
    fi
  fi
  verdict_pass "smoke checks"; return 0
}

p_stop() {
  local sha
  ensure_ctx || return 1
  { echo "To stop the billing run the 'destroy' action. 'down' only scales the services to zero: THE SERVER KEEPS BILLING."
    echo "Destroy deletes: the instance and its disk (including a Postgres running on it), the Terraform state, the state bucket and the state user."
    echo "Kept: DNS records, the container images, the GitHub Environment and its values, the backup bucket (objects expire after 30 days), the three Workers (they will point at a dead backend), and a Layerbase database if you use one (still billed)."
    echo "Afterwards, check the Public Cloud project shows no instance: that is the only thing that bills. Revoke the OVHcloud API token too."; } | panel "STOP BILLING" red
  if [ -z "$(state_get server.ip)" ] || [ -n "$(state_get destroyed)" ]; then ok "no server is recorded as running"; verdict_pass "nothing to stop"; return 0; fi
  warn "this wizard created a server at $(state_get server.ip) on $(state_get server.created_at); it is billing."
  if confirm_type "$ENVN" "Destroy it now? (type the environment name; anything else just finishes this reminder)"; then
    sha=$(state_get image_sha)
    start_run ovh-vps.yml -f action=destroy -f "confirm-destroy=ovh-vps" -f "image-tag=$sha" || return 1
    if watch_run "$RUN_ID" "destroying the server"; then
      state_set destroyed "$(now)"; state_del server.ip; state_del server.plan_ok; state_del phase.server; state_del phase.dns; state_del phase.smoke
      verdict_pass "destroyed. Confirm in the console that the project shows no instance."; return 0
    fi
    failure_summary "$RUN_ID" server; verdict_fail "destroy failed: the server may still be billing. Retry, or delete the instance in the console."; return 1
  fi
  verdict_warn "the server is still running and billing. Run  make deploy-guide ARGS='--only stop'  when you are done."
  return 0
}

# ---- modes ------------------------------------------------------------------------------------------------------------------------
print_summary() {
  local sf web
  ensure_ctx >/dev/null 2>&1; [ -n "$DOMAIN" ] || DOMAIN='<your-domain>'
  sf="https://www.$DOMAIN"; [ "$SFH" != apex ] || sf="https://$DOMAIN"
  blank; rule; bold "  Summary"; rule
  printf '  %-22s %s\n' "Storefront" "$sf" "Seller Center" "https://seller.$DOMAIN" "Staff console" "https://admin.$DOMAIN" \
    "API (gateway-bff)" "https://api.$DOMAIN/bff" "Sign-in (identity)" "https://auth.$DOMAIN" "MCP" "https://mcp.$DOMAIN" "Grafana" "https://grafana.$DOMAIN"
  [ -z "$(state_get server.ip)" ] || { printf '  %-22s %s\n' "Server" "$(state_get server.ip)  (ssh -i .ovh/ovh-vps-deploy-key ubuntu@$(state_get server.ip))"; printf '  %s%s%s\n' "$C_YELLOW" "  Billing: the server bills by the hour until destroyed." "$C_RESET"; }
  [ -z "$SHA" ] || printf '  %-22s %s\n' "Images" "$(printf '%s' "$SHA" | cut -c1-12)"
  blank; bold "  Next three things"
  say "   1. Sign in at https://admin.$DOMAIN as ADMIN_EMAIL (the password you set or the one shown once)."
  say "   2. Add the Stripe webhook (https://api.$DOMAIN/payments/v1/webhooks/stripe), then re-run phase 8; watch csp.violation lines in Workers Logs for a week."
  say "   3. When you stop for the day: make deploy-guide ARGS='--only stop'   (destroy; 'down' does not stop billing)."
}

show_status() {
  local i id st
  banner; blank
  printf '  %s%-3s %-12s %-10s %s%s\n' "$C_BOLD" '#' PHASE STATUS 'WHEN / NOTE' "$C_RESET"
  for i in 0 1 2 3 4 5 6 7 8 9; do
    id=$(id_of "$i"); st=$(state_get "phase.$id")
    if [ -n "$st" ]; then printf '  %-3s %-12s %s%-10s%s %s\n' "$i" "$id" "$C_GREEN" done "$C_RESET" "$st"
    else printf '  %-3s %-12s %s%-10s%s\n' "$i" "$id" "$C_DIM" pending "$C_RESET"; fi
  done
  blank
  say "  repo: $(state_get repo)   domain: $(state_get domain)   location: $(state_get location)   commit: $(state_get image_sha | cut -c1-8)"
  [ -z "$(state_get server.ip)" ] || say "  server: $(state_get server.ip) (created $(state_get server.created_at))"
  [ -z "$(state_get server.run)" ] || say "  an unfinished server run is recorded: $(state_get server.run) (phase 5 re-attaches)"
  [ -z "$(state_get destroyed)" ] || say "  destroyed: $(state_get destroyed)"
  hint "state file: $STATE"
}

show_explain() {
  local i id
  banner; blank
  info "  This is the whole plan. Nothing is run, asked or written."
  for i in 0 1 2 3 4 5 6 7 8 9; do
    id=$(id_of "$i"); header "$i" "$NPH" "$(title_of "$id")"
    phase_what "$id" | panel "WHAT IT DOES" cyan
    phase_cost "$id" | panel "COST" $(case "$id" in (server | database | stop) echo red ;; (flavor | cloudflare) echo yellow ;; (*) echo green ;; esac)
    phase_why "$id" | panel "WHY" dim
    phase_verify "$id" | panel "HOW IT IS VERIFIED" green
    phase_after "$id" | sed -n 's/^[^|]*|//p' | panel "IF IT FAILS" dim
  done
  blank; bold "  The values (tools/deploy/catalogue.conf)"
  printf '  %s%-30s %-7s %-10s %-3s %s%s\n' "$C_BOLD" NAME KIND NEED PH WHAT "$C_RESET"
  awk -F'|' '/^#/ || NF < 11 { next } { printf "  %-30s %-7s %-10s %-3s %.60s\n", $1, $2, $3, $5, $9 }' "$CAT_FILE"
  blank; bold "  What it never does"
  say "   - never prints, stores in its state file or logs a secret; it reads them with hidden prompts and passes them to GitHub on stdin"
  say "   - never creates a server, changes DNS or deploys without you typing a word (create, plan, deploy, reindex...); --yes does not skip those"
  say "   - never uses 'down' to stop billing; never touches production (dev/uat/prod pipelines, infra/cloudflare)"
}

show_dry() {
  local i id line
  banner; blank
  info "  DRY RUN: nothing is asked, run, sent or written. This is what each phase would do."
  for i in 0 1 2 3 4 5 6 7 8 9; do
    id=$(id_of "$i")
    [ -z "$ONLY" ] || [ "$i" = "$ONLY" ] || continue
    [ -z "$FROM" ] || [ "$i" -ge "$FROM" ] || continue
    header "$i" "$NPH" "$(title_of "$id")"
    phase_what "$id" | panel "WHAT IT DOES" cyan
    phase_cost "$id" | panel "COST" $(case "$id" in (server | database | stop) echo red ;; (flavor | cloudflare) echo yellow ;; (*) echo green ;; esac)
    phase_dry "$id" | tr '|' '\n' | while IFS= read -r line; do printf '  %s[dry-run]%s would %s\n' "$C_DIM" "$C_RESET" "$line"; done
  done
  blank; say "  Run it for real: make deploy-guide"
}

doctor() {
  local rc=0
  banner; blank; info "  DOCTOR: read-only re-verification of what is deployed (no changes, no workflow runs)."
  command -v gh >/dev/null 2>&1 && ensure_ctx >/dev/null 2>&1
  [ -n "$DOMAIN" ] || { read_in "Domain (not in the state):" ; DOMAIN=$REPLY; }
  SERVER_IP=$(state_get server.ip)
  [ -n "$SERVER_IP" ] || SERVER_IP=$(a_lookup "api.$DOMAIN" | head -1)
  say "  domain $DOMAIN   server ${SERVER_IP:-unknown}   storefront-host $SFH"
  blank; bold "DNS and certificates"
  chk_dns || rc=1; chk_cert "api.$DOMAIN" || rc=1; chk_cert "auth.$DOMAIN" || rc=1
  blank; bold "Backend"; chk_api || rc=1
  blank; bold "Web apps"; chk_hosts || rc=1; chk_apex || rc=1
  blank; bold "Headers"; chk_signin_csp || rc=1; chk_web_csp || rc=1; chk_csp_report get || rc=1
  if [ "$GH_LISTED" = 1 ]; then blank; bold "Values"; checklist missing; fi
  blank
  [ "$rc" = 0 ] && verdict_pass "everything checked is healthy" || verdict_fail "something above failed: follow the hint under it"
  return "$rc"
}

run_phase() {
  local i=$1 id rc after what failed
  id=$(id_of "$i")
  header "$i" "$NPH" "$(title_of "$id")"
  phase_what "$id" | panel "WHAT YOU NEED / WHAT IT DOES" cyan
  phase_cost "$id" | panel "COST" $(case "$id" in (server | database | stop) echo red ;; (flavor | cloudflare) echo yellow ;; (*) echo green ;; esac)
  "p_$id"; rc=$?
  after=$(phase_after "$id"); what=${after%%|*}; failed=${after#*|}
  blank
  if [ "$rc" = 0 ]; then
    state_set "phase.$id" "$(now)"
    printf '  %sWhat just happened:%s %s\n' "$C_BOLD" "$C_RESET" "$what"
    return 0
  fi
  printf '  %sWhat just happened:%s %s\n' "$C_BOLD" "$C_RESET" "$what"
  printf '  %sIf it failed:%s %s\n' "$C_BOLD$C_RED" "$C_RESET" "$failed"
  blank; bad "Phase $i ($id) did not pass. Fix the cause above, then run  make deploy-guide  again: it resumes here."
  return 1
}

main() {
  local i id force
  while [ $# -gt 0 ]; do
    case "$1" in
      (--set) shift; MODE=set; SETNAME=${1:-} ;;
      (--status) MODE=status ;; (--explain) MODE=explain ;; (--doctor) MODE=doctor ;; (--checklist) MODE=checklist ;;
      (--dry-run) DRY=1 ;; (--yes | -y) ASSUME_YES=1 ;;
      (--from) shift; FROM=$(phase_num "${1:-}") || { echo "unknown phase '${1:-}': $PHASES (or 0-9)" >&2; exit 2; } ;;
      (--only) shift; ONLY=$(phase_num "${1:-}") || { echo "unknown phase '${1:-}': $PHASES (or 0-9)" >&2; exit 2; } ;;
      (-h | --help) sed -n 2,16p "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
      (*) echo "unknown option $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
  done
  ui_init
  case "$MODE" in
    (status) show_status; exit 0 ;;
    (explain) show_explain; exit 0 ;;
  esac
  [ "$DRY" = 0 ] || { show_dry; exit 0; }
  ui_open_input || [ "$MODE" = doctor ] || exit 1
  if [ "$MODE" = doctor ]; then doctor; exit $?; fi
  if [ "$MODE" = set ]; then ensure_ctx || exit 1; set_one "$SETNAME"; exit $?; fi
  if [ "$MODE" = checklist ]; then ensure_ctx || exit 1; checklist all; exit 0; fi
  banner
  for i in 0 1 2 3 4 5 6 7 8 9; do
    id=$(id_of "$i")
    [ -z "$ONLY" ] || [ "$i" = "$ONLY" ] || continue
    [ -z "$FROM" ] || [ "$i" -ge "$FROM" ] || continue
    force=0; { [ -n "$ONLY" ] || [ -n "$FROM" ]; } && force=1
    if [ "$force" = 0 ] && [ -n "$(state_get "phase.$id")" ]; then
      printf '  %s%s%s Phase %s (%s) already done %s: skipping. Re-run with --only %s.\n' "$C_GREEN" "$S_OK" "$C_RESET" "$i" "$id" "$(state_get "phase.$id")" "$id"
      continue
    fi
    run_phase "$i" || exit 1
  done
  [ -z "$ONLY" ] || exit 0
  print_summary
  if ensure_ctx >/dev/null 2>&1; then blank; bold "  Checklist of every value"; checklist all; fi
}

main "$@"
