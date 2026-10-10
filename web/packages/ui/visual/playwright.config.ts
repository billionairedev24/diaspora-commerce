import { defineConfig, devices } from '@playwright/test';

/*
 * Visual regression for every story of the static Storybook build (storybook-static/index.json), compared with the
 * PNG baselines committed in ../visual-baselines (no external service).
 *
 *   pnpm --filter @afrotransact/ui build-storybook
 *   pnpm --filter @afrotransact/ui visual           # compare; diffs land in visual/test-results
 *   pnpm --filter @afrotransact/ui visual:update    # rewrite the baselines after an intended change
 *
 * Env:
 *   VISUAL_MAX_DIFF_PIXEL_RATIO  share of pixels allowed to differ per story (default 0.002 = 0.2 %)
 *   VISUAL_THRESHOLD             per-pixel YIQ colour distance 0..1 that counts as "different" (default 0.2)
 *   STORYBOOK_URL                compare an already-served build instead of starting scripts/serve-static.mjs
 *   STORYBOOK_STATIC_DIR         build directory (default storybook-static)
 *   PLAYWRIGHT_BROWSERS_PATH     where Chromium lives (/opt/pw-browsers in the dev container)
 */
const PORT = Number(process.env.VISUAL_PORT ?? 6008);
const external = process.env.STORYBOOK_URL;

export default defineConfig({
  testDir: '.',
  testMatch: 'stories.visual.ts',
  outputDir: './test-results',
  // Baselines are platform-agnostic file names: CI and local both render in Playwright's Chromium on Linux.
  snapshotPathTemplate: '{testDir}/../visual-baselines/{arg}{ext}',
  fullyParallel: true,
  workers: process.env.CI ? 2 : 4,
  retries: 0,
  forbidOnly: Boolean(process.env.CI),
  reporter: process.env.CI ? [['list'], ['html', { outputFolder: './report', open: 'never' }]] : [['list']],
  timeout: 30_000,
  expect: {
    toHaveScreenshot: {
      maxDiffPixelRatio: Number(process.env.VISUAL_MAX_DIFF_PIXEL_RATIO ?? 0.002),
      threshold: Number(process.env.VISUAL_THRESHOLD ?? 0.2),
      animations: 'disabled',
      caret: 'hide',
      scale: 'css'
    }
  },
  use: {
    ...devices['Desktop Chrome'],
    baseURL: external ?? `http://127.0.0.1:${PORT}`,
    viewport: { width: 1024, height: 720 },
    deviceScaleFactor: 1,
    colorScheme: 'light',
    locale: 'en-US',
    timezoneId: 'UTC',
    contextOptions: { reducedMotion: 'reduce' }
  },
  webServer: external
    ? undefined
    : {
        command: 'node scripts/serve-static.mjs',
        cwd: '..',
        env: { STORYBOOK_PORT: String(PORT) },
        url: `http://127.0.0.1:${PORT}/index.json`,
        reuseExistingServer: !process.env.CI,
        timeout: 30_000
      }
});
