#!/usr/bin/env bash
# Regenerate the TypeScript clients (types) for every BFF contract: contracts/openapi/gateway-bff*.yaml
# -> web/packages/api-client/src/generated/<contract>.ts
#
# Uses the SAME generator as web/packages/api-client/scripts/generate.mjs (the openapi-typescript installed in
# that package, astToString, identical banner), so files both produce are byte-identical. New BFF contracts are
# picked up by glob; nothing is hard-coded.
#
# Usage:
#   tools/contracts/generate_clients.sh            write the generated files
#   tools/contracts/generate_clients.sh --check    CI: exit 1 when any generated client is missing or stale
#   OUT_DIR=/tmp/clients tools/contracts/generate_clients.sh   write (or check) somewhere else
# Requires `pnpm install` first. Generated files are never edited by hand.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG="$ROOT/web/packages/api-client"
export GEN_ROOT="$ROOT"
export GEN_PKG="$PKG"
export GEN_OUT="${OUT_DIR:-$PKG/src/generated}"
export GEN_CHECK=0
for a in "$@"; do
  case "$a" in
    --check) GEN_CHECK=1 ;;
    -h|--help) sed -n '2,15p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "generate_clients: unknown argument $a" >&2; exit 2 ;;
  esac
done

if [[ ! -d "$PKG/node_modules/openapi-typescript" ]]; then
  echo "generate_clients: openapi-typescript is not installed in web/packages/api-client; run 'pnpm install' first." >&2
  exit 2
fi

node --input-type=module <<'JS'
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, relative } from 'node:path';
import { pathToFileURL } from 'node:url';

const root = process.env.GEN_ROOT;
const pkg = process.env.GEN_PKG;
const outDir = process.env.GEN_OUT;
const check = process.env.GEN_CHECK === '1';
// Resolve the ESM entry ("import" condition) of the package's own openapi-typescript, exactly what generate.mjs imports.
const require = createRequire(join(pkg, 'package.json'));
const otsPkgJson = require.resolve('openapi-typescript/package.json');
const otsPkg = JSON.parse(readFileSync(otsPkgJson, 'utf8'));
const entry = otsPkg.exports?.['.']?.import ?? otsPkg.module ?? otsPkg.main;
const { default: openapiTS, astToString } = await import(pathToFileURL(join(dirname(otsPkgJson), entry)).href);

const contracts = join(root, 'contracts/openapi');
const specs = readdirSync(contracts).filter((f) => /^gateway-bff.*\.ya?ml$/.test(f)).sort();
if (!specs.length) {
  console.log('no BFF contracts (contracts/openapi/gateway-bff*.yaml)');
  process.exit(0);
}
// Files that api-client's own generate.mjs also produces keep its banner, so both generators agree byte for byte.
const pkgScript = join(pkg, 'scripts/generate.mjs');
const pkgSpecs = existsSync(pkgScript) ? readFileSync(pkgScript, 'utf8') : '';
const banner = (spec) => {
  const regen = pkgSpecs.includes(`'${spec}'`) ? 'pnpm --filter @afrotransact/api-client generate' : 'tools/contracts/generate_clients.sh';
  return `// GENERATED from contracts/openapi/${spec} by openapi-typescript — do not edit by hand.\n// Regenerate with: ${regen}\n/* eslint-disable */\n\n`;
};

let stale = 0;
if (!check) mkdirSync(outDir, { recursive: true });
for (const spec of specs) {
  const out = spec.replace(/\.ya?ml$/, '.ts');
  const target = join(outDir, out);
  const shown = relative(root, target);
  let text;
  try {
    text = banner(spec) + astToString(await openapiTS(pathToFileURL(join(contracts, spec))));
  } catch (e) {
    console.error(`error: contracts/openapi/${spec}: ${String(e.message ?? e).split('\n')[0]}`);
    stale += 1;
    continue;
  }
  if (check) {
    const current = existsSync(target) ? readFileSync(target, 'utf8') : null;
    if (current === null) {
      console.error(`missing: ${shown} — run tools/contracts/generate_clients.sh`);
      stale += 1;
    } else if (current !== text) {
      console.error(`stale:   ${shown} — run tools/contracts/generate_clients.sh`);
      stale += 1;
    } else console.log(`ok:      ${shown}`);
  } else {
    const current = existsSync(target) ? readFileSync(target, 'utf8') : null;
    if (current === text) console.log(`unchanged ${shown}`);
    else {
      writeFileSync(target, text);
      console.log(`wrote     ${shown}`);
    }
  }
}
process.exit(stale ? 1 : 0);
JS
