#!/usr/bin/env bash
# Rebuilds the identity pages' phone field bundle (libphonenumber-js mobile metadata, minified).
# Needs the web workspace installed (`pnpm install`) so libphonenumber-js resolves (override with NODE_PATH);
# esbuild is fetched on demand through npx.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
if [ -z "${NODE_PATH:-}" ]; then
  lib="$(cd "$root/web/packages/ui" && node -e "console.log(require('path').resolve(require.resolve('libphonenumber-js/package.json'),'../..'))")"
  export NODE_PATH="$lib"
fi
npx --yes esbuild@0.25.5 "$root/tools/identity/phone-field.src.js" --bundle --minify --format=iife --target=es2020 \
  --legal-comments=none --outfile="$root/java/services/identity/src/main/resources/static/assets/phone.js"
