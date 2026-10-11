#!/usr/bin/env node
/*
 * Summarises a responsive audit run (specs/*.responsive.spec.ts with RESPONSIVE_OUT=<dir>) into <dir>/report.json and
 * <dir>/report.md: issue counts per app and width, then every distinct finding ranked by severity and how often it occurs.
 *
 *   RESPONSIVE_OUT=/tmp/resp pnpm --filter @afrotransact/e2e exec playwright test responsive
 *   node tests/e2e/scripts/responsive-report.mjs /tmp/resp [--compare /tmp/resp-before]
 */
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const WIDTHS = [320, 360, 390, 414, 768, 834, 1024, 1280, 1440];
const RANK = { critical: 0, major: 1, minor: 2 };

function load(dir) {
  const rows = [];
  if (!existsSync(dir)) return rows;
  for (const app of readdirSync(dir, { withFileTypes: true }).filter((d) => d.isDirectory())) {
    const jdir = join(dir, app.name, 'json');
    if (!existsSync(jdir)) continue;
    for (const f of readdirSync(jdir).filter((n) => n.endsWith('.json'))) rows.push(JSON.parse(readFileSync(join(jdir, f), 'utf8')));
  }
  return rows;
}

function counts(rows) {
  const out = {};
  for (const r of rows) {
    const app = (out[r.app] ??= {});
    const w = (app[r.width] ??= { pages: 0, critical: 0, major: 0, minor: 0, byCheck: {} });
    w.pages += 1;
    for (const i of r.issues) {
      w[i.severity] += 1;
      w.byCheck[i.check] = (w.byCheck[i.check] ?? 0) + 1;
    }
  }
  return out;
}

function findings(rows) {
  const map = new Map();
  for (const r of rows) {
    for (const i of r.issues) {
      const key = `${r.app}|${i.check}|${i.selector}`;
      const f = map.get(key) ?? { app: r.app, check: i.check, severity: i.severity, selector: i.selector, pages: new Set(), widths: new Set(), count: 0, example: i.detail };
      f.pages.add(r.label);
      f.widths.add(r.width);
      f.count += 1;
      map.set(key, f);
    }
  }
  return [...map.values()]
    .map((f) => ({ ...f, pages: [...f.pages].sort(), widths: [...f.widths].sort((a, b) => a - b) }))
    .sort((a, b) => RANK[a.severity] - RANK[b.severity] || b.count - a.count);
}

const dir = process.argv[2] ?? process.env.RESPONSIVE_OUT;
if (!dir) {
  console.error('usage: responsive-report.mjs <dir> [--compare <before-dir>]');
  process.exit(2);
}
const cmpIdx = process.argv.indexOf('--compare');
const beforeDir = cmpIdx > 0 ? process.argv[cmpIdx + 1] : null;

const rows = load(dir);
const table = counts(rows);
const ranked = findings(rows);
const before = beforeDir ? counts(load(beforeDir)) : null;
writeFileSync(join(dir, 'report.json'), JSON.stringify({ generatedAt: new Date().toISOString(), counts: table, findings: ranked }, null, 2));

const cell = (c, b) => {
  const now = `${c.critical}/${c.major}/${c.minor}`;
  return b ? `${b.critical}/${b.major}/${b.minor} → ${now}` : now;
};
let md = `# Responsive audit\n\n${rows.length} page renders. Cells: critical/major/minor issue counts${before ? ' (before → after)' : ''}.\n\n`;
for (const app of Object.keys(table).sort()) {
  md += `## ${app}\n\n| width | pages | issues | page-overflow | element-overflow | text-clipped | covered | sticky-tall | cramped cells | content pushed | tap <24 | tap <44 | font <12 | truncated |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n`;
  for (const w of WIDTHS) {
    const c = table[app][w];
    if (!c) continue;
    const b = before?.[app]?.[w];
    const k = (n) => c.byCheck[n] ?? 0;
    md += `| ${w} | ${c.pages} | ${cell(c, b)} | ${k('page-overflow')} | ${k('element-overflow')} | ${k('text-clipped')} | ${k('covered') + k('cta-covered')} | ${k('sticky-tall')} | ${k('cramped-cell')} | ${k('content-pushed')} | ${k('tap-target-tiny')} | ${k('tap-target')} | ${k('font-small')} | ${k('text-truncated')} |\n`;
  }
  md += '\n';
}
md += `## Findings (ranked)\n\n| severity | app | check | where | occurrences | widths | pages | example |\n|---|---|---|---|---|---|---|---|\n`;
for (const f of ranked.slice(0, 400)) {
  const pages = f.pages.length > 4 ? `${f.pages.slice(0, 4).join(', ')} +${f.pages.length - 4}` : f.pages.join(', ');
  md += `| ${f.severity} | ${f.app} | ${f.check} | \`${f.selector.replace(/\|/g, '\\|')}\` | ${f.count} | ${f.widths.join(' ')} | ${pages} | ${String(f.example).replace(/\|/g, '\\|').slice(0, 90)} |\n`;
}
writeFileSync(join(dir, 'report.md'), md);
console.log(`wrote ${join(dir, 'report.md')} (${ranked.length} distinct findings over ${rows.length} renders)`);
