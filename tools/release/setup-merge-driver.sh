#!/usr/bin/env bash
# Enables the row-level merge driver for docs/backlog/backlog.csv in THIS clone (git config is local; .gitattributes is committed).
# After this, `git merge`, `git rebase` and `git cherry-pick` merge the backlog by Issue Id (tools/release/merge_backlog.py) instead of
# by line, so two sides that append rows no longer corrupt the file. A real conflict (same field changed two ways) stays a git conflict.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git config merge.backlog-csv.name "backlog.csv row merge (tools/release/merge_backlog.py)"
git config merge.backlog-csv.driver "python3 tools/release/merge_backlog.py --driver %O %A %B"
echo "merge driver backlog-csv configured for $(pwd)"
