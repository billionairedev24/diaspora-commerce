#!/usr/bin/env bash
# Terminal UX of tools/deploy/guide.sh: colours, panels, progress, spinners, prompts. Sourced, never run. bash 3.2 (macOS).
# Colour has a meaning: cyan = information, green = pass, yellow = warning or cost, red = failure or destructive, dim = hint,
# bold = titles. Off when NO_COLOR is set, stdout is not a terminal, or TERM=dumb. GUIDE_ASCII=1 forces ASCII symbols and
# borders (also the default when the locale is not UTF-8). Prompts read from fd 3 (the terminal; GUIDE_TTY overrides it, for tests).

UI_COLOR=0 UI_TTY=0 UI_UTF=1 UI_W=78
C_RESET='' C_BOLD='' C_DIM='' C_RED='' C_GREEN='' C_YELLOW='' C_CYAN=''
S_OK='+' S_BAD='x' S_WARN='!' S_WAIT='~' S_DOT='-' B_H='-' B_V='|' B_TL='+' B_TR='+' B_BL='+' B_BR='+' B_FULL='#' B_EMPTY='.'

ui_init() {
  if [ -t 1 ] && [ "${TERM:-dumb}" != dumb ]; then UI_TTY=1; fi
  if [ "$UI_TTY" = 1 ] && [ -z "${NO_COLOR:-}" ]; then UI_COLOR=1; fi
  [ "${GUIDE_FORCE_COLOR:-}" != 1 ] || [ -n "${NO_COLOR:-}" ] || UI_COLOR=1
  case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in (*UTF-8* | *utf-8* | *UTF8* | *utf8*) UI_UTF=1 ;; (*) UI_UTF=0 ;; esac
  [ "${GUIDE_ASCII:-}" != 1 ] || UI_UTF=0
  if [ "$UI_COLOR" = 1 ]; then
    C_RESET=$'\033[0m' C_BOLD=$'\033[1m' C_DIM=$'\033[2m' C_RED=$'\033[31m' C_GREEN=$'\033[32m' C_YELLOW=$'\033[33m' C_CYAN=$'\033[36m'
  fi
  if [ "$UI_UTF" = 1 ]; then
    S_OK='✓' S_BAD='✗' S_WARN='▲' S_WAIT='…' S_DOT='·' B_H='─' B_V='│' B_TL='┌' B_TR='┐' B_BL='└' B_BR='┘' B_FULL='█' B_EMPTY='░'
  fi
  local cols=${COLUMNS:-}
  [ -n "$cols" ] || cols=$(tput cols 2>/dev/null || echo 80)
  case "$cols" in ('' | *[!0-9]*) cols=80 ;; esac
  UI_W=$((cols - 2)); [ "$UI_W" -le 78 ] || UI_W=78; [ "$UI_W" -ge 50 ] || UI_W=50
}

# ---- one-line messages ------------------------------------------------------------------------------------------------
say()  { printf '%s\n' "$*"; }
info() { printf '%s%s%s\n' "$C_CYAN" "$*" "$C_RESET"; }
ok()   { printf '  %s%s%s %s\n' "$C_GREEN" "$S_OK" "$C_RESET" "$*"; }
bad()  { printf '  %s%s %s%s\n' "$C_RED" "$S_BAD" "$*" "$C_RESET"; }
warn() { printf '  %s%s %s%s\n' "$C_YELLOW" "$S_WARN" "$*" "$C_RESET"; }
hint() { printf '    %s%s%s\n' "$C_DIM" "$*" "$C_RESET"; }
bold() { printf '%s%s%s\n' "$C_BOLD" "$*" "$C_RESET"; }
blank() { printf '\n'; }
rule() { local i line=''; for ((i = 0; i < UI_W; i++)); do line="$line$B_H"; done; printf '%s%s%s\n' "$C_DIM" "$line" "$C_RESET"; }

# PASS / FAIL / WARN line of a verify step.
verdict_pass() { printf '  %s%s PASS%s  %s\n' "$C_GREEN$C_BOLD" "$S_OK" "$C_RESET" "$*"; }
verdict_fail() { printf '  %s%s FAIL%s  %s\n' "$C_RED$C_BOLD" "$S_BAD" "$C_RESET" "$*"; }
verdict_warn() { printf '  %s%s WARN%s  %s\n' "$C_YELLOW$C_BOLD" "$S_WARN" "$C_RESET" "$*"; }

banner() {
  blank
  printf '%s  AfroTransact  %s  guided test-environment deploy%s\n' "$C_BOLD$C_CYAN" "$S_DOT" "$C_RESET"
  printf '%s  OVHcloud (backend, one k3s server) + Cloudflare (three web apps and DNS)%s\n' "$C_DIM" "$C_RESET"
  printf '%s  Quit any time with q; run it again and it resumes where you stopped.%s\n' "$C_DIM" "$C_RESET"
}

# header <n> <of> <title>: "Phase 3 of 9" and a progress bar.
header() {
  local n=$1 of=$2 title=$3 filled i bar=''
  filled=$((n * 20 / of))
  for ((i = 0; i < 20; i++)); do if [ "$i" -lt "$filled" ]; then bar="$bar$B_FULL"; else bar="$bar$B_EMPTY"; fi; done
  blank; rule
  printf '%s Phase %s of %s%s  %s%s%s   %s%s %s%%%s\n' "$C_BOLD$C_CYAN" "$n" "$of" "$C_RESET" "$C_BOLD" "$title" "$C_RESET" "$C_CYAN" "$bar" "$((n * 100 / of))" "$C_RESET"
  rule
}

# panel <TITLE> <cyan|yellow|red|green|dim>: the body comes on stdin, wrapped to the panel width.
panel() {
  local title=$1 col=$2 cc inner line pad i top='' body
  case "$col" in (red) cc=$C_RED ;; (yellow) cc=$C_YELLOW ;; (green) cc=$C_GREEN ;; (dim) cc=$C_DIM ;; (*) cc=$C_CYAN ;; esac
  inner=$((UI_W - 4))
  for ((i = 0; i < UI_W - 5 - ${#title}; i++)); do top="$top$B_H"; done
  printf '%s%s%s %s%s%s%s %s%s%s\n' "$cc" "$B_TL" "$B_H" "$C_BOLD" "$title" "$C_RESET" "$cc" "$top" "$B_TR" "$C_RESET"
  body=$(fold -s -w "$inner")
  while IFS= read -r line; do
    printf '%s%s%s %s' "$cc" "$B_V" "$C_RESET" "$line"
    pad=$((inner - ${#line})); [ "$pad" -gt 0 ] || pad=0
    printf '%*s %s%s%s\n' "$pad" '' "$cc" "$B_V" "$C_RESET"
  done <<EOF2
$body
EOF2
  top=''; for ((i = 0; i < UI_W - 2; i++)); do top="$top$B_H"; done
  printf '%s%s%s%s%s\n' "$cc" "$B_BL" "$top" "$B_BR" "$C_RESET"
}

# ---- waiting --------------------------------------------------------------------------------------------------------------
fmt_elapsed() { local s=$1; if [ "$s" -ge 60 ]; then printf '%sm%02ss' "$((s / 60))" "$((s % 60))"; else printf '%ss' "$s"; fi; }

# poll <label> <interval ticks> <max seconds> <command...>: runs the command every interval until it exits 0 (done) or max is
# reached (status 1). A spinner with the elapsed time on a terminal; a line every 30 s otherwise. GUIDE_SLEEP is the tick (s).
poll() {
  local label=$1 interval=$2 max=$3; shift 3
  local tick=${GUIDE_SLEEP:-1} start elapsed frames i=0 f t
  start=$(date +%s)
  if [ "$UI_UTF" = 1 ]; then frames='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'; else frames='|/-\'; fi
  [ "$UI_TTY" = 1 ] || printf '  %s %s\n' "$S_WAIT" "$label"
  while :; do
    if "$@"; then [ "$UI_TTY" != 1 ] || printf '\r\033[K'; return 0; fi
    elapsed=$(( $(date +%s) - start ))
    if [ "$elapsed" -ge "$max" ] || [ "${GUIDE_SLEEP:-1}" = 0 -a "$i" -ge "$max" ]; then [ "$UI_TTY" != 1 ] || printf '\r\033[K'; return 1; fi
    t=0
    while [ "$t" -lt "$interval" ]; do
      if [ "$UI_TTY" = 1 ]; then
        f=${frames:$((i % ${#frames})):1}
        printf '\r  %s%s%s %s  %s%s%s' "$C_CYAN" "$f" "$C_RESET" "$label" "$C_DIM" "$(fmt_elapsed $(( $(date +%s) - start )))" "$C_RESET"
      fi
      i=$((i + 1)); sleep "$tick"; t=$((t + 1))
    done
    [ "$UI_TTY" = 1 ] || { [ $((i % 30)) -ne 0 ] || printf '  %s still waiting (%s)\n' "$S_WAIT" "$(fmt_elapsed "$elapsed")"; }
  done
}

# ---- prompts (fd 3) -------------------------------------------------------------------------------------------------------
QUIT_HOOK=''
ui_open_input() {
  local src=${GUIDE_TTY:-/dev/tty}
  exec 3<"$src" 2>/dev/null || { echo "No terminal to ask questions on (needs /dev/tty; GUIDE_TTY=file is for tests)." >&2; return 1; }
}
quit_now() {
  blank; info "Stopped. Nothing is lost: run  make deploy-guide  again and it resumes. Progress: .ovh/deploy-guide.state"
  exit 0
}
# read_in <prompt>: the answer in $REPLY; q quits; end of input quits.
read_in() {
  printf '%s%s%s ' "$C_BOLD" "$1" "$C_RESET"
  if ! IFS= read -r -u 3 REPLY; then blank; quit_now; fi
  case "$REPLY" in (q | Q | quit) quit_now ;; esac
}
read_secret() {
  printf '%s%s%s ' "$C_BOLD" "$1" "$C_RESET"
  if ! IFS= read -r -s -u 3 REPLY; then blank; quit_now; fi
  printf '\n'
}
ASSUME_YES=0
# confirm_yn <question> [y|n default]: only for steps that cost nothing and change nothing outside this session; --yes answers it.
confirm_yn() {
  local def=${2:-n} hintp='[y/N]'
  [ "$def" != y ] || hintp='[Y/n]'
  if [ "$ASSUME_YES" = 1 ]; then say "$1 $hintp y (--yes)"; return 0; fi
  read_in "$1 $hintp"
  case "$REPLY" in (y | Y | yes | YES) return 0 ;; ('') [ "$def" = y ] && return 0 ;; esac
  return 1
}
# confirm_type <word> <question>: for billable, outward-facing or destructive steps; --yes never answers it.
confirm_type() {
  read_in "$2 Type  $1  to go ahead (anything else cancels):"
  [ "$REPLY" = "$1" ]
}
# wait_done <what>: something only the person can do (browser). Returns 2 when they ask for the steps again.
wait_done() {
  read_in "$1 Type  done  when finished, or  ?  to see the steps again:"
  case "$REPLY" in (done | DONE | Done) return 0 ;; esac
  return 2
}
# choose <title> <default number> <item>...: numbered menu (gum if installed and interactive). Sets CHOICE (1-based).
choose() {
  local title=$1 def=$2 n=0 it pick; shift 2
  if command -v gum >/dev/null 2>&1 && [ "$UI_TTY" = 1 ] && [ -z "${GUIDE_TTY:-}" ] && [ -z "${GUIDE_NO_GUM:-}" ]; then
    pick=$(gum choose --header "$title" "$@") || quit_now
    for it in "$@"; do n=$((n + 1)); [ "$it" != "$pick" ] || { CHOICE=$n; return 0; }; done
    n=0
  fi
  bold "$title"
  for it in "$@"; do n=$((n + 1)); printf '   %s%s)%s %s\n' "$C_CYAN" "$n" "$C_RESET" "$it"; done
  while :; do
    read_in "Choose 1-$n [$def]:"
    [ -n "$REPLY" ] || REPLY=$def
    case "$REPLY" in ('' | *[!0-9]*) ;; (*) if [ "$REPLY" -ge 1 ] && [ "$REPLY" -le "$n" ]; then CHOICE=$REPLY; return 0; fi ;; esac
    bad "enter a number from 1 to $n"
  done
}
# mask: hides anything that looks like a credential in text we echo from logs.
mask() { sed -E 's/(ghp_|github_pat_|sk_test_|sk_live_|rk_live_|pk_live_|whsec_|re_)[A-Za-z0-9_+\/=-]+/\1****/g; s/(Bearer |token[=:] ?)[A-Za-z0-9._-]{12,}/\1****/g'; }
