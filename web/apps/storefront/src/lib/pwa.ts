import { buildManifest, defaultServiceWorkerConfig, type PwaManifest, type ServiceWorkerConfig } from '@afrotransact/pwa';
import { resolvedVariables } from '@afrotransact/tokens';

/**
 * The storefront as an installable web app (docs/engineering/pwa.md): the manifest, the service worker's rules and the
 * offline page's colours all come from here, and every colour is a design token.
 */

export function storefrontManifest(): PwaManifest {
  return buildManifest({
    name: 'AfroTransact',
    shortName: 'AfroTransact',
    startUrl: '/?source=pwa',
    scope: '/',
    // The browser chrome matches the storefront header (the same token as the layout's viewport themeColor).
    themeColor: resolvedVariables['--at-color-header-bg'],
    backgroundColor: resolvedVariables['--at-color-canvas'],
    icons: [
      { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
      { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
      { src: '/icons/maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' }
    ]
  });
}

/** Static assets only (build output, brand icons, logo); see COMMON_NEVER_CACHE in @afrotransact/pwa for what is never cached. */
export function storefrontServiceWorkerConfig(): ServiceWorkerConfig {
  return defaultServiceWorkerConfig({ cachePrefix: 'afrotransact-storefront' });
}
