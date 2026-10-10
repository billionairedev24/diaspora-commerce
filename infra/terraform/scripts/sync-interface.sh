#!/usr/bin/env bash
# Keep the three cloud module sets on one interface.
#   - modules/context.tf.tmpl              -> modules/<cloud>/<module>/context.tf
#   - modules/_interface/<module>/variables.tf -> modules/<cloud>/<module>/variables.tf
#   - output NAMES must be identical across clouds (values are cloud specific)
#   - envs/dev/{main,providers,variables,outputs}.tf -> envs/{uat,prod}/
# Usage: scripts/sync-interface.sh          # copy
#        scripts/sync-interface.sh --check  # CI: fail on drift
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
mode="${1:-copy}"
status=0
clouds=(aws gcp azure)

sync() { # src dst
  if [[ "$mode" == "--check" ]]; then
    if ! cmp -s "$1" "$2"; then echo "drift: ${2#"$root"/}" >&2; status=1; fi
  else
    cp "$1" "$2"
  fi
}

for iface in "$root"/modules/_interface/*/; do
  module="$(basename "$iface")"
  for cloud in "${clouds[@]}"; do
    dir="$root/modules/$cloud/$module"
    [[ -d "$dir" ]] || { echo "missing module: modules/$cloud/$module" >&2; status=1; continue; }
    sync "$root/modules/context.tf.tmpl" "$dir/context.tf"
    sync "$iface/variables.tf" "$dir/variables.tf"
  done
  # Output names must match across clouds.
  ref=""
  for cloud in "${clouds[@]}"; do
    names="$( (grep -hoE '^output "[^"]+"' "$root/modules/$cloud/$module/outputs.tf" 2>/dev/null || true) | sort | tr '\n' ' ')"
    if [[ -z "$ref" ]]; then ref="$names"; elif [[ "$names" != "$ref" ]]; then
      echo "output mismatch in $module: $cloud has [$names] vs aws [$ref]" >&2; status=1
    fi
  done
done
# Environment roots: everything except sizing.tf, backend/tfvars examples is identical (dev is the source).
for env in uat prod; do
  for f in main.tf providers.tf variables.tf outputs.tf; do
    sync "$root/envs/dev/$f" "$root/envs/$env/$f"
  done
done
exit "$status"
