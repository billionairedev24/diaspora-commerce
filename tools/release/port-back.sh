#!/usr/bin/env bash
# Port release-1-only commits back to main (or develop) so the two lines never drift (docs/runbooks/release-1-deploy.md, "Branch model").
# A hotfix is made on release-1 (the production line); this lists the commits release-1 has that main does not, and opens a branch with
# the ones you pick, cherry-picked onto origin/main, ready to push and turn into a pull request. It never pushes and never touches
# release-1, main or develop.
#
#   tools/release/port-back.sh                       list: release-1 commits not on origin/main that are not release-1-only by nature
#   tools/release/port-back.sh --all                 list everything, including release-1-only paths and merges of main
#   tools/release/port-back.sh <sha>... [--onto origin/develop] [--branch port/<name>]
#                                                    create the branch from origin/main and cherry-pick the given commits (oldest first)
#   tools/release/port-back.sh --pick-all            cherry-pick every listed commit
#
# Not listed (they cannot apply on main): merge commits, `Merge main into release-1`, bot pin commits (`deploy(...)`), and commits that touch
# ONLY release-1-only paths (java/apps/core, java/libs/module-*, go/apps, the deployable charts, the release-1 tools, docs/runbooks/release-1-*,
# ADR 0011, the sync/parity workflows). A commit that mixes both is listed with a warning: the cherry-pick may need hand-editing.
# On a conflict the cherry-pick stops, exactly like git: resolve, `git add`, `git cherry-pick --continue` (or --abort and tell the owner).
set -euo pipefail

ONTO="origin/main"
BRANCH=""
PICK_ALL=0
LIST_ALL=0
SHAS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --onto) ONTO="$2"; shift ;;
    --branch) BRANCH="$2"; shift ;;
    --pick-all) PICK_ALL=1 ;;
    --all) LIST_ALL=1 ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    -*) echo "port-back: unknown argument $1" >&2; exit 64 ;;
    *) SHAS+=("$1") ;;
  esac
  shift
done

die() { echo "port-back: $*" >&2; exit 1; }
git rev-parse --git-dir >/dev/null 2>&1 || die "not inside a git repository"
cd "$(git rev-parse --show-toplevel)"
ONTO_NAME="${ONTO#origin/}"
git fetch origin "${ONTO_NAME}:refs/remotes/origin/${ONTO_NAME}" "release-1:refs/remotes/origin/release-1" >/dev/null
SRC="origin/release-1"

# release-1-only paths (glob pathspecs): include form for "touches", exclude form for "touches anything else"
R1_GLOBS=('java/apps/core/**' 'java/libs/module-*/**' 'go/apps/**' 'docs/adr/0011-modular-monolith-release-1.md' 'docs/engineering/release-1-*'
  'docs/runbooks/release-1-*' 'tools/release/**' '.github/workflows/core.yml' '.github/workflows/afrotransact-go.yml'
  '.github/workflows/release-1-*.yml' 'infra/argocd/launch-set/release-1.yaml' 'go/Dockerfile.afrotransact-go'
  'infra/argocd/applicationsets/deployables.yaml')
ONLY_R1=(); EXCLUDE_R1=()
for g in "${R1_GLOBS[@]}"; do ONLY_R1+=(":(glob)$g"); EXCLUDE_R1+=(":(exclude,glob)$g"); done

candidates() {
  # Commits on release-1 that are not on the target (by patch id too: a commit already cherry-picked is not listed again).
  git log --no-merges --reverse --cherry-pick --right-only --format='%H' "${ONTO}...${SRC}"
}

is_listable() { # echo reason when NOT listable
  local sha="$1" subject files nonr1
  subject="$(git log -1 --format=%s "$sha")"
  case "$subject" in
    "Merge "*|"deploy("*|"release-1: carry main's new service settings"*) echo "release-1 machinery ($subject)"; return ;;
  esac
  files="$(git diff-tree --no-commit-id --name-only -r "$sha")"
  [[ -n "$files" ]] || { echo "empty"; return; }
  nonr1="$(git diff-tree --no-commit-id --name-only -r "$sha" -- . "${EXCLUDE_R1[@]}" | head -1)"
  [[ -n "$nonr1" ]] || echo "touches only release-1-only paths"
}

mixed_warning() {
  local sha="$1" r1
  r1="$(git diff-tree --no-commit-id --name-only -r "$sha" -- "${ONLY_R1[@]}" | head -1)"
  [[ -z "$r1" ]] || echo "  (also touches release-1-only paths such as ${r1}: the cherry-pick may need editing)"
}

if [[ "${#SHAS[@]}" -eq 0 && "$PICK_ALL" -eq 0 ]]; then
  n=0
  echo "release-1 commits not on ${ONTO}:"
  while read -r sha; do
    [[ -n "$sha" ]] || continue
    why="$(is_listable "$sha")"
    if [[ -n "$why" && "$LIST_ALL" -eq 0 ]]; then continue; fi
    n=$((n + 1))
    printf '  %s %s%s\n' "$(git rev-parse --short "$sha")" "$(git log -1 --format='%s' "$sha" | cut -c1-100)" "${why:+  [skipped: $why]}"
    [[ -n "$why" ]] || mixed_warning "$sha"
  done < <(candidates)
  echo "$n commit(s). Port with: tools/release/port-back.sh <sha>... (oldest first), or --pick-all"
  exit 0
fi

if [[ "$PICK_ALL" -eq 1 ]]; then
  while read -r sha; do
    [[ -n "$sha" ]] || continue
    [[ -z "$(is_listable "$sha")" ]] && SHAS+=("$sha")
  done < <(candidates)
fi
[[ "${#SHAS[@]}" -gt 0 ]] || die "nothing to port"
for sha in "${SHAS[@]}"; do
  git cat-file -e "${sha}^{commit}" 2>/dev/null || die "$sha is not a commit"
  git merge-base --is-ancestor "$sha" "$SRC" || die "$sha is not on release-1"
done

[[ -z "$(git status --porcelain --untracked-files=no)" ]] || die "work tree has uncommitted changes; use a clean worktree"
first="$(git rev-parse --short "${SHAS[0]}")"
BRANCH="${BRANCH:-port/${ONTO_NAME}-${first}}"
git rev-parse --verify -q "refs/heads/${BRANCH}" >/dev/null && die "branch ${BRANCH} already exists"
git checkout -q -b "$BRANCH" "$ONTO"
echo "Branch ${BRANCH} from ${ONTO}; cherry-picking ${#SHAS[@]} commit(s) with -x (the message records the release-1 sha)."
if ! git cherry-pick -x "${SHAS[@]}"; then
  echo
  echo "Cherry-pick stopped (conflict). Resolve, git add, git cherry-pick --continue; or git cherry-pick --abort and ask the owner."
  exit 2
fi
echo
echo "Ready: ${BRANCH} ($(git rev-list --count "${ONTO}..HEAD") commit(s) on ${ONTO})."
echo "Push the BRANCH (never main/develop/release-1 directly) and open the pull request:"
echo "  git push -u origin ${BRANCH}"
echo "  gh pr create --base ${ONTO_NAME} --head ${BRANCH} --title \"Port from release-1: $(git log -1 --format=%s | cut -c1-60)\" --body \"Ported from release-1 (hotfix flow, docs/runbooks/release-1-deploy.md, Branch model). Cherry-picked with -x.\""
