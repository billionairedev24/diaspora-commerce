#!/usr/bin/env bash
# shellcheck disable=SC2164  # every `cd` below is inside a subshell on a directory this script just created
# Tests of tools/release/sync-main.sh and port-back.sh on throwaway repositories (a local bare repo as `origin`; no network, nothing is
# pushed anywhere real). Covers: both sides appending backlog rows, a backlog id collision (renumbered), a real conflict (reported, tree
# left clean in --ci), generated-workflow-only conflicts (regenerated), nothing to merge, the require-current guard, a dirty tree, and the
# port-back listing and cherry-pick.
#   bash tools/release/test_sync.sh
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REAL=$(cd "$HERE/../.." && pwd)
PASS=0 FAILN=0
ok() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
ko() { FAILN=$((FAILN + 1)); printf '  FAIL %s\n' "$1"; [ -z "${2:-}" ] || printf '%s\n' "$2" | sed 's/^/       | /'; }
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null

# --- fixture helpers -------------------------------------------------------------------------------------------------------------
csv_text() { python3 - "$@" <<'PY'
import sys; sys.path.insert(0, sys.argv[1]); import merge_backlog as m
rows = []
for spec in sys.argv[2:]:
    parts = spec.split(":", 2)
    i = parts[0]
    status = parts[1] if len(parts) > 1 else "To Do"
    ac = parts[2] if len(parts) > 2 else "- a\\n- b"
    typ = "Epic" if i.startswith("E") else "Story"
    parent = "" if typ == "Epic" else ("E37" if i.startswith("S6") else "E01")
    rows.append([i, typ, "summary " + i, "d", ac.replace("\\n", "\n"), parent, "summary" if typ == "Epic" else "", "High", "3", "x", "c", status])
sys.stdout.buffer.write(m.serialize(rows).encode())
PY
}
new_world() { # new_world NAME: bare origin + a clone `$W/NAME` on release-1 tracking origin/release-1
  local n="$1"
  git init -q --bare -b main "$W/$n.git"
  git clone -q "$W/$n.git" "$W/$n" 2>/dev/null
  ( cd "$W/$n"
    git checkout -q -b main
    mkdir -p tools/release tools/ci docs/backlog .github/workflows src
    cp "$REAL/tools/release/sync-main.sh" "$REAL/tools/release/merge_backlog.py" "$REAL/tools/release/port-back.sh" "$REAL/tools/release/sync-open-pr.sh" tools/release/
    printf '# fixture: the generated workflow\nname: gen\n' > .github/workflows/java-x.yml
    cat > tools/ci/gen_workflows.py <<'PY'
#!/usr/bin/env python3
import sys, pathlib
p = pathlib.Path(".github/workflows/java-x.yml"); want = "# GENERATED\nname: gen\n"
if "--check" in sys.argv: sys.exit(0 if p.read_text() == want else 1)
p.write_text(want)
PY
    printf 'docs/backlog/backlog.csv merge=backlog-csv -text\n' > .gitattributes
    csv_text "$PWD/tools/release" E01 E37 S001 > docs/backlog/backlog.csv
    echo one > src/a.txt; echo one > src/b.txt
    git add -A; git commit -q -m "base"
    git push -q origin main
    git checkout -q -b release-1
    git push -q origin release-1
    git branch -q --set-upstream-to=origin/release-1 release-1 2>/dev/null || git config branch.release-1.merge refs/heads/release-1
    git config branch.release-1.remote origin
    git config branch.main.remote origin; git config branch.main.merge refs/heads/main )
}
work() { # work NAME BRANCH MESSAGE  (shell commands on stdin, run in the clone after checking out BRANCH): commit, push BRANCH, back to release-1
  local script; script="$(cat)"
  ( cd "$W/$1" && git checkout -q "$2" && eval "$script" && git add -A && git commit -q -m "$3" && git push -q origin "$2" && git checkout -q release-1 )
}
sync() { ( cd "$W/$1" && shift && "$PWD/tools/release/sync-main.sh" "$@" ) ; }

# --- 1. both sides append backlog rows (multi-line fields), plus a clean file from main ----------------------------------------
echo "== both sides append backlog rows"
new_world w1
work w1 main "main: S002, S003 and a file" <<'SH'
csv_text "$PWD/tools/release" E01 E37 S001 S002 "S003:To Do:- m1\\n- m2\\n- m3" > docs/backlog/backlog.csv; echo two > src/new.txt
SH
work w1 release-1 "release-1: S600, S601" <<'SH'
csv_text "$PWD/tools/release" E01 E37 S001 "S600:To Do:- r1\\n- r2" "S601" > docs/backlog/backlog.csv
SH
out=$(SYNC_OUT="$W/out1" sync w1 --ci 2>&1); rc=$?
[ "$rc" = 0 ] && ok "merged (rc 0)" || ko "rc $rc" "$out"
( cd "$W/w1"; python3 tools/release/merge_backlog.py --validate docs/backlog/backlog.csv >/dev/null ) && ok "backlog valid after the merge" || ko "backlog invalid"
ids=$(cd "$W/w1" && python3 -c "
import sys; sys.path.insert(0,'tools/release'); import merge_backlog as m
print(' '.join(r[0] for r in m.parse(open('docs/backlog/backlog.csv',encoding='utf-8',newline='').read(),'x')))")
[ "$ids" = "E01 E37 S001 S002 S003 S600 S601" ] && ok "rows of both sides, main's first: $ids" || ko "ids: $ids"
[ -f "$W/w1/src/new.txt" ] && ok "main's file arrived" || ko "main's file missing"
[ "$(cd "$W/w1" && git log -1 --format=%s)" = "Merge main into release-1 (1 commit(s))" ] && ok "merge commit message" || ko "message: $(cd "$W/w1" && git log -1 --format=%s)"
grep -q "main: S002, S003 and a file" "$W/out1/summary.md" && ok "summary lists the commits main brought" || ko "summary" "$(cat "$W/out1/summary.md")"
[ "$(cd "$W/w1" && git rev-parse origin/release-1)" = "$(cd "$W/w1" && git rev-parse HEAD~1)" ] && ok "origin/release-1 was not pushed to" || ko "release-1 moved"

# --- 2. id collision: main takes S600, release-1 has S600 --------------------------------------------------------------------------
echo "== backlog id collision is renumbered in release-1's block"
new_world w2
work w2 main "main takes S600" <<'SH'
csv_text "$PWD/tools/release" E01 E37 S001 "S600:To Do:- main took it" > docs/backlog/backlog.csv
SH
work w2 release-1 "release-1 adds S600" <<'SH'
csv_text "$PWD/tools/release" E01 E37 S001 "S600:To Do:- release story" > docs/backlog/backlog.csv
SH
out=$(sync w2 --ci 2>&1); rc=$?
[ "$rc" = 0 ] && ok "merged (rc 0)" || ko "rc $rc" "$out"
printf '%s' "$out" | grep -q "RENUMBERED S600 -> S601" && ok "renumbering reported" || ko "no renumber report" "$out"
ac=$(cd "$W/w2" && python3 -c "
import sys; sys.path.insert(0,'tools/release'); import merge_backlog as m
r={x[0]:x for x in m.parse(open('docs/backlog/backlog.csv',encoding='utf-8',newline='').read(),'x')}
print(r['S600'][4].split(chr(10))[0], '/', r['S601'][4].split(chr(10))[0])")
[ "$ac" = "- main took it / - release story" ] && ok "main keeps S600, release-1's story is S601" || ko "S600/S601: $ac"

# --- 3. a real conflict: reported, nothing guessed, tree clean in --ci -------------------------------------------------------------
echo "== a source conflict is reported and the tree is left clean (--ci)"
new_world w3
work w3 main "main edits a" <<'SH'
echo main > src/a.txt
SH
work w3 release-1 "release edits a" <<'SH'
echo release > src/a.txt
SH
before=$(cd "$W/w3" && git rev-parse HEAD)
out=$(SYNC_OUT="$W/out3" sync w3 --ci 2>&1); rc=$?
[ "$rc" = 2 ] && ok "rc 2" || ko "rc $rc" "$out"
grep -q 'src/a.txt' "$W/out3/summary.md" && ok "summary names the file" || ko "summary" "$(cat "$W/out3/summary.md")"
[ "$(cd "$W/w3" && git rev-parse HEAD)" = "$before" ] && [ -z "$(cd "$W/w3" && git status --porcelain)" ] && ok "tree restored (merge aborted)" || ko "tree not clean"

echo "== a real conflict inside backlog.csv (same field two ways) is a conflict, not a guess"
new_world w3b
work w3b main "main: S001 Done" <<'SH'
csv_text "$PWD/tools/release" E01 E37 "S001:Done" > docs/backlog/backlog.csv
SH
work w3b release-1 "release-1: S001 In Progress" <<'SH'
csv_text "$PWD/tools/release" E01 E37 "S001:In Progress" > docs/backlog/backlog.csv
SH
out=$(SYNC_OUT="$W/out3b" sync w3b --ci 2>&1); rc=$?
[ "$rc" = 2 ] && ok "rc 2" || ko "rc $rc" "$out"
grep -q 'docs/backlog/backlog.csv' "$W/out3b/summary.md" && printf '%s' "$out" | grep -q 'S001' && ok "names the backlog and S001" || ko "report" "$out"

# --- 4. generated workflows only ---------------------------------------------------------------------------------------------------
echo "== only generated workflows conflict: regenerated"
new_world w4
work w4 main "main touches the generated file" <<'SH'
printf '# main\nname: gen\n' > .github/workflows/java-x.yml
SH
work w4 release-1 "release touches the generated file" <<'SH'
printf '# release\nname: gen\n' > .github/workflows/java-x.yml
SH
out=$(sync w4 --ci 2>&1); rc=$?
[ "$rc" = 0 ] && ok "merged (rc 0)" || ko "rc $rc" "$out"
[ "$(cat "$W/w4/.github/workflows/java-x.yml")" = "$(printf '# GENERATED\nname: gen\n')" ] && ok "file regenerated, no conflict markers" || ko "content" "$(cat "$W/w4/.github/workflows/java-x.yml")"

# --- 5. nothing to merge, require-current, dirty tree, tracking ----------------------------------------------------------------
echo "== guards"
new_world w5
out=$(sync w5 --ci 2>&1); rc=$?; [ "$rc" = 3 ] && ok "nothing to merge: rc 3 in --ci" || ko "rc $rc" "$out"
out=$(sync w5 2>&1); rc=$?; [ "$rc" = 0 ] && ok "nothing to merge: rc 0 interactive" || ko "rc $rc" "$out"
work w5 main "main moves" <<'SH'
echo m > src/b.txt
SH
out=$(sync w5 --require-current 2>&1); rc=$?; [ "$rc" = 1 ] && ok "--require-current fails when behind" || ko "rc $rc" "$out"
out=$(sync w5 --check 2>&1); rc=$?; [ "$rc" = 0 ] && printf '%s' "$out" | grep -q "1 commit(s) behind" && ok "--check reports and exits 0" || ko "rc $rc" "$out"
( cd "$W/w5"; git checkout -q release-1; echo dirty >> src/a.txt )
out=$(sync w5 2>&1); rc=$?; [ "$rc" = 1 ] && printf '%s' "$out" | grep -q "uncommitted" && ok "dirty tree refused" || ko "rc $rc" "$out"
( cd "$W/w5"; git checkout -q -- src/a.txt; git checkout -q -b not-tracking origin/release-1 --no-track )
out=$(sync w5 2>&1); rc=$?; [ "$rc" = 1 ] && printf '%s' "$out" | grep -q "does not track" && ok "a branch that does not track release-1 is refused" || ko "rc $rc" "$out"
out=$(sync w5 --ci 2>&1); rc=$?; [ "$rc" = 0 ] && ok "--ci needs no tracking" || ko "rc $rc" "$out"

echo "== develop"
new_world w6
( cd "$W/w6"; git checkout -q -b develop main; echo dev > src/dev.txt; git add -A; git commit -q -m "develop feature"; git push -q origin develop; git checkout -q release-1 )
out=$(sync w6 --ci --ref origin/develop 2>&1); rc=$?
[ "$rc" = 0 ] && [ -f "$W/w6/src/dev.txt" ] && ok "--ref origin/develop merges develop" || ko "rc $rc" "$out"
[ "$(cd "$W/w6" && git log -1 --format=%s)" = "Merge develop into release-1 (1 commit(s))" ] && ok "message names develop" || ko "message"

# --- 6. port-back ---------------------------------------------------------------------------------------------------------------
echo "== port-back"
new_world w7
work w7 release-1 "hotfix: b and a core file" <<'SH'
mkdir -p java/apps/core; echo core > java/apps/core/x.txt; echo hotfix > src/b.txt
SH
work w7 release-1 "core only change" <<'SH'
echo only > java/apps/core/y.txt
SH
work w7 release-1 "hotfix: a" <<'SH'
echo fix2 > src/a.txt
SH
out=$(cd "$W/w7" && tools/release/port-back.sh 2>&1)
printf '%s' "$out" | grep -q "hotfix: b and a core file" && printf '%s' "$out" | grep -q "release-1-only paths" && ok "lists the mixed commit with a warning" || ko "list" "$out"
printf '%s' "$out" | grep -q "core only change" && ko "release-1-only commit must not be listed" "$out" || ok "release-1-only commit not listed"
out=$(cd "$W/w7" && tools/release/port-back.sh --all 2>&1); printf '%s' "$out" | grep -q "skipped: touches only release-1-only paths" && ok "--all shows it as skipped" || ko "--all" "$out"
a=$(cd "$W/w7" && git log --format=%h --grep='hotfix: a' -1)
out=$(cd "$W/w7" && tools/release/port-back.sh "$a" 2>&1); rc=$?
[ "$rc" = 0 ] && ok "cherry-pick branch created (rc 0)" || ko "rc $rc" "$out"
[ "$(cd "$W/w7" && git rev-parse --abbrev-ref HEAD)" = "port/main-$a" ] && ok "branch port/main-$a" || ko "branch $(cd "$W/w7" && git rev-parse --abbrev-ref HEAD)"
( cd "$W/w7" && git log -1 --format=%B | grep -q "cherry picked from commit" ) && ok "-x records the release-1 sha" || ko "no -x trailer"
[ "$(cd "$W/w7" && git rev-parse origin/main)" = "$(cd "$W/w7" && git rev-parse HEAD~1)" ] && ok "branch is on origin/main, nothing pushed" || ko "base"

# --- 7. sync-open-pr.sh with a stub gh ----------------------------------------------------------------------------------------------
echo "== sync-open-pr.sh (stub gh)"
mkdir -p "$W/bin"
cat > "$W/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${GH_LOG:?}"
case "$1 $2" in
  "pr list") printf '%s' "${GH_OPEN_SYNC:-}" ;;
  "issue list") printf '%s' "${GH_OPEN_ISSUE:-}" ;;
esac
exit 0
STUB
chmod +x "$W/bin/gh"
export GH="$W/bin/gh" GH_LOG="$W/gh.log"
remote_heads() { git --git-dir="$W/$1.git" for-each-ref --format='%(refname:short)' refs/heads | tr '\n' ' '; }

new_world w8
work w8 main "main adds a file" <<'SH'
echo hi > src/new.txt
SH
r1=$(git --git-dir="$W/w8.git" rev-parse release-1); mn=$(git --git-dir="$W/w8.git" rev-parse main)
: > "$GH_LOG"
out=$(cd "$W/w8" && SYNC_DISPATCH_CI=1 tools/release/sync-open-pr.sh origin/main 2>&1); rc=$?
[ "$rc" = 0 ] && ok "clean sync: rc 0" || ko "rc $rc" "$out"
heads=$(remote_heads w8)
case "$heads" in *"sync/main-"*) ok "the sync/ branch was pushed" ;; *) ko "no sync branch on origin: $heads" ;; esac
[ "$(git --git-dir="$W/w8.git" rev-parse release-1)" = "$r1" ] && [ "$(git --git-dir="$W/w8.git" rev-parse main)" = "$mn" ] && ok "release-1 and main on origin untouched" || ko "origin branches moved"
grep -q '^pr create --base release-1 --head sync/main-' "$GH_LOG" && ok "PR opened into release-1" || ko "no pr create" "$(cat "$GH_LOG")"
grep -q 'title Sync main into release-1: 1 commit(s) (main adds a file)' <<<"$(tr '\n' ' ' < "$GH_LOG" | sed 's/--/\n--/g')" && ok "title carries the commit subjects" || ko "title" "$(cat "$GH_LOG")"
grep -q -- '--draft' "$GH_LOG" && ko "a clean sync must not be a draft" || ok "not a draft (no parity gaps)"
grep -q '^workflow run core.yml --ref sync/main-' "$GH_LOG" && grep -q '^workflow run release-1-parity.yml --ref sync/main-' "$GH_LOG" && ok "CI dispatched on the branch (SYNC_DISPATCH_CI=1)" || ko "no dispatch" "$(cat "$GH_LOG")"
grep -q -- '--force\|+refs' "$GH_LOG" && ko "force used" || ok "never forced"

echo "-- an open sync PR stops a second proposal"
new_world w9
work w9 main "main adds" <<'SH'
echo hi > src/new.txt
SH
: > "$GH_LOG"
out=$(cd "$W/w9" && GH_OPEN_SYNC="sync/main-abc" tools/release/sync-open-pr.sh origin/main 2>&1); rc=$?
[ "$rc" = 0 ] && printf '%s' "$out" | grep -q "already open" && ! grep -q 'pr create' "$GH_LOG" && ok "skipped, no second PR" || ko "rc $rc" "$out"

echo "-- conflicts open ONE issue and push nothing"
new_world w10
work w10 main "main edits a" <<'SH'
echo main > src/a.txt
SH
work w10 release-1 "release edits a" <<'SH'
echo release > src/a.txt
SH
: > "$GH_LOG"
out=$(cd "$W/w10" && tools/release/sync-open-pr.sh origin/main 2>&1); rc=$?
[ "$rc" = 0 ] && grep -q '^issue create --title release-1 sync blocked: main conflicts with release-1' "$GH_LOG" && ok "issue created" || ko "rc $rc" "$out$(cat "$GH_LOG")"
case "$(remote_heads w10)" in *sync/*) ko "a conflicting sync pushed a branch" ;; *) ok "nothing pushed" ;; esac
: > "$GH_LOG"
out=$(cd "$W/w10" && git checkout -q release-1 && GH_OPEN_ISSUE=7 tools/release/sync-open-pr.sh origin/main 2>&1); 
grep -q '^issue edit 7 --body-file' "$GH_LOG" && ok "an existing issue is updated, not duplicated" || ko "no issue edit" "$(cat "$GH_LOG")"

echo "-- dry run and refusals"
new_world w11
work w11 main "main adds" <<'SH'
echo hi > src/new.txt
SH
: > "$GH_LOG"
out=$(cd "$W/w11" && tools/release/sync-open-pr.sh origin/main --dry-run 2>&1); rc=$?
[ "$rc" = 0 ] && printf '%s' "$out" | grep -q "dry run" && ! grep -q 'pr create\|issue create' "$GH_LOG" && [ -z "$(remote_heads w11 | grep sync/)" ] && ok "dry run pushes and creates nothing" || ko "rc $rc" "$out"
out=$(cd "$W/w11" && tools/release/sync-open-pr.sh origin/release-1 2>&1); rc=$?
[ "$rc" = 64 ] && ok "only main and develop can be synced" || ko "rc $rc" "$out"
echo "-- a rejected push (workflow files) becomes an issue"
new_world w12
work w12 main "main adds" <<'SH'
echo hi > src/new.txt
SH
printf '#!/bin/sh\necho "refusing to allow a GitHub App to create or update workflow without workflows permission" >&2\nexit 1\n' > "$W/w12.git/hooks/pre-receive"; chmod +x "$W/w12.git/hooks/pre-receive"
: > "$GH_LOG"
out=$(cd "$W/w12" && tools/release/sync-open-pr.sh origin/main 2>&1); rc=$?
[ "$rc" = 0 ] && grep -q '^issue create --title release-1 sync could not push its branch: main' "$GH_LOG" && ! grep -q 'pr create' "$GH_LOG" && ok "issue, no PR, rc 0" || ko "rc $rc" "$out$(cat "$GH_LOG")"
printf '\npassed: %d, failed: %d\n' "$PASS" "$FAILN"
[ "$FAILN" = 0 ]
