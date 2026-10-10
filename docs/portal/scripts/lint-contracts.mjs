#!/usr/bin/env node
// Offline contract lint (entry point: tools/contracts/lint_contracts.sh).
//   OpenAPI : every contracts/openapi/*.{yaml,yml,json} with the Redocly lint engine (@redocly/openapi-core,
//             the library behind `redocly lint`) and the root redocly.yaml.
//   AsyncAPI: every contracts/asyncapi/*.{yaml,yml} with @asyncapi/parser (schema + AsyncAPI ruleset).
// Usage: node scripts/lint-contracts.mjs [--warnings] [--json <file>] [name-filter ...]
// Exit code 1 when any contract has errors.
import { existsSync, readdirSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { Parser, fromFile } from '@asyncapi/parser';
import { getLineColLocation, lint, loadConfig } from '@redocly/openapi-core';

const PORTAL = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ROOT = resolve(PORTAL, '../..');
const args = process.argv.slice(2);
const showWarnings = args.includes('--warnings');
const jsonIdx = args.indexOf('--json');
const jsonOut = jsonIdx >= 0 ? args[jsonIdx + 1] : null;
const filters = args.filter((a, i) => !a.startsWith('--') && (jsonIdx < 0 || i !== jsonIdx + 1));
const match = (f) => !filters.length || filters.some((x) => f.includes(x));
const rel = (p) => relative(ROOT, p);
const color = process.stdout.isTTY ? (c, s) => `\u001b[${c}m${s}\u001b[0m` : (_c, s) => s;

const report = [];
let totalErrors = 0;
let totalWarnings = 0;

function print(file, problems) {
  const errors = problems.filter((p) => p.severity === 'error');
  const warnings = problems.filter((p) => p.severity === 'warn');
  totalErrors += errors.length;
  totalWarnings += warnings.length;
  const status = errors.length ? color(31, 'FAIL') : color(32, ' ok ');
  console.log(`${status} ${file}  ${errors.length} error(s), ${warnings.length} warning(s)`);
  const shown = showWarnings ? problems : errors;
  for (const p of shown) {
    const sev = p.severity === 'error' ? color(31, 'error') : color(33, 'warn ');
    console.log(`       ${sev} ${p.line ? `L${p.line}` : ''} ${p.rule}: ${p.message}${p.where ? `  (${p.where})` : ''}`);
  }
  if (!showWarnings && warnings.length) {
    const byRule = new Map();
    for (const w of warnings) byRule.set(w.rule, (byRule.get(w.rule) ?? 0) + 1);
    console.log(`       warnings by rule: ${[...byRule.entries()].map(([r, n]) => `${r} ×${n}`).join(', ')}`);
  }
  report.push({ file, errors: errors.length, warnings: warnings.length, problems });
}

// ── OpenAPI ──────────────────────────────────────────────────────────────────────────────────────
const openapiDir = join(ROOT, 'contracts/openapi');
const configPath = join(ROOT, 'redocly.yaml');
const config = await loadConfig(existsSync(configPath) ? { configPath } : {});
const specs = existsSync(openapiDir)
  ? readdirSync(openapiDir).filter((f) => /\.(ya?ml|json)$/.test(f) && match(f)).sort()
  : [];
console.log(`OpenAPI: ${specs.length} contract(s), rules from ${existsSync(configPath) ? 'redocly.yaml' : 'Redocly recommended'}\n`);
for (const f of specs) {
  const ref = join(openapiDir, f);
  let problems;
  try {
    const results = await lint({ ref, config });
    problems = results.map((p) => {
      const loc = p.location?.[0];
      let line;
      try {
        line = loc ? getLineColLocation(loc).start.line : undefined;
      } catch {
        line = undefined;
      }
      return { severity: p.severity, rule: p.ruleId, message: p.message.replace(/\s+/g, ' '), where: loc?.pointer, line };
    });
  } catch (e) {
    problems = [{ severity: 'error', rule: 'parse', message: String(e.message ?? e).split('\n')[0] }];
  }
  print(`contracts/openapi/${f}`, problems);
}

// ── AsyncAPI ─────────────────────────────────────────────────────────────────────────────────────
const asyncDir = join(ROOT, 'contracts/asyncapi');
const asyncDocs = existsSync(asyncDir) ? readdirSync(asyncDir).filter((f) => /\.ya?ml$/.test(f) && match(f)).sort() : [];
if (asyncDocs.length) console.log(`\nAsyncAPI: ${asyncDocs.length} document(s), @asyncapi/parser\n`);
const parser = new Parser();
const SEVERITY = ['error', 'warn', 'info', 'hint'];
for (const f of asyncDocs) {
  const path = join(asyncDir, f);
  let problems;
  try {
    const { diagnostics } = await fromFile(parser, path).parse();
    problems = diagnostics
      .filter((d) => d.severity <= 1)
      .map((d) => ({
        severity: SEVERITY[d.severity] ?? 'warn',
        rule: String(d.code),
        message: d.message.replace(/\s+/g, ' '),
        where: Array.isArray(d.path) && d.path.length ? `#/${d.path.join('/')}` : undefined,
        line: d.range ? d.range.start.line + 1 : undefined,
      }));
  } catch (e) {
    problems = [{ severity: 'error', rule: 'parse', message: String(e.message ?? e).split('\n')[0] }];
  }
  print(`contracts/asyncapi/${basename(f)}`, problems);
}

console.log(`\n${totalErrors ? color(31, 'FAILED') : color(32, 'PASSED')}: ${report.length} contract(s), ${totalErrors} error(s), ${totalWarnings} warning(s)`);
if (jsonOut) writeFileSync(resolve(process.cwd(), jsonOut), JSON.stringify(report, null, 2));
process.exit(totalErrors ? 1 : 0);
