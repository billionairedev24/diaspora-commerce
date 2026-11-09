#!/usr/bin/env bash
# GitHub-side helpers shared by tools/ovh/gh-setup.sh and the guided wizard (tools/deploy/guide.sh). Sourced, never run.
# bash 3.2 compatible.

# gh_env_ensure <owner/repo> <environment>: the Environment with you as required reviewer and deployments only from main
# (OVH_GH_BRANCHES overrides, OVH_GH_REVIEWER sets another reviewer login). On a private repository without a paid plan GitHub
# refuses reviewers: the Environment is made without them and a warning says so.
gh_env_ensure() {
  local repo=$1 t=$2 reviewer reviewer_id body b
  reviewer=${OVH_GH_REVIEWER:-$(gh api user 2>/dev/null | jq -r '.login // empty')}
  [ -n "$reviewer" ] || { echo "cannot read your GitHub login (gh auth login?)" >&2; return 1; }
  reviewer_id=$(gh api "users/$reviewer" 2>/dev/null | jq -r '.id // empty')
  body=$(printf '{"reviewers":[{"type":"User","id":%s}],"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' "${reviewer_id:-0}")
  if printf '%s' "$body" | gh api -X PUT "repos/$repo/environments/$t" --input - >/dev/null 2>&1; then
    echo "environment $t: required reviewer $reviewer (every run waits for your approval)"
  else
    printf '%s' '{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' \
      | gh api -X PUT "repos/$repo/environments/$t" --input - >/dev/null 2>&1 \
      || { echo "GitHub refused to create the environment $t (admin rights? Environments in a private repository need a paid plan)" >&2; return 1; }
    echo "warning: GitHub refused a required reviewer on $t (plan?): anyone with write access can run the workflow; destroy still needs a typed confirmation" >&2
  fi
  for b in ${OVH_GH_BRANCHES:-main}; do
    gh api -X POST "repos/$repo/environments/$t/deployment-branch-policies" -f name="$b" -f type=branch >/dev/null 2>&1 || true
  done
  echo "environment $t: deployments from ${OVH_GH_BRANCHES:-main}"
}
