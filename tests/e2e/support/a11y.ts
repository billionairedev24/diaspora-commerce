import { appendFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

import AxeBuilder from '@axe-core/playwright';
import { expect, type Page, type TestInfo } from '@playwright/test';

import { KNOWN_A11Y_ISSUES } from './a11y-known-issues';

/** WCAG 2.2 A/AA rule tags checked by the smoke scans. */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

/**
 * Runs axe on the current page and fails on serious/critical violations.
 *
 * - Minor/moderate findings are attached to the report (axe-results.json) without blocking.
 * - Serious/critical findings listed for this page in `a11y-known-issues.ts` (each with an owner) are reported as
 *   test annotations instead of failures, so the gate catches regressions while the backlog is burned down.
 */
export async function expectNoSeriousA11yViolations(page: Page, testInfo: TestInfo, opts: { page?: string; include?: string; exclude?: string[] } = {}) {
  let builder = new AxeBuilder({ page })
    .withTags(TAGS)
    // Next.js dev tools (dev mode only) render in a <nextjs-portal>; they are not part of the product.
    .exclude('nextjs-portal');
  if (opts.include) builder = builder.include(opts.include);
  for (const sel of opts.exclude ?? []) builder = builder.exclude(sel);
  const results = await builder.analyze();
  await testInfo.attach('axe-results.json', { body: JSON.stringify(results.violations, null, 2), contentType: 'application/json' });
  // E2E_A11Y_SUMMARY=<file.jsonl>: one line per scan with every violation (all impacts), for before/after reports.
  const summary = process.env.E2E_A11Y_SUMMARY;
  if (summary) {
    mkdirSync(dirname(summary), { recursive: true });
    const line = { project: testInfo.project.name, page: opts.page ?? page.url(), url: page.url(), violations: results.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.length, targets: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) })) };
    appendFileSync(summary, `${JSON.stringify(line)}\n`);
  }

  const known = KNOWN_A11Y_ISSUES.filter((k) => k.page === opts.page);
  const blocking: string[] = [];
  for (const v of results.violations) {
    if (v.impact !== 'serious' && v.impact !== 'critical') continue;
    const line = `${v.impact} ${v.id}: ${v.help} → ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`;
    const k = known.find((x) => x.rule === v.id);
    if (k) testInfo.annotations.push({ type: 'a11y-known-issue', description: `${line} (owner: ${k.owner}; ${k.note})` });
    else blocking.push(line);
  }
  expect(blocking, `axe serious/critical violations on ${page.url()}`).toEqual([]);
}
