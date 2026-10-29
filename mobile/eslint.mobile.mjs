// Shared ESLint flat config for the Expo apps in mobile/apps/*. It reuses the workspace rules from the root
// eslint.config.mjs (strict TS, no `any`, sorted imports, no namespace imports of app code, hooks rules) and adds:
//  - the design-token rule (no raw hex colours) for every mobile source file,
//  - ignores for Expo build output.
// Next.js and Storybook blocks of the root config are dropped: expo-router's src/app/ is not a Next.js app dir.
import root from '../eslint.config.mjs';

const isWebOnly = (block) =>
  Boolean(block.plugins && ('@next/next' in block.plugins || 'storybook' in block.plugins || 'jsx-a11y' in block.plugins));

const at = root.find((block) => block.plugins && 'at' in block.plugins)?.plugins.at;
if (!at) throw new Error('Root ESLint config no longer defines the "at" plugin (no-raw-hex).');

export default [
  { ignores: ['**/.expo/**', '**/dist/**', '**/web-build/**', '**/expo-env.d.ts', '**/src/api/generated/**', '**/android/**', '**/ios/**'] },
  ...root.filter((block) => !isWebOnly(block)),
  {
    // Metro loads its config as CommonJS.
    files: ['metro.config.js'],
    languageOptions: { sourceType: 'commonjs' },
    rules: { '@typescript-eslint/no-require-imports': 'off' }
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { 'at-mobile': at },
    rules: { 'at-mobile/no-raw-hex': 'error' }
  },
  {
    // Documented exception (same as web/packages/ui PaymentBrandMarks.tsx): card network and wallet marks keep their
    // owners' colours. Only this file may hold them.
    files: ['src/ui/PaymentMarks.tsx'],
    rules: { 'at-mobile/no-raw-hex': 'off' }
  }
];
