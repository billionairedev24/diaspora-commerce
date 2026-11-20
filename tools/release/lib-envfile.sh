# shellcheck shell=bash
# Shared by uat-env.sh and uat-github-setup.sh. SOURCE this file; it only defines functions.
# Reads a NAME=value file AS DATA (never `source`, never `eval`). Works with bash 3.2 (macOS): no associative arrays, no mapfile.

# envfile_get FILE NAME: prints the value of the LAST `NAME=` line with outer quotes and a trailing CR removed; nothing when absent or blank.
envfile_get() {
  local f="$1" n="$2" line v
  [[ -r "$f" ]] || return 0
  line="$(grep -E "^${n}=" "$f" 2>/dev/null | tail -n 1)" || true
  [[ -n "$line" ]] || return 0
  v="${line#*=}"
  v="${v%$'\r'}"
  case "$v" in
    \"*\") v="${v#\"}"; v="${v%\"}" ;;
    \'*\') v="${v#\'}"; v="${v%\'}" ;;
  esac
  printf '%s' "$v"
}

# envfile_names FILE: every NAME defined, one per line, in order, duplicates kept.
envfile_names() {
  grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$1" 2>/dev/null | sed -E 's/=.*//' || true
}

# envfile_inline_comment FILE NAME: true when the value is unquoted and contains " #" (probably an inline comment that would end up in the value).
envfile_inline_comment() {
  local f="$1" n="$2" line v
  line="$(grep -E "^${n}=" "$f" 2>/dev/null | tail -n 1)" || true
  [[ -n "$line" ]] || return 1
  v="${line#*=}"
  case "$v" in \"*|\'*) return 1 ;; esac
  [[ "$v" == *" #"* ]]
}
