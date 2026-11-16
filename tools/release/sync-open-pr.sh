#!/usr/bin/env bash
# Opens the pull request that carries main (or develop) into release-1. Called by .github/workflows/release-1-sync.yml; also runnable by hand
# with an authenticated `gh` (it is a dry run with --dry-run: nothing is pushed, no PR or issue is made).
#
#   tools/release/sync-open-pr.sh origin/main [--dry-run]
#   tools/release/sync-open-pr.sh origin/develop
#
# What it does, in order:
#   1. cuts the throwaway branch sync/<name>-<short sha of the ref> from origin/release-1 (never reuses or force-pushes a branch);
#   2. skips when a sync/ pull request is already open, or the branch already exists on origin (one proposal at a time);
#   3. runs tools/release/sync-main.sh --ci on it (merge by row for backlog.csv, report every other conflict);
#   4. merged  -> pushes ONLY the sync/ branch, opens a PR into release-1 titled with the commits it carries (a DRAFT when parity.py found
#                 gaps: it cannot be merged until they are fixed) and, with SYNC_DISPATCH_CI=1, starts the release-1 workflows on the branch
#                 (a push made with GITHUB_TOKEN does not trigger pull_request workflows; workflow_dispatch it does);
#      conflicts -> opens or updates ONE issue (label release-1-sync) listing the files and the hints; nothing is pushed;
#      nothing to merge -> says so.
# It never pushes to release-1, main or develop and never forces. Needs: gh (GH_TOKEN), git. SYNC_OUT: a directory for the summary.
set -euo pipefail

REF="${1:?usage: sync-open-pr.sh origin/main|origin/develop [--dry-run]}"
DRY=0
[[ "${2:-}" != "--dry-run" ]] || DRY=1
GH="${GH:-gh}"
NAME="${REF#origin/}"
case "$NAME" in main|develop) ;; *) echo "sync-open-pr: only origin/main and origin/develop can be synced (got $REF)" >&2; exit 64 ;; esac
cd "$(git rev-parse --show-toplevel)"
SYNC_OUT="${SYNC_OUT:-$(mktemp -d)}"
export SYNC_OUT

git fetch origin "${NAME}:refs/remotes/origin/${NAME}" "release-1:refs/remotes/origin/release-1" >/dev/null
short="$(git rev-parse --short=10 "$REF")"
BRANCH="sync/${NAME}-${short}"
[[ "$BRANCH" == sync/* ]] || { echo "sync-open-pr: refusing a branch outside sync/" >&2; exit 1; }

open_sync="$("$GH" pr list --state open --base release-1 --json headRefName --jq '.[].headRefName | select(startswith("sync/"))' 2>/dev/null || true)"
if [[ -n "$open_sync" ]]; then
  echo "A sync pull request is already open (${open_sync//$'\n'/, }): merge or close it first. Nothing to do."
  exit 0
fi
if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
  echo "${BRANCH} already exists on origin (a closed proposal of the same ${NAME} commit). Nothing to do."
  exit 0
fi

git checkout -q -B "$BRANCH" origin/release-1
git config user.name "${GIT_AUTHOR_NAME:-release-1-sync[bot]}"
git config user.email "${GIT_AUTHOR_EMAIL:-release-1-sync@users.noreply.github.com}"

set +e
tools/release/sync-main.sh --ci --ref "$REF"
rc=$?
set -e
summary="$SYNC_OUT/summary.md"

case "$rc" in
  3) echo "release-1 already contains ${REF}: nothing to open."; exit 0 ;;
  2)
    title="release-1 sync blocked: ${NAME} conflicts with release-1"
    {
      echo "The scheduled sync of \`${REF}\` (\`$(git rev-parse --short "$REF")\`) into release-1 stopped on conflicts that need a person."
      echo "Nothing was pushed and nothing was guessed. Resolve them in a release-1 worktree with \`tools/release/sync-main.sh\`"
      echo "(or \`--ref origin/develop\`), push the result to a branch and open the pull request yourself."
      echo
      cat "$summary"
    } > "$SYNC_OUT/issue.md"
    if [[ "$DRY" -eq 1 ]]; then echo "[dry run] would open or update the issue: $title"; cat "$SYNC_OUT/issue.md"; exit 0; fi
    "$GH" label create release-1-sync --color 5319e7 --description "release-1 sync of main/develop" >/dev/null 2>&1 || true
    existing="$("$GH" issue list --state open --label release-1-sync --search "in:title \"$title\"" --json number --jq '.[0].number // empty' 2>/dev/null || true)"
    if [[ -n "$existing" ]]; then "$GH" issue edit "$existing" --body-file "$SYNC_OUT/issue.md" >/dev/null; echo "updated issue #$existing"
    else "$GH" issue create --title "$title" --body-file "$SYNC_OUT/issue.md" --label release-1-sync; fi
    echo "::warning::release-1 sync blocked by conflicts: see the issue"
    exit 0 ;;
  0) ;;
  *) echo "sync-open-pr: sync-main.sh failed (rc $rc)" >&2; exit "$rc" ;;
esac

n="$(git rev-list --no-merges --count origin/release-1..HEAD)"
subjects="$(git log --no-merges --format='%s' origin/release-1..HEAD | head -3 | sed 's/ *(#[0-9]*)$//' | paste -sd ';' | sed 's/;/; /g')"
title="Sync ${NAME} into release-1: ${n} commit(s) (${subjects})"
title="${title:0:240}"
draft=()
if grep -q '^## Parity gaps' "$summary" 2>/dev/null; then draft=(--draft); fi
{
  cat "$summary"
  echo
  echo "---"
  echo "Opened by \`.github/workflows/release-1-sync.yml\`. Review it like any pull request: the release-1 CI (core, afrotransact-go, release-1-parity) runs on this branch."
  echo "Done on main/develop is not done until release-1 parity is green (docs/engineering/release-1-parity.md)."
} > "$SYNC_OUT/pr-body.md"

if [[ "$DRY" -eq 1 ]]; then
  echo "[dry run] branch ${BRANCH} at $(git rev-parse --short HEAD); would push it and open: ${title} ${draft[*]:-}"
  cat "$SYNC_OUT/pr-body.md"
  exit 0
fi
git push origin "refs/heads/${BRANCH}:refs/heads/${BRANCH}"
"$GH" label create release-1-sync --color 5319e7 --description "release-1 sync of main/develop" >/dev/null 2>&1 || true
"$GH" pr create --base release-1 --head "$BRANCH" --title "$title" --body-file "$SYNC_OUT/pr-body.md" --label release-1-sync "${draft[@]}"
if [[ "${SYNC_DISPATCH_CI:-0}" == 1 ]]; then
  for wf in core.yml afrotransact-go.yml release-1-parity.yml; do
    "$GH" workflow run "$wf" --ref "$BRANCH" || echo "::warning::could not start ${wf} on ${BRANCH}; start it by hand"
  done
fi
