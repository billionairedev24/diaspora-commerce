import { defineConfig, devices } from '@playwright/test';

/* Smoke tests run the production build against the in-process MSW mock BFF (NEXT_PUBLIC_API_MOCKS=1).
   Set E2E_SKIP_BUILD=1 to reuse an existing `next build`. Chromium comes from PLAYWRIGHT_BROWSERS_PATH. */
const PORT = Number(process.env.E2E_PORT ?? 3005);
const build = process.env.E2E_SKIP_BUILD ? '' : 'pnpm exec next build && ';
/* Specs start with a cookie choice already made (the first-visit banner would cover bottom-of-page controls);
   e2e/legal.spec.ts starts without one to test the banner itself. */
const CONSENT = encodeURIComponent(JSON.stringify({ necessary: true, functional: true, analytics: false, advertising: false, dns: false, gpc: false, v: '2.1', at: '2026-10-01T00:00:00.000Z' }));

export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
    storageState: { cookies: [{ name: 'at_consent', value: CONSENT, domain: 'localhost', path: '/', expires: -1, httpOnly: false, secure: false, sameSite: 'Lax' }], origins: [] }
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 900 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'] }, testMatch: /responsive\.spec\.ts/ }
  ],
  webServer: {
    command: `${build}pnpm exec next start --port ${PORT}`,
    url: `http://localhost:${PORT}/api/bff/v1/site-config`,
    reuseExistingServer: !process.env.CI,
    timeout: 240_000,
    env: { NEXT_PUBLIC_API_MOCKS: '1', NEXT_TELEMETRY_DISABLED: '1' }
  }
});
