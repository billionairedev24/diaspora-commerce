#!/usr/bin/env bash
# Sets the GitHub Actions variables and secrets the UAT PIPELINE needs (build, promote, web deploy, docs), with where to get each one.
# Run it yourself, on your machine, logged in with `gh auth login` as someone who can administer the repository.
#
#   tools/release/uat-github-setup.sh list      explains every item and where to get it (changes nothing, asks nothing)
#   tools/release/uat-github-setup.sh set       interactive: prompts for each missing item, sets it, never prints a value
#   tools/release/uat-github-setup.sh verify    names only: what is set and what is still missing (exit code = number missing)
#
# Options (environment variables): REPO=owner/name  FORCE=1 (re-prompt items that are already set)  DRY_RUN=1 (show what would be set, never prompt, never write)
#
# How secrets are handled, all enforced here:
#   * a secret is typed at a hidden prompt (no echo) or read from a file you name; it goes to `gh secret set` on STDIN, never as an
#     argument, never to the terminal, never to a file this script writes, never to shell history;
#   * nothing is read back (GitHub never returns secret values); `verify` compares NAMES only;
#   * Enter on an empty prompt skips the item;
#   * UAT only. This sets the 'uat' environment. PROD gets its own, separate values later (never reuse a UAT token in prod).
#
# NOT here, on purpose: Stripe, Resend, R2, Layerbase (Postgres/Valkey), the Cloudflare tunnel token, OpenRouter, Grafana, Backblaze, GHCR pull token.
# The pipeline never needs them; the CLUSTER does. They go through infra/ovh-vps/secrets.local.env, sops and apply.sh
# (docs/runbooks/release-1-go-live.md section 6). Keeping them out of GitHub keeps a leaked workflow token from reaching your payments.
set -uo pipefail

REPO="${REPO:-billionairedev24/diaspora-commerce}"
ENVNAME="uat"
FORCE="${FORCE:-0}"
DRY_RUN="${DRY_RUN:-0}"
TTY_IN="${SETUP_TTY:-/dev/tty}"      # tests point this at a file; people use the terminal
REGISTRY_DEFAULT="ghcr.io/$(printf '%s' "$REPO" | tr '[:upper:]' '[:lower:]')"

MISSING=0; SET=0; SKIPPED=0
say()  { printf '\n== %s\n' "$*"; }
ok()   { printf 'OK    %s\n' "$*"; }
miss() { printf 'MISS  %s\n' "$*"; MISSING=$((MISSING+1)); }
note() { printf '      %s\n' "$*"; }

need_gh() {
  command -v gh >/dev/null 2>&1 || { echo "gh is required: https://cli.github.com" >&2; exit 2; }
  gh auth status >/dev/null 2>&1 || { echo "gh is not logged in: run 'gh auth login' (account with admin rights on $REPO)" >&2; exit 2; }
  gh api "repos/$REPO" --jq .full_name >/dev/null 2>&1 || { echo "cannot read $REPO with this gh login" >&2; exit 2; }
}

# --- what exists (names only) -------------------------------------------------------------------------------------------------
env_exists()      { gh api "repos/$REPO/environments" --jq '.environments[].name' 2>/dev/null | grep -qx "$ENVNAME"; }
has_env_var()     { gh variable list --repo "$REPO" --env "$ENVNAME" --json name --jq '.[].name' 2>/dev/null | grep -qx "$1"; }
has_repo_var()    { gh variable list --repo "$REPO" --json name --jq '.[].name' 2>/dev/null | grep -qx "$1"; }
has_env_secret()  { gh secret list --repo "$REPO" --env "$ENVNAME" --json name --jq '.[].name' 2>/dev/null | grep -qx "$1"; }
has_repo_secret() { gh secret list --repo "$REPO" --json name --jq '.[].name' 2>/dev/null | grep -qx "$1"; }

# --- the catalogue: name | scope | kind | what it does | where to get it ---------------------------------------------------------
# scope: env = the 'uat' environment, repo = repository-wide. kind: var or secret.
explain() {
  case "$1" in
  CLOUD) cat <<'EOF'
What: tells the deploy workflow which cloud the environment runs on. Value for you: ovh. (Not a secret.)
Used by: promote.yml / _deploy.yml. Empty = images are built and signed but nothing is deployed.
Where: nowhere to look up, it is a fixed choice (aws | gcp | azure | ovh). OVH Managed Kubernetes is the plan.
EOF
  ;;
  REGISTRY_URL) cat <<'EOF'
What: the container registry the images are pushed to and the cluster pulls from. Value for you: ghcr.io/<owner>/<repo> in lower case. (Not a secret.)
Used by: promote.yml / _deploy.yml (writes it into the image pin in values-uat.yaml).
Where: it is derived from the repository name; the default offered is right unless you move the repo.
EOF
  ;;
  STORYBOOK_HOST) cat <<'EOF'
What: tells the Storybook workflow that Storybook is hosted on Vercel, so it must not build its own Cloudflare Pages copy. Value: vercel. (Not a secret.)
Used by: storybook.yml. Repository-wide variable. Skip it if you have not set up Vercel yet (that workflow stays red until you do).
Where: your decision; the Vercel project itself is a separate owner step (runbook section 7.4).
EOF
  ;;
  GITOPS_APP_ID) cat <<'EOF'
What: the numeric id of a GitHub App the pipeline uses to commit the image pin (image.tag and image.digest) back to release-1. The built-in
      workflow token cannot push past the branch rules; an App that you add as a bypass actor can. Keeps a personal token out of the pipeline.
Used by: _deploy.yml (promote). Repository-wide secret (the same App serves uat and prod).
Where, once (about 5 minutes):
  1. github.com > your avatar > Settings > Developer settings > GitHub Apps > New GitHub App (an organisation: the org's Settings > Developer settings).
  2. Name: afrotransact-gitops (any unique name). Homepage URL: your repo URL. Webhook: UNTICK Active.
  3. Repository permissions: Contents = Read and write; Pull requests = Read and write; Metadata = Read-only. Nothing else.
  4. Where can it be installed: Only on this account. Create GitHub App.
  5. The App ID is shown at the top of its About page (a number, for example 1234567). That is the value for this prompt.
  6. Left menu > Install App > Install on your account > Only select repositories > this repository.
  7. Repo Settings > Rules > Rulesets (or Branches) > the release-1 rule > Bypass list > add this App.
EOF
  ;;
  GITOPS_APP_PRIVATE_KEY) cat <<'EOF'
What: the private key of the same GitHub App. The pipeline signs a short-lived token with it.
Used by: _deploy.yml (promote). Repository-wide secret.
Where: on the App's page (step 5 above) > Private keys > Generate a private key. A .pem file downloads; give this script the PATH to it.
      Afterwards store the .pem in your password manager and delete it from Downloads. If it ever leaks: delete the key on the App page and generate a new one.
EOF
  ;;
  CLOUDFLARE_ACCOUNT_ID) cat <<'EOF'
What: your Cloudflare account id (not a secret in itself, stored as a secret so logs mask it). 32 hexadecimal characters.
Used by: the three web deploy workflows (wrangler deploy of storefront, seller center, staff console) and docs.yml.
Where: dash.cloudflare.com > Workers & Pages > the "Account ID" box on the right; or the 32-character part of the URL after dash.cloudflare.com/.
EOF
  ;;
  CLOUDFLARE_API_TOKEN) cat <<'EOF'
What: a Cloudflare API token that lets GitHub Actions deploy the three Workers (and the docs site).
Used by: the web-* deploy workflows (environment uat) and docs.yml.
Where: dash.cloudflare.com > My Profile > API Tokens > Create Token > Create Custom Token. Least privilege, a DEPLOY token (do not reuse your tofu token):
  Account > Workers Scripts > Edit; Account > Cloudflare Pages > Edit (docs); Zone > Workers Routes > Edit; Zone > Zone > Read.
  Account Resources: Include > your account. Zone Resources: Include > Specific zone > afrotransact.com. Add a TTL if you like. Copy the token ONCE.
  (Permission names follow the dashboard's wording at the time of writing; if a name differs, pick the closest Edit permission for Workers Scripts, Pages, Routes.)
  The tofu / infrastructure token (tunnel, DNS, R2, zone settings) is a different, stronger token that stays on your machine only.
EOF
  ;;
  esac
}

# --- setters ---------------------------------------------------------------------------------------------------------------------
set_var() { # name value scope
  if [[ "$DRY_RUN" == "1" ]]; then echo "DRY_RUN: would set variable $1 on $3 ($2)"; return 0; fi
  if [[ "$3" == "env" ]]; then gh variable set "$1" --repo "$REPO" --env "$ENVNAME" --body "$2"; else gh variable set "$1" --repo "$REPO" --body "$2"; fi \
    && { ok "variable $1 set ($3)"; SET=$((SET+1)); }
}

# secret value arrives on stdin of this function, goes to gh on stdin; never an argument
put_secret() { # name scope  (value on stdin)
  if [[ "$2" == "env" ]]; then gh secret set "$1" --repo "$REPO" --env "$ENVNAME"; else gh secret set "$1" --repo "$REPO"; fi >/dev/null \
    && { ok "secret $1 set ($2)"; SET=$((SET+1)); }
}

read_secret() { # prompt -> value in $REPLY_SECRET (hidden)
  local v=""
  if [[ -t 0 && "$TTY_IN" == "/dev/tty" ]]; then IFS= read -r -s -p "$1" v <&3; printf '\n'; else IFS= read -r v <&3; fi
  REPLY_SECRET="$v"
}
ask() { # prompt default -> $REPLY_TEXT
  local v=""; read -r -p "$1" v <&3; REPLY_TEXT="${v:-$2}"
}

already() { # kind name scope
  [[ "$FORCE" == "1" ]] && return 1
  case "$1:$3" in var:env) has_env_var "$2";; var:repo) has_repo_var "$2";; secret:env) has_env_secret "$2";; secret:repo) has_repo_secret "$2";; esac
}

do_var() { # name default scope optional(0/1)
  local n="$1" d="$2" s="$3" opt="${4:-0}"
  say "variable $n ($s)"
  if already var "$n" "$s"; then ok "$n already set; FORCE=1 to change"; SKIPPED=$((SKIPPED+1)); return; fi
  explain "$n"
  if [[ "$DRY_RUN" == "1" ]]; then set_var "$n" "$d" "$s"; return; fi
  if [[ "$opt" == "1" ]]; then ask "Value [$d] (type 'skip' to skip): " "$d"; else ask "Value [$d]: " "$d"; fi
  [[ "$REPLY_TEXT" == "skip" ]] && { echo "skipped"; SKIPPED=$((SKIPPED+1)); return; }
  set_var "$n" "$REPLY_TEXT" "$s"
}

do_secret() { # name scope validator(regex or "") file(0/1)
  local n="$1" s="$2" re="${3:-}" isfile="${4:-0}"
  say "secret $n ($s)"
  if already secret "$n" "$s"; then ok "$n already set; FORCE=1 to change"; SKIPPED=$((SKIPPED+1)); return; fi
  explain "$n"
  if [[ "$DRY_RUN" == "1" ]]; then echo "DRY_RUN: would prompt for and set secret $n on $s"; return; fi
  if [[ "$isfile" == "1" ]]; then
    ask "Path to the .pem file (Enter to skip): " ""
    [[ -z "$REPLY_TEXT" ]] && { echo "skipped"; SKIPPED=$((SKIPPED+1)); return; }
    local f="${REPLY_TEXT/#\~/$HOME}"
    [[ -r "$f" ]] || { echo "cannot read that file; skipped"; SKIPPED=$((SKIPPED+1)); return; }
    head -1 "$f" | grep -q -- '-----BEGIN .*PRIVATE KEY-----' || { echo "that does not look like a PEM private key; skipped"; SKIPPED=$((SKIPPED+1)); return; }
    put_secret "$n" "$s" <"$f"
    return
  fi
  read_secret "Value (hidden; Enter to skip): "
  local v="$REPLY_SECRET"; REPLY_SECRET=""
  [[ -z "$v" ]] && { echo "skipped"; SKIPPED=$((SKIPPED+1)); return; }
  if [[ -n "$re" && ! "$v" =~ $re ]]; then echo "that does not match the expected format ($re); NOT set. Run again."; SKIPPED=$((SKIPPED+1)); v=""; return; fi
  printf '%s' "$v" | put_secret "$n" "$s"
  v=""
}

stage_list() {
  for n in CLOUD REGISTRY_URL STORYBOOK_HOST GITOPS_APP_ID GITOPS_APP_PRIVATE_KEY CLOUDFLARE_ACCOUNT_ID CLOUDFLARE_API_TOKEN; do printf '\n---- %s\n' "$n"; explain "$n"; done
  cat <<'EOF'

---- Also set by `set` (settings, not values)
  * the 'uat' GitHub environment (created if missing). For PROD you will add required reviewers and a deployment-branch rule; UAT has none so the dry run is quick.
  * Settings > Actions > General > "Allow GitHub Actions to create and approve pull requests" (the promote step opens PRs).

---- NOT set here (cluster secrets, runbook section 6): STRIPE_*, RESEND_*, R2_*, EXTERNAL_PG_*, EXTERNAL_REDIS_URL, CLOUDFLARED_TUNNEL_TOKEN, OPENROUTER_API_KEY,
     GRAFANA_*, BACKUP_*, GHCR_USER / GHCR_TOKEN, ADMIN_*. They go into infra/ovh-vps/secrets.local.env (git-ignored) then sops and apply.sh.
EOF
}

stage_verify() {
  need_gh
  say "Verify (names only) for $REPO, environment $ENVNAME"
  env_exists && ok "environment $ENVNAME exists" || miss "environment $ENVNAME"
  has_env_var CLOUD        && ok "variable CLOUD (env)"        || miss "variable CLOUD on $ENVNAME"
  has_env_var REGISTRY_URL && ok "variable REGISTRY_URL (env)" || miss "variable REGISTRY_URL on $ENVNAME"
  has_repo_var STORYBOOK_HOST && ok "variable STORYBOOK_HOST (repo)" || note "optional: variable STORYBOOK_HOST not set (Storybook workflow stays red until it is)"
  for s in GITOPS_APP_ID GITOPS_APP_PRIVATE_KEY; do has_repo_secret "$s" && ok "secret $s (repo)" || miss "secret $s"; done
  for s in CLOUDFLARE_ACCOUNT_ID CLOUDFLARE_API_TOKEN; do
    if has_env_secret "$s" || has_repo_secret "$s"; then ok "secret $s"; else miss "secret $s on $ENVNAME"; fi
  done
  pr="$(gh api "repos/$REPO/actions/permissions/workflow" --jq .can_approve_pull_request_reviews 2>/dev/null || true)"
  [[ "$pr" == "true" ]] && ok "Actions may create and approve pull requests" || miss "Actions may not create/approve PRs (Settings > Actions > General)"
  echo; echo "$MISSING missing."
  [[ "$MISSING" -eq 0 ]]
}

stage_set() {
  need_gh
  if [[ "$DRY_RUN" != "1" ]]; then exec 3<"$TTY_IN" || { echo "no terminal to prompt on" >&2; exit 2; }; fi
  echo "Repository: $REPO   environment: $ENVNAME   (UAT only; nothing here is read back or printed)"
  [[ "$DRY_RUN" == "1" ]] && echo "DRY_RUN=1: nothing will be asked or written."

  say "Environment $ENVNAME"
  if env_exists; then ok "exists"; else
    if [[ "$DRY_RUN" == "1" ]]; then echo "DRY_RUN: would create environment $ENVNAME"; else
      ask "Create the '$ENVNAME' environment now? [Y/n]: " "y"
      [[ "$REPLY_TEXT" =~ ^[Yy] ]] && gh api -X PUT "repos/$REPO/environments/$ENVNAME" --silent && ok "environment created" || { echo "cannot continue without it"; exit 1; }
    fi
  fi

  say "Actions may create and approve pull requests"
  cur="$(gh api "repos/$REPO/actions/permissions/workflow" --jq .can_approve_pull_request_reviews 2>/dev/null || true)"
  if [[ "$cur" == "true" ]]; then ok "already on"; elif [[ "$DRY_RUN" == "1" ]]; then echo "DRY_RUN: would turn it on"; else
    ask "Turn it on now? (the promote step opens PRs) [Y/n]: " "y"
    if [[ "$REPLY_TEXT" =~ ^[Yy] ]]; then
      gh api -X PUT "repos/$REPO/actions/permissions/workflow" -f default_workflow_permissions=read -F can_approve_pull_request_reviews=true --silent \
        && ok "turned on (workflow tokens stay read-only by default)" || echo "could not set it: do it in Settings > Actions > General"
    fi
  fi

  do_var CLOUD ovh env
  do_var REGISTRY_URL "$REGISTRY_DEFAULT" env
  do_var STORYBOOK_HOST vercel repo 1
  do_secret GITOPS_APP_ID repo '^[0-9]{3,}$'
  do_secret GITOPS_APP_PRIVATE_KEY repo "" 1
  do_secret CLOUDFLARE_ACCOUNT_ID env '^[0-9a-fA-F]{32}$'
  do_secret CLOUDFLARE_API_TOKEN env '^[A-Za-z0-9_-]{30,}$'

  if [[ "$DRY_RUN" != "1" ]]; then
    say "docs.yml (the docs portal) has no environment, so it reads REPOSITORY secrets"
    ask "Also store the Cloudflare account id and token repository-wide for the docs workflow? Only do this if you accept that token at repo level [y/N]: " "n"
    if [[ "$REPLY_TEXT" =~ ^[Yy] ]]; then
      do_secret CLOUDFLARE_ACCOUNT_ID repo '^[0-9a-fA-F]{32}$'
      do_secret CLOUDFLARE_API_TOKEN repo '^[A-Za-z0-9_-]{30,}$'
    else
      echo "skipped: docs.yml will fail its deploy step until the two secrets exist at repo level (the site itself still builds)."
    fi
  fi

  say "Summary"
  echo "$SET set, $SKIPPED skipped/already set."
  [[ "$DRY_RUN" == "1" ]] || stage_verify || true
  cat <<'EOF'

Remaining GitHub steps this script cannot do: add the GitOps App as a bypass actor on the release-1 ruleset (explained under GITOPS_APP_ID);
for PROD later, required reviewers on the 'prod' environment and a deployment-branch rule of release-1.
Next: tools/release/uat-tonight.sh check
EOF
}

case "${1:-}" in
  list)   stage_list ;;
  set)    stage_set ;;
  verify) stage_verify ;;
  *) sed -n '2,25p' "$0"; exit 0 ;;
esac
