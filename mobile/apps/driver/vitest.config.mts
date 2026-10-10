import { fileURLToPath } from 'node:url';

import { defineConfig } from 'vitest/config';

// Unit tests cover the plain-TS domain modules (no device, no React Native runtime).
export default defineConfig({
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url).href) } },
  test: { include: ['test/**/*.test.ts'], environment: 'node' }
});
