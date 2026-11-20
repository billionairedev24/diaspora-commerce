#!/usr/bin/env bash
# Guided, owner-run helper for the FIRST UAT deploy of release-1 (docs/runbooks/release-1-go-live.md, sections 4 to 8).
#
#   tools/release/uat-tonight.sh                 list the stages
#   tools/release/uat-tonight.sh check           read-only: tools, GitHub settings (names only), offline gate, CI state
#   tools/release/uat-tonight.sh images          build the two UNVERIFIED UAT images for the pinned sha, scan and sign (asks first)
#   tools/release/uat-tonight.sh promote         promote those images to uat only, then check the pins (asks first)
#   tools/release/uat-tonight.sh guide           the owner-only steps that remain, in order (sections 5 to 7)
#   tools/release/uat-tonight.sh smoke           read-only smoke of the deployed uat (tools/smoke/go-live.sh)
#
# Safety rules, all enforced here:
#   * UAT only. ENV=prod is refused: PROD needs an image from a fully green verify (runbook section 4, "Production needs ...").
#   * Every command that changes something (a workflow dispatch) is printed and needs a typed "yes". DRY_RUN=1 prints and never runs.
#   * The commit is pinned: EXPECTED_SHA (default: origin/release-1 now). If release-1 moves, the script stops. Re-read, then re-run.
#   * It never reads, prints or sends a secret VALUE. GitHub secrets and variables are checked by NAME only. Local env files are
#     checked for the presence of key names only.
#   * It does not touch OVH, Layerbase, Cloudflare, Stripe, DNS or the cluster. Those are yours (guide stage).
set -uo pipefail

REPO="${REPO:-billionairedev24/diaspora-commerce}"
ENV="${ENV:-uat}"
BRANCH="release-1"
DRY_RUN="${DRY_RUN:-0}"
GHCR="ghcr.io/${REPO}"
SERVICES=(afrotransact-core afrotransact-go)

[[ "$ENV" == "uat" ]] || { echo "This script is UAT only (got ENV=$ENV). PROD is a separate, reviewed run." >&2; exit 2; }
cd "$(git rev-parse --show-toplevel)" || exit 2
ROOT="$(pwd)"

say()  { printf '\n== %s\n' "$*"; }
ok()   { printf 'OK    %s\n' "$*"; }
warn() { printf 'WARN  %s\n' "$*"; WARNINGS=$((WARNINGS+1)); }
bad()  { printf 'FAIL  %s\n' "$*"; FAILS=$((FAILS+1)); }
WARNINGS=0; FAILS=0

need() { command -v "$1" >/dev/null 2>&1 && ok "tool: $1" || { bad "missing tool: $1 ($2)"; return 1; }; }

confirm() { # confirm "what will happen"
  printf '\nAbout to: %s\n' "$1"
  if [[ "$DRY_RUN" == "1" ]]; then echo "(DRY_RUN=1: not running)"; return 1; fi
  read -r -p 'Type "yes" to run it: ' a
  [[ "$a" == "yes" ]]
}

remote_sha() { git ls-remote origin "refs/heads/${BRANCH}" | cut -f1; }

pin_sha() {
  local now; now="$(remote_sha)"
  [[ -n "$now" ]] || { echo "cannot read origin/${BRANCH}" >&2; exit 2; }
  EXPECTED_SHA="${EXPECTED_SHA:-$now}"
  if [[ "$now" != "$EXPECTED_SHA" ]]; then
    echo "origin/${BRANCH} is $now but EXPECTED_SHA is $EXPECTED_SHA. It moved: stop, review what changed, set EXPECTED_SHA, re-run." >&2
    exit 3
  fi
  echo "Pinned commit: $EXPECTED_SHA"
}

stage_check() {
  say "Tools (runbook 4.2)"
  for t in git gh python3 helm kubeconform kustomize; do need "$t" "install it" || true; done
  for t in tofu kubectl sops age psql argocd cosign jq; do command -v "$t" >/dev/null 2>&1 && ok "tool: $t" || warn "not installed: $t (needed later: see the stage that uses it)"; done

  say "GitHub (names only, no values)"
  gh auth status >/dev/null 2>&1 && ok "gh is logged in" || warn "gh auth status failed: run 'gh auth login' before images/promote"
  def="$(gh api "repos/${REPO}" --jq .default_branch 2>/dev/null || true)"
  [[ "$def" == "$BRANCH" ]] && ok "default branch is $BRANCH" || bad "default branch is '${def:-unknown}', must be $BRANCH (owner step 1: Argo CD and scheduled workflows read HEAD)"
  # A setting that cannot be READ (no permission, proxy, not logged in) is a WARN, never reported as missing.
  if envs="$(gh api "repos/${REPO}/environments" --jq '.environments[].name' 2>/dev/null)"; then
    grep -qx uat <<<"$envs" && ok "GitHub environment 'uat' exists" || bad "GitHub environment 'uat' missing (owner step 7)"
  else warn "cannot read GitHub environments (login or permission): check 'uat' by hand"; fi
  if vars="$(gh variable list --repo "$REPO" --json name --jq '.[].name' 2>/dev/null)"; then
    evars="$(gh variable list --repo "$REPO" --env uat --json name --jq '.[].name' 2>/dev/null || true)"
    for v in CLOUD REGISTRY_URL; do
      grep -qx "$v" <<<"$vars"$'\n'"$evars" && ok "variable $v is set" || bad "variable $v not set (repo or 'uat' environment)"
    done
  else warn "cannot read GitHub variables: check CLOUD and REGISTRY_URL by hand"; fi
  if secrets="$(gh secret list --repo "$REPO" --json name --jq '.[].name' 2>/dev/null)"; then
    esecrets="$(gh secret list --repo "$REPO" --env uat --json name --jq '.[].name' 2>/dev/null || true)"
    for s in GITOPS_APP_ID GITOPS_APP_PRIVATE_KEY; do
      grep -qx "$s" <<<"$secrets"$'\n'"$esecrets" && ok "secret $s exists" || bad "secret $s missing (needed by promote to commit the image pin)"
    done
  else warn "cannot read GitHub secrets: check GITOPS_APP_ID and GITOPS_APP_PRIVATE_KEY by hand"; fi
  if pr="$(gh api "repos/${REPO}/actions/permissions/workflow" --jq .can_approve_pull_request_reviews 2>/dev/null)"; then
    [[ "$pr" == "true" ]] && ok "Actions may create and approve pull requests" || bad "Actions may not create PRs: Settings > Actions > General (owner step 7)"
  else warn "cannot read Actions permissions: check 'Allow GitHub Actions to create and approve pull requests' by hand"; fi

  say "Pinned commit and offline gate (runbook 4.2; nothing is changed)"
  pin_sha
  [[ "$(git rev-parse HEAD)" == "$EXPECTED_SHA" ]] && ok "this checkout is at the pinned commit" || warn "this checkout is $(git rev-parse --short HEAD), not the pinned commit; run from a worktree of origin/${BRANCH}"
  python3 infra/argocd/scripts/check_launch_set.py && ok "launch set" || bad "check_launch_set.py failed"
  python3 tools/release/config_inventory.py --check && ok "config inventory" || bad "config_inventory.py --check failed"
  python3 tools/release/check_capacity.py && ok "capacity" || bad "check_capacity.py failed"
  python3 tools/release/test_image_verdict.py >/dev/null 2>&1 && ok "image verdict tests" || bad "test_image_verdict.py failed"
  if command -v helm >/dev/null && command -v kubeconform >/dev/null; then
    for d in java/apps/core go/apps/afrotransact-go; do
      ( cd "$d/deploy/helm" && helm dependency build . >/dev/null 2>&1 && \
        helm template x . -n "afrotransact-$ENV" -f values.yaml -f "values-$ENV.yaml" \
          -f "$ROOT/infra/argocd/envs/$ENV/platform-values.yaml" --set image.tag="$EXPECTED_SHA" \
        | kubeconform -ignore-missing-schemas -summary - >/dev/null ) \
        && ok "helm template + kubeconform: $d ($ENV)" || bad "helm template or kubeconform failed: $d ($ENV)"
    done
  fi

  say "CI state on $BRANCH (informational; core and afrotransact-go verify are manual-trigger and may never have run on this sha)"
  for w in core.yml afrotransact-go.yml uat-unverified-image.yml promote.yml; do
    printf '%-26s ' "$w"
    gh run list --repo "$REPO" --workflow "$w" --branch "$BRANCH" --limit 1 --json conclusion,headSha,createdAt \
      --jq 'if length==0 then "no run on this branch" else .[0]|"\(.conclusion // "running") on \(.headSha[0:9]) at \(.createdAt)" end' 2>/dev/null || echo "unreadable"
  done
  echo "The unverified UAT image path does not need verify. PROD does."

  say "Result"
  echo "$FAILS failure(s), $WARNINGS warning(s). Fix every FAIL before 'images'."
  [[ "$FAILS" -eq 0 ]]
}

latest_run_id() { # service sha -> run id of the newest dispatch of that workflow on this sha
  gh run list --repo "$REPO" --workflow "$1" --branch "$BRANCH" --event workflow_dispatch --limit 10 \
    --json databaseId,headSha --jq "[.[]|select(.headSha==\"$2\")][0].databaseId" 2>/dev/null
}

wait_run() { # workflow
  local id=""
  for _ in $(seq 1 12); do id="$(latest_run_id "$1" "$EXPECTED_SHA")"; [[ -n "$id" && "$id" != "null" ]] && break; sleep 5; done
  [[ -n "$id" && "$id" != "null" ]] || { bad "could not find the run of $1 for $EXPECTED_SHA"; return 1; }
  echo "Watching run $id of $1 (approve the 'uat' environment prompt on GitHub if it has reviewers)"
  gh run watch "$id" --repo "$REPO" --exit-status --interval 20 || { bad "run $id of $1 failed: gh run view $id --repo $REPO --log-failed"; return 1; }
  ok "run $id of $1 succeeded"
}

stage_images() {
  pin_sha
  say "Build UNVERIFIED UAT images for $EXPECTED_SHA (Trivy scan, SBOM, cosign signature; verify is skipped; UAT only)"
  echo "The image is marked org.afrotransact.verified=false in its signature. promote refuses it for prod."
  for s in "${SERVICES[@]}"; do
    cmd=(gh workflow run uat-unverified-image.yml --repo "$REPO" --ref "$BRANCH" -f "service=$s" -f skip_verify=true -f confirm_uat_only=UAT-ONLY-UNVERIFIED)
    if confirm "${cmd[*]}"; then "${cmd[@]}" || { bad "dispatch of $s failed"; return 1; }; else echo "skipped $s"; continue; fi
  done
  [[ "$DRY_RUN" == "1" ]] && return 0
  wait_run uat-unverified-image.yml || true
  say "Digests (read from the run summary; check the marking with cosign)"
  for s in "${SERVICES[@]}"; do
    ref="$GHCR/$s:$EXPECTED_SHA"
    echo "$ref"
    if command -v cosign >/dev/null && command -v jq >/dev/null; then
      cosign verify "$ref" --certificate-oidc-issuer https://token.actions.githubusercontent.com \
        --certificate-identity-regexp "^https://github\\.com/${REPO//./\\.}/\\.github/workflows/uat-unverified-image\\.yml@refs/heads/${BRANCH}\$" 2>/dev/null \
        | jq -r '.[].optional["org.afrotransact.verified"]' | grep -qx false \
        && ok "$s signature says verified=false, from the allowed workflow" || bad "$s: signature or marking check failed"
    else
      warn "cosign/jq not installed: run the cosign command in runbook section 4 by hand"
    fi
  done
  echo "Two services, one workflow: wait_run watched the newest run only. If the second is still running: gh run list --workflow uat-unverified-image.yml"
}

stage_promote() {
  pin_sha
  say "Promote the two images to uat only (writes image.tag and image.digest into values-uat.yaml on $BRANCH via the GitOps app)"
  for s in "${SERVICES[@]}"; do
    cmd=(gh workflow run promote.yml --repo "$REPO" --ref "$BRANCH" -f "service=$s" -f environment=uat -f "tag=$EXPECTED_SHA")
    if confirm "${cmd[*]}"; then "${cmd[@]}" || { bad "dispatch failed: $s"; return 1; }; else echo "skipped $s"; fi
  done
  [[ "$DRY_RUN" == "1" ]] && return 0
  wait_run promote.yml || true
  say "Re-read $BRANCH and check the pins"
  git fetch origin "refs/heads/${BRANCH}:refs/remotes/origin/${BRANCH}" >/dev/null 2>&1
  echo "The promote commit moved $BRANCH, so update your checkout: git -C <worktree> merge --ff-only origin/${BRANCH}"
  echo "Then: python3 infra/argocd/scripts/check_launch_set.py --env uat --require-tags   (must print OK; values-uat.yaml has image.unverified: true)"
}

stage_guide() {
  cat <<'EOF'

OWNER-ONLY STEPS THAT REMAIN (docs/runbooks/release-1-go-live.md). Each one prints no secret. Stop at any failed check.

 A. Accounts (section 1, in parallel, start now): OVH US project, Layerbase Postgres + Valkey for uat, Cloudflare token + R2,
    Resend domain, Stripe TEST keys, OpenRouter key with a spend limit. Route 53: change nothing.
 B. Section 4.3: this script, stages `images` then `promote`.
 C. Section 5.1-5.2: OVH cluster with tofu, kubeconfig; check ONE Ready node and >= 13 GiB allocatable memory, a default StorageClass.
 D. Section 5.4: Layerbase bootstrap (infra/ops/postgres/release-1-bootstrap.sh) with PGHOST/PGUSER/PGPASSWORD in your shell only;
    it must end with the verify passing and postgis + btree_gist listed for both `core` and `go`.
 E. Section 5.5: Valkey: ask for volatile-lru (or allkeys-lru), never noeviction; `redis-cli ... ping` -> PONG.
 F. Section 5.6: Cloudflare tofu (tunnel, DNS, Workers routes, R2); then CLOUDFLARED_TUNNEL_TOKEN.
 G. Section 5.7: Argo CD install, `kubectl apply -n argocd -f infra/argocd/bootstrap/root-uat.yaml`.
 H. Section 6.1-6.2: render and encrypt secrets (secrets.sh, sops, apply.sh); run `infra/ops/secrets/escrow.sh create` and store it OFFLINE.
 I. Section 7.1: sync platform apps in order (namespaces, envoy-gateway, otel gateway, edge, lean), waiting for Healthy.
 J. Section 7.3: `argocd app diff` then `argocd app sync` afrotransact-core-uat, then afrotransact-go-uat. Core runs Flyway for every module on first start.
 K. Section 6.3.7-6.3.9: create the three Stripe TEST webhook endpoints AFTER the routes answer, put the whsec_ values in, re-apply, restart core.
 L. Section 7.4: the three Workers (Actions > web-storefront / web-seller-center / web-staff-console > environment uat) and the docs portal.
 M. This script: `smoke`. Then section 8, every line, with the date. Stop after the dry run; PROD is a separate go/no-go.

If a step fails: do not fix forward blindly. Roll back with the line under that step in the runbook (re-promote the previous sha, or scale core to 0).
EOF
}

stage_smoke() { ENV=uat tools/smoke/go-live.sh; }

case "${1:-}" in
  check)   stage_check ;;
  images)  stage_images ;;
  promote) stage_promote ;;
  guide)   stage_guide ;;
  smoke)   stage_smoke ;;
  *) sed -n '2,11p' "$0"; exit 0 ;;
esac
