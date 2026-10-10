// OpenNext adapter config for Cloudflare Workers (`pnpm cf:build` → .open-next/worker.js).
// The same Next.js build also deploys unchanged to Vercel.
import { defineCloudflareConfig } from '@opennextjs/cloudflare';

export default defineCloudflareConfig({
  // Pages are per-session (cart, deliver-to ZIP, account), so no incremental cache is configured yet.
  // Add R2 incremental cache here once static/ISR pages (help, legal) are introduced.
});
