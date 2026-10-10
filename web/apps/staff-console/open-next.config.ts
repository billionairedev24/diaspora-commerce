// OpenNext adapter config for Cloudflare Workers (`pnpm cf:build` → .open-next/worker.js), as in the storefront.
import { defineCloudflareConfig } from '@opennextjs/cloudflare';

export default defineCloudflareConfig({
  // Staff console pages are per-session back-office screens, so no incremental cache is configured.
});
