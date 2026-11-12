/* The service worker (/sw.js): generated from the shared rules in @afrotransact/pwa. Static assets only; never API, auth,
   cart, checkout or personalised responses. Served with no-cache so a new version reaches browsers on the next visit. */
import { SERVICE_WORKER_HEADERS, serviceWorkerSource } from '@afrotransact/pwa';

import { storefrontServiceWorkerConfig } from '@/lib/pwa';

export function GET(): Response {
  return new Response(serviceWorkerSource(storefrontServiceWorkerConfig()), { headers: SERVICE_WORKER_HEADERS });
}
