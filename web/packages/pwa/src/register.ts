import { SERVICE_WORKER_PATH } from './service-worker';

export interface RegisterEnv {
  nodeEnv: string | undefined;
  hasServiceWorker: boolean;
}

/** The worker is registered in production builds only: a dev server must never be cached. */
export function shouldRegister(env: RegisterEnv): boolean {
  return env.nodeEnv === 'production' && env.hasServiceWorker;
}

/**
 * Registers the worker (production) or removes a leftover one (any other build, e.g. a dev server on a port that once
 * ran a production build). Never throws: the site works without a worker.
 */
export async function syncServiceWorker(nodeEnv: string | undefined, url: string = SERVICE_WORKER_PATH): Promise<void> {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return;
  try {
    if (shouldRegister({ nodeEnv, hasServiceWorker: true })) {
      await navigator.serviceWorker.register(url, { scope: '/', updateViaCache: 'none' });
      return;
    }
    const registrations = await navigator.serviceWorker.getRegistrations();
    await Promise.all(registrations.map((r) => r.unregister()));
  } catch {
    /* private mode, blocked storage or an insecure origin: no worker, no problem */
  }
}
