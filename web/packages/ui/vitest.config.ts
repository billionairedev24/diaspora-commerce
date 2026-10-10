import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { storybookTest } from '@storybook/addon-vitest/vitest-plugin';
import { playwright } from '@vitest/browser-playwright';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const here = dirname(fileURLToPath(import.meta.url));

/*
 * Two Vitest projects:
 *
 *   unit       Testing Library in jsdom (test/**). `pnpm test` runs only this one.
 *   storybook  every story of .storybook/main.ts (ui + app stories) rendered in headless Playwright Chromium through
 *              @storybook/addon-vitest: a render error or a failing `play` function fails the story, and the a11y
 *              addon's axe scan (parameters.a11y in .storybook/preview.tsx) fails it on serious/critical violations.
 *              `pnpm test:storybook` runs it with v8 coverage into coverage/storybook.
 *
 * Chromium comes from PLAYWRIGHT_BROWSERS_PATH (/opt/pw-browsers in the dev container; preinstalled in the CI image
 * mcr.microsoft.com/playwright:v1.56.1-noble, which matches the `playwright` dev dependency). Opt a story out with the
 * `skip-test` tag.
 */
export default defineConfig({
  test: {
    projects: [
      {
        plugins: [react()],
        test: {
          name: 'unit',
          environment: 'jsdom',
          include: ['test/**/*.test.{ts,tsx}'],
          setupFiles: ['test/setup.ts'],
          css: { modules: { classNameStrategy: 'non-scoped' } }
        }
      },
      {
        // The plugin reads .storybook/main.ts (stories globs, viteFinal) and applies preview.tsx itself.
        plugins: [storybookTest({ configDir: join(here, '.storybook'), tags: { exclude: ['skip-test'] } })],
        test: {
          name: 'storybook',
          browser: {
            enabled: true,
            headless: true,
            provider: playwright({ contextOptions: { locale: 'en-US', timezoneId: 'UTC', reducedMotion: 'reduce' } }),
            instances: [{ browser: 'chromium' }],
            // Failure screenshots would otherwise land in __screenshots__/ next to the story files.
            screenshotFailures: false
          }
        }
      }
    ],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.stories.{ts,tsx}', 'src/stories.helpers.ts', 'src/**/*.d.ts'],
      // json-summary is read by tools/quality/coverage_report.sh; lcov.info for IDEs/uploads. No HTML reporter: its
      // bundled .js files would be picked up by `eslint .`.
      reporter: ['text-summary', 'json-summary', 'lcovonly']
    }
  }
});
