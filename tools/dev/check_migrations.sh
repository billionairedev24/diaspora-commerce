#!/usr/bin/env bash
# Fails when a Flyway migration version is used twice in one service (two branches added the same Vn): the service
# would refuse to start with "Found more than one migration with version n".
set -euo pipefail
cd "$(dirname "$0")/../.."
status=0
for dir in java/services/*/src/main/resources/db/migration; do
  dup=$(ls "$dir" | sed -E 's/^(V[0-9]+)__.*/\1/' | sort | uniq -d || true)
  if [ -n "$dup" ]; then
    echo "Duplicate migration version in $dir: $(echo "$dup" | tr '\n' ' ')"
    status=1
  fi
done
exit $status
