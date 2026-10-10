import { defineConfig, devices } from '@playwright/test';

/*
 * Cross-app E2E journeys. Playwright starts both Next.js apps with the in-process MSW mock BFF
 * (NEXT_PUBLIC_API_MOCKS=1) on spare ports, so no back end is needed:
 *
 *   pnpm --filter @afrotransact/e2e test            # from the repo root
 *
 * Env:
 *   E2E_STOREFRONT_PORT / E2E_SELLER_PORT / E2E_STAFF_PORT  ports for the app servers (default 3410 / 3411 / 3412)
 *   E2E_STOREFRONT_URL / E2E_SELLER_URL / E2E_STAFF_URL     point at already-running apps or a per-PR preview; no server is started
 *   E2E_SERVER=prod|dev                    prod (default): `next build` into .next-e2e + `next start` — stable and close to
 *                                          production; dev: `next dev` (faster to start, but Fast Refresh reloads the page
 *                                          whenever someone edits app or package sources, which makes journeys flaky)
 *   E2E_SKIP_BUILD=1                       prod mode: reuse an existing .next-e2e build
 *   E2E_BROWSERS=chromium,firefox,webkit   extra engines (CI on previews); local default is Chromium only
 *   PLAYWRIGHT_BROWSERS_PATH               where Playwright finds browsers (/opt/pw-browsers in the dev container)
 *
 * Quarantine: tag a flaky test `@quarantine` and add `// owner: <github handle> — <issue>` above it. CI runs
 * `--grep-invert @quarantine` on the blocking job and the quarantined set in a non-blocking job.
 */

const SF_PORT = Number(process.env.E2E_STOREFRONT_PORT ?? 3410);
const SC_PORT = Number(process.env.E2E_SELLER_PORT ?? 3411);
const ST_PORT = Number(process.env.E2E_STAFF_PORT ?? 3412);
const STOREFRONT_URL = process.env.E2E_STOREFRONT_URL ?? `http://localhost:${SF_PORT}`;
const SELLER_URL = process.env.E2E_SELLER_URL ?? `http://localhost:${SC_PORT}`;
const STAFF_URL = process.env.E2E_STAFF_URL ?? `http://localhost:${ST_PORT}`;
const external = Boolean(process.env.E2E_STOREFRONT_URL || process.env.E2E_SELLER_URL || process.env.E2E_STAFF_URL);

const engines = (process.env.E2E_BROWSERS ?? 'chromium').split(',').map((s) => s.trim()).filter(Boolean);
const deviceOf: Record<string, (typeof devices)[string]> = {
  chromium: devices['Desktop Chrome'],
  firefox: devices['Desktop Firefox'],
  webkit: devices['Desktop Safari']
};

const MODE = process.env.E2E_SERVER === 'dev' ? 'dev' : 'prod';
// Separate dist dir (NEXT_DIST_DIR is honoured by both apps' next.config.ts) so E2E never clobbers a developer's .next.
const DIST = '.next-e2e';

const appServer = (app: string, port: number, extraEnv: Record<string, string> = {}) => ({
  command:
    MODE === 'dev'
      ? `pnpm --dir ../../web/apps/${app} exec next dev --port ${port}`
      : `${process.env.E2E_SKIP_BUILD ? '' : `pnpm --dir ../../web/apps/${app} exec next build && `}pnpm --dir ../../web/apps/${app} exec next start --port ${port}`,
  url: `http://localhost:${port}/api/bff/v1/site-config`,
  reuseExistingServer: !process.env.CI,
  timeout: 600_000,
  stdout: 'ignore' as const,
  stderr: 'pipe' as const,
  // NEXT_PUBLIC_* values are inlined at build time, so they must be set for `next build` as well as at runtime.
  env: { NEXT_PUBLIC_API_MOCKS: '1', NEXT_TELEMETRY_DISABLED: '1', ...(MODE === 'prod' ? { NEXT_DIST_DIR: DIST } : {}), ...extraEnv }
});

export default defineConfig({
  testDir: './specs',
  outputDir: './test-results',
  timeout: 90_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: process.env.CI ? 2 : 1,
  retries: process.env.CI ? 1 : 0,
  forbidOnly: Boolean(process.env.CI),
  reporter: process.env.CI ? [['list'], ['html', { open: 'never', outputFolder: 'reports/html' }], ['junit', { outputFile: 'reports/junit.xml' }]] : 'list',
  use: { trace: 'retain-on-failure', screenshot: 'only-on-failure', locale: 'en-US', timezoneId: 'America/New_York' },
  projects: engines.flatMap((engine) => [
    { name: `storefront-${engine}`, testMatch: /storefront\..*spec\.ts/, use: { ...deviceOf[engine], baseURL: STOREFRONT_URL } },
    // Seller mock state (active organisation) is process-wide, so seller journeys run one at a time.
    { name: `seller-center-${engine}`, testMatch: /seller-center\..*spec\.ts/, fullyParallel: false, use: { ...deviceOf[engine], baseURL: SELLER_URL, viewport: { width: 1440, height: 900 } } },
    { name: `staff-console-${engine}`, testMatch: /staff-console\..*spec\.ts/, fullyParallel: false, use: { ...deviceOf[engine], baseURL: STAFF_URL, viewport: { width: 1440, height: 900 } } }
  ]),
  webServer: external
    ? undefined
    : [
        // The site-config route is the seller app's BFF proxy too, but only the storefront mocks answer it; use the session for Seller Center.
        appServer('storefront', SF_PORT),
        { ...appServer('seller-center', SC_PORT, { NEXT_PUBLIC_STOREFRONT_URL: STOREFRONT_URL }), url: `http://localhost:${SC_PORT}/api/bff/v1/seller/session` },
        { ...appServer('staff-console', ST_PORT, { NEXT_PUBLIC_STOREFRONT_URL: STOREFRONT_URL }), url: `http://localhost:${ST_PORT}/api/bff/v1/staff/session` }
      ]
});
