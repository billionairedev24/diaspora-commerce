#!/usr/bin/env bash
# One-off (macOS): move from the EDB-installer Postgres (system daemon) to Homebrew's postgresql@17, so only one
# Postgres runs on :5432. Keeps the backup and the EDB data directory; deletes nothing.
#   tools/dev/one-postgres.sh /path/to/edb-backup.sql
# Bash 3.2 compatible.
set -uo pipefail

BACKUP="${1:-$HOME/edb-backup-$(date +%F).sql}"
FORMULA="${PG_FORMULA:-postgresql@17}"
LOG="$HOME/restore.log"

say() { printf '\n==> %s\n' "$*"; }
die() { printf '\nSTOPPED: %s\n' "$*" >&2; exit 1; }
psql_b() { psql -X -w -p 5432 -U "$(whoami)" -qtA "$@"; }

[[ "$(uname -s)" == Darwin ]] || die "this script is for macOS."
command -v brew >/dev/null || die "Homebrew not found."
command -v psql >/dev/null || die "psql not found (brew install libpq, or put $(brew --prefix "$FORMULA")/bin on PATH)."

say "1/7 Checking the backup: $BACKUP"
[[ -s "$BACKUP" ]] || die "backup file not found or empty."
tail -5 "$BACKUP" | grep -q "database cluster dump complete" || die "backup does not end with 'cluster dump complete': it looks cut off. Re-run pg_dumpall."
DBS="$(grep '^CREATE DATABASE ' "$BACKUP" | sed -E 's/^CREATE DATABASE "?([^" ]+)"? .*/\1/')"
echo "backup contains $(printf '%s\n' "$DBS" | grep -c .) databases"

say "2/7 Stopping the EDB Postgres (system daemon)"
PLIST="$(ls /Library/LaunchDaemons 2>/dev/null | grep -i postgres | head -1 || true)"
if [[ -n "$PLIST" ]]; then
  echo "found /Library/LaunchDaemons/$PLIST (sudo will ask for your password)"
  sudo launchctl bootout system "/Library/LaunchDaemons/$PLIST" 2>/dev/null || true
  sudo mv "/Library/LaunchDaemons/$PLIST" "$HOME/${PLIST}.disabled" && echo "moved to ~/${PLIST}.disabled (won't start at boot)"
fi
if lsof -nP -iTCP:5432 -sTCP:LISTEN >/dev/null 2>&1 || sudo lsof -nP -iTCP:5432 -sTCP:LISTEN >/dev/null 2>&1; then
  EDB=/Library/PostgreSQL/17
  if [[ -x "$EDB/bin/pg_ctl" ]]; then
    echo "still listening: stopping with pg_ctl"
    sudo -u postgres "$EDB/bin/pg_ctl" -D "$EDB/data" stop -m fast || true
  fi
fi

say "3/7 Waiting for port 5432 to be free"
i=0
while sudo lsof -nP -iTCP:5432 -sTCP:LISTEN >/dev/null 2>&1; do
  i=$((i + 1)); [[ $i -gt 20 ]] && die "something still listens on 5432: $(sudo lsof -nP -iTCP:5432 -sTCP:LISTEN | tail -1)"
  sleep 1
done
echo "free"

say "4/7 Starting Homebrew's $FORMULA"
brew services restart "$FORMULA" >/dev/null || die "brew services restart failed."
i=0
until psql_b -d postgres -c 'select 1' >/dev/null 2>&1; do
  i=$((i + 1)); [[ $i -gt 30 ]] && die "Homebrew Postgres did not come up. See: tail -30 $(brew --prefix)/var/log/${FORMULA}.log"
  sleep 1
done
psql_b -d postgres -c 'show server_version' | sed 's/^/server_version /'
[[ "$(psql_b -d postgres -c 'select rolsuper from pg_roles where rolname = current_user')" == t ]] \
  || die "your login ($(whoami)) is not a superuser on this server. Run: createuser -s $(whoami)   then re-run."

say "5/7 Checking nothing in the backup already exists here"
CLASH=""
for d in $DBS; do
  [[ "$d" == postgres || "$d" == template0 || "$d" == template1 ]] && continue
  if [[ "$(psql_b -d postgres -c "select 1 from pg_database where datname = '$d'")" == 1 ]]; then CLASH="$CLASH $d"; fi
done
[[ -z "$CLASH" ]] || die "these databases already exist on the Homebrew server, restoring would mix data:$CLASH. Drop or rename them (or tell Claude), then re-run."
echo "no clashes"

say "6/7 Restoring (log: $LOG)"
psql -X -w -p 5432 -U "$(whoami)" -d postgres -q -f "$BACKUP" >"$LOG" 2>&1
echo "errors in log: $(grep -c ERROR "$LOG")"
grep ERROR "$LOG" | sed -E 's/^[^E]*//' | sort | uniq -c | sort -rn | head -15

say "7/7 Result"
MISSING=""
for d in $DBS; do
  [[ "$(psql_b -d postgres -c "select 1 from pg_database where datname = '$d'")" == 1 ]] || MISSING="$MISSING $d"
done
psql_b -d postgres -c "select datname from pg_database where not datistemplate order by 1" | sed 's/^/  /'
if [[ -n "$MISSING" ]]; then
  echo; echo "NOT restored:$MISSING  (see $LOG)"; exit 1
fi
cat <<MSG

Done: one Postgres ($FORMULA) on :5432 with all $(printf '%s\n' "$DBS" | grep -c .) databases restored.
Kept: $BACKUP and /Library/PostgreSQL/17/data. Delete them only after your other projects have run fine for a while.
Now: make up
MSG
