#!/usr/bin/env bash
# Merge origin/main (or origin/develop) into release-1 (ADR 0011). Run from a worktree whose branch tracks release-1, or with --ci on a
# throwaway branch cut from origin/release-1 (what .github/workflows/release-1-sync.yml does). Never pushes: it prints the push command, or in
# --ci leaves the push to the workflow, which pushes a sync/ branch and opens a pull request, never release-1, main or develop.
#
#   tools/release/sync-main.sh                 merge origin/main, resolve what is mechanical, run the cheap checks, print what to review
#   tools/release/sync-main.sh --ref origin/develop      the same for develop (RELEASE_BASE=origin/develop does too)
#   tools/release/sync-main.sh --check         only report how far release-1 is behind main and which files would conflict
#   tools/release/sync-main.sh --require-current         exit 1 when release-1 is behind main (guard workflow, checkpoints)
#   tools/release/sync-main.sh --ci            throwaway branch, no tracking needed; writes $SYNC_OUT/summary.md; exit 0 merged / 3 nothing to
#                                              merge / 2 conflicts that need a person (the file list is in summary.md, the tree is left clean)
#
# What is resolved by itself (and nothing else is ever guessed):
#   * docs/backlog/backlog.csv: merged by row (tools/release/merge_backlog.py: append-only, release-1 rows renumbered into S600-S699 on an
#     id collision, same field changed two ways = conflict). Applied through the `backlog-csv` merge driver, set for this one merge only.
#   * generated workflows (.github/workflows/java-*.yml, go-*.yml, web-*.yml, promote.yml): when they are the ONLY conflicts and the
#     generator merged cleanly, tools/ci/gen_workflows.py rewrites them (a derived artefact; `--check` proves it).
# Every other conflict is REPORTED with its file and the guidance below, and the merge is aborted (--ci) or left for you (interactive).
#
# Conflict guidance (docs/engineering/release-1-architecture.md, "Keeping main in release-1"):
#   * Files that only exist on main (new migrations, services, docs) merge cleanly: take them.
#   * java/apps/core/**, java/libs/module-*/**, go/apps/** exist only on release-1: a conflict there is a bug in the merge.
#   * Service poms, application*.yml and *Configuration.java: keep BOTH sides; the release-1 side is the small
#     module-mode switch, never delete a main-side property.
#   * db/migration: never edit or renumber a migration to resolve a conflict; a clash of two V<n> means one side re-numbers
#     its NEW file (the one that is not yet applied anywhere).
set -euo pipefail

BASE_BRANCH="${RELEASE_BASE:-origin/main}"
TARGET_BRANCH="${RELEASE_BRANCH:-release-1}"
MODE=merge
CI=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) MODE=check ;;
    --require-current) MODE=current ;;
    --ci) CI=1 ;;
    --ref) BASE_BRANCH="$2"; shift ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "sync-main: unknown argument $1 (try --help)" >&2; exit 64 ;;
  esac
  shift
done

die() { echo "sync-main: $*" >&2; exit 1; }
command -v git >/dev/null || die "git is required"
git rev-parse --git-dir >/dev/null 2>&1 || die "not inside a git repository"
cd "$(git rev-parse --show-toplevel)"

BASE_NAME="${BASE_BRANCH#origin/}"
# Explicit refspecs: a bare "git fetch origin <branch>" only updates FETCH_HEAD in some clones (shallow or single-branch).
git fetch origin "${BASE_NAME}:refs/remotes/origin/${BASE_NAME}" "${TARGET_BRANCH}:refs/remotes/origin/${TARGET_BRANCH}" >/dev/null

if [[ "$CI" -eq 0 ]]; then
  current="$(git rev-parse --abbrev-ref HEAD)"
  # Read the tracking config directly: single-branch clones have no refspec for release-1, so '@{u}' does not resolve.
  merge_ref="$(git config --get "branch.${current}.merge" || true)"
  if [[ "$merge_ref" != "refs/heads/${TARGET_BRANCH}" ]]; then
    die "HEAD ($current) does not track ${TARGET_BRANCH} (branch.${current}.merge='${merge_ref:-unset}'). Use a branch created from origin/${TARGET_BRANCH}."
  fi
else
  [[ "$(git rev-parse HEAD)" == "$(git rev-parse "origin/${TARGET_BRANCH}")" ]] || die "--ci expects a branch cut from origin/${TARGET_BRANCH} (HEAD differs from it)"
fi

behind="$(git rev-list --count "HEAD..${BASE_BRANCH}")"
ahead="$(git rev-list --count "${BASE_BRANCH}..HEAD")"
echo "release-1 is ${behind} commit(s) behind ${BASE_BRANCH} and ${ahead} ahead."
SUMMARY="${SYNC_OUT:+$SYNC_OUT/summary.md}"
[[ -z "${SYNC_OUT:-}" ]] || { mkdir -p "$SYNC_OUT"; : > "$SUMMARY"; }
note() { [[ -z "$SUMMARY" ]] || printf '%s\n' "$*" >> "$SUMMARY"; }

if [[ "$MODE" == current ]]; then
  [[ "$behind" -eq 0 ]] || die "release-1 is ${behind} commit(s) behind ${BASE_BRANCH}: run tools/release/sync-main.sh"
  exit 0
fi
if [[ "$behind" -eq 0 ]]; then
  echo "Nothing to merge."
  note "Nothing to merge: release-1 contains ${BASE_BRANCH}."
  [[ "$CI" -eq 1 ]] && exit 3
  exit 0
fi

# Dry run of the merge: which files would conflict, without touching the work tree.
conflicts="$(git merge-tree --write-tree --name-only --no-messages "HEAD" "${BASE_BRANCH}" 2>/dev/null | tail -n +2 || true)"
if [[ -n "$conflicts" ]]; then
  echo "Files that would conflict (before the automatic resolution below):"
  echo "$conflicts" | sed 's/^/  /'
else
  echo "No conflicts predicted."
fi
[[ "$MODE" != check ]] || exit 0

[[ -z "$(git status --porcelain --untracked-files=no)" ]] || die "work tree has uncommitted changes; commit or stash them first"

merge_base="$(git merge-base HEAD "${BASE_BRANCH}")"
commits="$(git log --no-merges --format='- %h %s' "HEAD..${BASE_BRANCH}")"
ncommits="$(git rev-list --no-merges --count "HEAD..${BASE_BRANCH}")"
subject="Merge ${BASE_NAME} into release-1 (${ncommits} commit(s))"
body="$(printf '%s\n' "$commits" | head -200)"
[[ "$ncommits" -le 200 ]] || body="$body"$'\n'"- ... and $((ncommits - 200)) more"

# The backlog row merge runs as a merge driver for THIS merge only (git config -c: nothing is written to the clone's config).
set +e
git -c "merge.backlog-csv.name=backlog.csv row merge" \
    -c "merge.backlog-csv.driver=python3 tools/release/merge_backlog.py --driver %O %A %B" \
    merge --no-ff --no-commit "${BASE_BRANCH}" >/tmp/sync-merge.$$ 2>&1
rc=$?
set -e
cat /tmp/sync-merge.$$; rm -f /tmp/sync-merge.$$

if [[ "$rc" -ne 0 ]]; then
  [[ -n "$(git diff --name-only --diff-filter=U)" ]] || { git merge --abort 2>/dev/null || true; die "git merge failed without conflicts (see above)"; }
  mapfile -t unmerged < <(git diff --name-only --diff-filter=U)
  if [[ "${#unmerged[@]}" -gt 0 ]]; then
    # Generated workflows only: regenerate them from the (cleanly merged) generator.
    only_generated=1
    for f in "${unmerged[@]}"; do
      case "$f" in
        .github/workflows/java-*.yml|.github/workflows/go-*.yml|.github/workflows/web-*.yml|.github/workflows/promote.yml) ;;
        *) only_generated=0 ;;
      esac
    done
    if [[ "$only_generated" -eq 1 ]] && ! git diff --name-only --diff-filter=U | grep -q '^tools/ci/gen_workflows.py$'; then
      echo "Only generated workflows conflict: regenerating them with tools/ci/gen_workflows.py"
      git checkout --ours -- "${unmerged[@]}" 2>/dev/null || true
      python3 tools/ci/gen_workflows.py >/dev/null
      git add "${unmerged[@]}"
      python3 tools/ci/gen_workflows.py --check || die "regenerated workflows differ from the generator"
      unmerged=()
    fi
  fi
  if [[ "${#unmerged[@]}" -gt 0 ]]; then
    echo
    echo "Merge stopped: conflicts that need a person (nothing was guessed):"
    printf '  %s\n' "${unmerged[@]}"
    note "## Conflicts: release-1 could not take ${BASE_BRANCH} automatically"
    note ""
    note "Files (resolve in a release-1 worktree with \`tools/release/sync-main.sh\`; guidance in the header of that script):"
    for f in "${unmerged[@]}"; do
      hint=""
      case "$f" in
        docs/backlog/backlog.csv) hint=" (same field changed two ways on both sides: see the merge_backlog output in the job log; decide the value, edit the row by Issue Id)" ;;
        */db/migration/*) hint=" (two V<n> clash: re-number the NEW, unapplied one; never edit an applied migration)" ;;
        java/apps/core/*|java/libs/module-*|go/apps/*) hint=" (release-1-only path: a conflict here is a bug in the merge)" ;;
        */pom.xml|*application*.yml|*Configuration.java) hint=" (keep BOTH sides; release-1's side is the small module-mode switch)" ;;
        .github/workflows/*|tools/ci/gen_workflows.py) hint=" (re-run python3 tools/ci/gen_workflows.py after resolving the generator)" ;;
      esac
      note "- \`$f\`$hint"
    done
    if [[ "$CI" -eq 1 ]]; then
      git merge --abort || true
      exit 2
    fi
    echo
    echo "Resolve them (guidance at the top of this script), then: git add <files> && git commit -m \"$subject\""
    exit 2
  fi
fi

# Merge result in the index. Verify what a line-level merge could have damaged, then commit.
if git diff --cached --quiet && git diff --quiet; then
  git merge --abort 2>/dev/null || true
  echo "Merge produced no change."; note "The merge changes nothing (main's commits are already represented)."
  [[ "$CI" -eq 1 ]] && exit 3
  exit 0
fi
python3 tools/release/merge_backlog.py --validate docs/backlog/backlog.csv || die "docs/backlog/backlog.csv is invalid after the merge (see above); not committing"
git add -A -- docs/backlog/backlog.csv 2>/dev/null || true
git commit -q -m "$subject" -m "$body"
echo "Merged: $(git rev-parse --short HEAD)."

# Cheap checks only (no full build: disk and time). The CI workflows of release-1 run the full suites on the pull request.
note "## What main brought (${ncommits} commit(s))"
note ""
note "$body"
echo "Cheap checks:"
if [[ -f java/apps/core/pom.xml ]]; then
  echo "  new migrations from main (check each module has its own V<n>, none renumbered):"
  git diff --name-status "HEAD^1" HEAD -- 'java/services/*/src/main/resources/db/migration/*' | sed 's/^/    /' || true
fi
# Consolidated modules (the ones java/apps/core mounts) whose wiring main touched must be adapted, not just merged.
mounted="$(sed -n 's/.*basePackage = "com.afrotransact.\([a-z]*\)".*/\1/p' java/apps/core/src/main/java/com/afrotransact/core/CoreApplication.java 2>/dev/null | tr '\n' ' ' || true)"
if [[ -n "$mounted" ]]; then
  echo "  mounted modules: $mounted"
  for pkg in $mounted; do
    changed="$(git diff --name-only HEAD^1 HEAD -- "java/services/*/src/main/java/com/afrotransact/${pkg}/adapter/out" "java/services/*/src/main/java/com/afrotransact/${pkg}/config" 'java/services/*/pom.xml' 2>/dev/null || true)"
    [[ -z "$changed" ]] || { echo "  ADAPT: main changed outbound adapters, config or pom of '${pkg}' (add the in-process adapter or keep mode=http):"; echo "$changed" | sed 's/^/    /'; }
  done
fi
# Config of the service charts must reach the deployable charts: regenerate the generated blocks (idempotent), commit if they moved.
if [[ -f tools/release/chart_env_sync.py ]]; then
  python3 tools/release/chart_env_sync.py --write >/dev/null || true
  if ! git diff --quiet -- java/apps go/apps; then
    git add java/apps go/apps
    git commit -q -m "release-1: carry main's new service settings into the deployable charts (tools/release/chart_env_sync.py)"
    echo "  chart env sync: the deployable charts changed (committed)"
    note ""
    note "Config: main changed service chart settings; the deployable charts were regenerated (tools/release/chart_env_sync.py)."
  fi
fi
if [[ -f tools/release/parity.py ]]; then
  echo "  parity of what main changed:"
  if python3 tools/release/parity.py --main "${BASE_BRANCH}" --since "$merge_base" > /tmp/sync-parity.$$ 2>&1; then
    sed 's/^/    /' /tmp/sync-parity.$$ | tail -3
    note ""
    note "Parity: OK (tools/release/parity.py)"
  else
    sed 's/^/    /' /tmp/sync-parity.$$
    note ""
    note "## Parity gaps (the pull request cannot merge until they are fixed: add commits to this branch)"
    note ""
    python3 tools/release/parity.py --main "${BASE_BRANCH}" --since "$merge_base" --format markdown >> "$SUMMARY" 2>/dev/null || true
  fi
  rm -f /tmp/sync-parity.$$
fi
echo
if [[ "$CI" -eq 1 ]]; then
  echo "Done (--ci): the workflow pushes this branch and opens the pull request."
else
  echo "Review with: git log --oneline ORIG_HEAD..HEAD | head"
  echo "Then push ONLY with: git push origin HEAD:${TARGET_BRANCH}"
fi
