'use client';

import { syncServiceWorker } from '@afrotransact/pwa';
import { useEffect } from 'react';

/**
 * Registers the service worker in production builds (and removes a leftover one in any other build). Renders nothing.
 * NODE_ENV is inlined by Next.js at build time.
 */
export function ServiceWorkerRegistration() {
  useEffect(() => {
    void syncServiceWorker(process.env.NODE_ENV);
  }, []);
  return null;
}
