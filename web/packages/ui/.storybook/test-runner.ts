/*
 * @storybook/test-runner config (Jest + Playwright Chromium against the static build, see package.json
 * `test-storybook:static`). Every story is rendered and its `play` function run by the runner; a render error, a
 * failing `play` assertion or a console error fails the story. After that, postVisit runs axe (WCAG 2.2 A/AA, the
 * same tags as tests/e2e) and fails on serious/critical violations that are not in the known-issue allowlist.
 */
import { appendFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

import AxeBuilder from '@axe-core/playwright';
import { type TestRunnerConfig } from '@storybook/test-runner';

import { A11Y_TAGS, blockingViolations } from './a11y-known-issues.ts';

/** Known (allowed) and fixed (stale allowlist) a11y findings; `test-storybook:static` clears it before each run. */
const A11Y_LOG = resolve(process.env.A11Y_LOG ?? 'test-results/storybook-a11y.log');

const config: TestRunnerConfig = {
  tags: { exclude: ['skip-test'] },
  async postVisit(page, context) {
    const results = await new AxeBuilder({ page }).withTags(A11Y_TAGS).analyze();
    const { blocking, allowed, stale } = blockingViolations(context.id, results.violations);
    // Jest workers swallow console output, so known/fixed issues go to a log (CI prints it into the job summary).
    const notes = [...allowed.map((l) => `known: ${context.id}: ${l}`), ...stale.map((l) => `FIXED, delete the entry: ${context.id}: ${l}`)];
    if (notes.length > 0) {
      mkdirSync(dirname(A11Y_LOG), { recursive: true });
      appendFileSync(A11Y_LOG, notes.map((n) => `${n}\n`).join(''));
    }
    if (blocking.length > 0) {
      throw new Error(`axe serious/critical violations in ${context.title} › ${context.name}:\n  ${blocking.join('\n  ')}`);
    }
  }
};

export default config;
