import { defineConfig, devices } from '@playwright/test';

/*
 * Live journeys against a RUNNING local stack (tools/dev/local.sh up): identity :8100, gateway-bff :8200,
 * storefront :3000, Seller Center :3001, staff console :3002, Mailpit :8025. Nothing is mocked or started here.
 *
 *   pnpm --filter @afrotransact/e2e test:live
 *
 * Env: LIVE_STOREFRONT_URL / LIVE_SELLER_URL / LIVE_STAFF_URL / LIVE_IDENTITY_URL / LIVE_MAILPIT_URL override the
 * local ports; LIVE_IDENTITY_LOG is identity's log (local verification codes, default .local/logs/identity.log);
 * LIVE_SCREENSHOTS saves the hosted page screenshots to that directory.
 */
export default defineConfig({
  testDir: './live',
  outputDir: './test-results/live',
  timeout: 120_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 }, trace: 'retain-on-failure', screenshot: 'only-on-failure', locale: 'en-US' }
});
