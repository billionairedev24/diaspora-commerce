#!/usr/bin/env bash
# Runs the visual tests inside the same pinned Playwright image CI uses (mcr.microsoft.com, not Docker Hub), so
# local results and baselines are pixel-identical to CI. Font rasterisation and system fallback fonts differ between
# hosts: outside this image ~1% of pixels differ on about a fifth of the stories.
#
#   scripts/visual-docker.sh                          # compare   (= pnpm visual:docker)
#   scripts/visual-docker.sh --update-snapshots=all   # rewrite   (= pnpm visual:update:docker)
#
# Needs storybook-static/ (pnpm build-storybook) and installed node_modules (pnpm install) on the host.
set -euo pipefail

IMAGE="${PLAYWRIGHT_IMAGE:-mcr.microsoft.com/playwright:v1.56.1-noble}"
pkg_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_root="$(cd "$pkg_dir/../../.." && pwd)"

exec docker run --rm --network host --ipc=host \
  -e VISUAL_MAX_DIFF_PIXEL_RATIO -e VISUAL_THRESHOLD -e VISUAL_PORT \
  -v "$repo_root:$repo_root" -w "$pkg_dir" \
  "$IMAGE" node node_modules/@playwright/test/cli.js test -c visual/playwright.config.ts "$@"
