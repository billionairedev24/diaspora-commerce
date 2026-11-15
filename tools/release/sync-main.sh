#!/usr/bin/env bash
# Merge origin/main into release-1 (ADR 0011). Run from a worktree that has release-1 checked out (or a local branch
# tracking origin/release-1). Never pushes: it prints the push command, so a person reads the merge first.
#
#   tools/release/sync-main.sh            merge origin/main, run the cheap checks, print what to review
#   tools/release/sync-main.sh --require-current   exit 1 when release-1 is behind main (CI, checkpoints)
#   tools/release/sync-main.sh --check    only report how far release-1 is behind main and which files would conflict
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

die() { echo "sync-main: $*" >&2; exit 1; }

command -v git >/dev/null || die "git is required"
git rev-parse --git-dir >/dev/null 2>&1 || die "not inside a git repository"

# Explicit refspec: a bare "git fetch origin <branch>" only updates FETCH_HEAD in some clones (shallow or single-branch).
git fetch origin "main:refs/remotes/origin/main" "${TARGET_BRANCH}:refs/remotes/origin/${TARGET_BRANCH}" >/dev/null

current="$(git rev-parse --abbrev-ref HEAD)"
# Read the tracking config directly: single-branch clones have no refspec for release-1, so '@{u}' does not resolve.
merge_ref="$(git config --get "branch.${current}.merge" || true)"
if [[ "$merge_ref" != "refs/heads/${TARGET_BRANCH}" ]]; then
  die "HEAD ($current) does not track ${TARGET_BRANCH} (branch.${current}.merge='${merge_ref:-unset}'). Use a branch created from origin/${TARGET_BRANCH}."
fi

behind="$(git rev-list --count "HEAD..${BASE_BRANCH}")"
ahead="$(git rev-list --count "${BASE_BRANCH}..HEAD")"
echo "release-1 is ${behind} commit(s) behind ${BASE_BRANCH} and ${ahead} ahead."
[[ "$behind" -gt 0 ]] || { echo "Nothing to merge."; exit 0; }

# Dry run of the merge: which files would conflict, without touching the work tree.
conflicts="$(git merge-tree --write-tree --name-only --no-messages "HEAD" "${BASE_BRANCH}" 2>/dev/null | tail -n +2 || true)"
if [[ -n "$conflicts" ]]; then
  echo "Files that would conflict:"
  echo "$conflicts" | sed 's/^/  /'
else
  echo "No conflicts predicted."
fi

if [[ "${1:-}" == "--check" ]]; then exit 0; fi
if [[ "${1:-}" == "--require-current" ]]; then
  # For CI and checkpoints: release-1 must contain all of main.
  [[ "$behind" -eq 0 ]] || die "release-1 is ${behind} commit(s) behind ${BASE_BRANCH}: run tools/release/sync-main.sh"
  exit 0
fi

[[ -z "$(git status --porcelain --untracked-files=no)" ]] || die "work tree has uncommitted changes; commit or stash them first"

git merge --no-ff "${BASE_BRANCH}" -m "Merge main into release-1" || {
  echo
  echo "Merge stopped on conflicts. Resolve them (guidance at the top of this script), then: git add <files> && git commit"
  exit 2
}

# Cheap checks only (no full build: disk and time). The CI workflow for release-1 runs the full suite.
echo "Merged. Cheap checks:"
bash -n tools/release/sync-main.sh && echo "  script syntax ok"
if [[ -f java/apps/core/pom.xml ]]; then
  echo "  new migrations from main (check each module has its own V<n>, none renumbered):"
  git diff --name-status "ORIG_HEAD" HEAD -- 'java/services/*/src/main/resources/db/migration/*' | sed 's/^/    /' || true
fi
# Consolidated modules (the ones java/apps/core mounts) whose wiring main touched must be adapted, not just merged.
mounted="$(sed -n 's/.*basePackage = "com.afrotransact.\([a-z]*\)".*/\1/p' java/apps/core/src/main/java/com/afrotransact/core/CoreApplication.java 2>/dev/null | tr '\n' ' ')"
if [[ -n "$mounted" ]]; then
  echo "  mounted modules: $mounted"
  for pkg in $mounted; do
    changed="$(git diff --name-only ORIG_HEAD HEAD -- "java/services/*/src/main/java/com/afrotransact/${pkg}/adapter/out" "java/services/*/src/main/java/com/afrotransact/${pkg}/config" 'java/services/*/pom.xml' 2>/dev/null || true)"
    [[ -z "$changed" ]] || { echo "  ADAPT: main changed outbound adapters, config or pom of '${pkg}' (add the in-process adapter or keep mode=http):"; echo "$changed" | sed 's/^/    /'; }
  done
fi
echo
echo "Review with: git log --oneline ORIG_HEAD..HEAD | head"
echo "Then push ONLY with: git push origin HEAD:${TARGET_BRANCH}"
