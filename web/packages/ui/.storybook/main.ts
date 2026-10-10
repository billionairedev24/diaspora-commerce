import { createRequire } from 'node:module';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { type StorybookConfig } from '@storybook/react-vite';

const here = dirname(fileURLToPath(import.meta.url));
const sellerCenterDir = resolve(here, '../../../apps/seller-center');

/**
 * Tailwind 4 for the Seller Center app stories: the app styles with Tailwind utilities bound to the --at-* tokens
 * (src/app/globals.css). The PostCSS plugin is loaded from the app's own dependencies, so this package does not need
 * Tailwind; when the app is not installed the app stories still render, just unstyled.
 */
async function appPostcssPlugins(): Promise<unknown[]> {
  try {
    const req = createRequire(resolve(sellerCenterDir, 'package.json'));
    const mod = (await import(req.resolve('@tailwindcss/postcss'))) as { default: (opts: { base: string }) => unknown };
    return [mod.default({ base: sellerCenterDir })];
  } catch {
    return [];
  }
}

const appsDir = resolve(here, '../../../apps');

/**
 * The Next.js apps import their own modules as `@/…` (tsconfig `paths`: `@/*` -> `./src/*`). App components rendered in
 * stories keep that: `@/x` imported from a file under web/apps/<app>/ resolves to web/apps/<app>/src/x.
 */
function appAliasPlugin() {
  return {
    name: 'afrotransact-app-alias',
    enforce: 'pre' as const,
    async resolveId(this: { resolve: (id: string, importer?: string, opts?: { skipSelf: boolean }) => Promise<unknown> }, source: string, importer?: string) {
      if (!source.startsWith('@/') || !importer) return null;
      const rel = importer.startsWith(appsDir + sep) ? importer.slice(appsDir.length + 1) : null;
      const app = rel?.split(sep)[0];
      if (!app) return null;
      return this.resolve(resolve(appsDir, app, 'src', source.slice(2)), importer, { skipSelf: true });
    }
  };
}

const config: StorybookConfig = {
  stories: [
    // Foundations (design tokens) and the @afrotransact/ui component stories.
    '../stories/**/*.mdx',
    '../src/**/*.stories.@(ts|tsx)',
    // Presentational components of the web apps: stories kept here (stories/apps/<app>) or co-located in the app.
    '../stories/**/*.stories.@(ts|tsx)',
    '../../../apps/*/src/**/*.stories.@(ts|tsx)'
  ],
  // addon-vitest: the Storybook UI's test widget runs the `storybook` Vitest project (vitest.config.ts).
  addons: ['@storybook/addon-a11y', '@storybook/addon-docs', '@storybook/addon-vitest'],
  framework: { name: '@storybook/react-vite', options: {} },
  core: { disableTelemetry: true },
  typescript: { reactDocgen: 'react-docgen-typescript' },
  viteFinal: async (cfg) => ({
    ...cfg,
    plugins: [appAliasPlugin(), ...(cfg.plugins ?? [])],
    css: { ...cfg.css, postcss: { plugins: (await appPostcssPlugins()) as never[] } },
    build: {
      ...cfg.build,
      chunkSizeWarningLimit: 2000,
      rollupOptions: {
        ...cfg.build?.rollupOptions,
        // Radix ships "use client" directives that are meaningless in a static Storybook bundle.
        onwarn: (w, warn) => {
          if (w.code === 'MODULE_LEVEL_DIRECTIVE') return;
          warn(w);
        }
      }
    }
  })
};

export default config;
