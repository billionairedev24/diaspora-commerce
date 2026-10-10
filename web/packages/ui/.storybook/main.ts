import { type StorybookConfig } from '@storybook/react-vite';

const config: StorybookConfig = {
  stories: ['../src/**/*.stories.@(ts|tsx)'],
  addons: ['@storybook/addon-a11y', '@storybook/addon-docs'],
  framework: { name: '@storybook/react-vite', options: {} },
  core: { disableTelemetry: true },
  typescript: { reactDocgen: 'react-docgen-typescript' },
  // Radix ships "use client" directives that are meaningless in a static Storybook bundle.
  viteFinal: (cfg) => ({
    ...cfg,
    build: {
      ...cfg.build,
      chunkSizeWarningLimit: 2000,
      rollupOptions: {
        ...cfg.build?.rollupOptions,
        onwarn: (w, warn) => {
          if (w.code === 'MODULE_LEVEL_DIRECTIVE') return;
          warn(w);
        }
      }
    }
  })
};

export default config;
