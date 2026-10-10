// Shared ESLint flat config for every JavaScript/TypeScript package in the pnpm workspace.
// Packages run `eslint .` and ESLint resolves this file by walking up from the package directory.
import js from '@eslint/js';
import nextPlugin from '@next/eslint-plugin-next';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import simpleImportSort from 'eslint-plugin-simple-import-sort';
import storybook from 'eslint-plugin-storybook';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/** Matches CSS hex colours such as #fff, #ffd400 or #ffd400cc. */
const HEX_COLOUR = /#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})(?![0-9a-zA-Z_-])/;

/**
 * Local rule: components must use design tokens (CSS custom properties from @afrotransact/tokens),
 * never raw hex colours. Applies to string literals, template literals and JSX text.
 */
const noRawHex = {
  meta: {
    type: 'problem',
    docs: { description: 'Disallow raw hex colours; use --at-* design tokens instead.' },
    messages: { hex: 'Raw hex colour "{{value}}" — use a design token (var(--at-…)) from @afrotransact/tokens.' },
    schema: []
  },
  create(context) {
    const check = (node, text) => {
      const m = typeof text === 'string' ? HEX_COLOUR.exec(text) : null;
      if (m) context.report({ node, messageId: 'hex', data: { value: m[0] } });
    };
    return {
      Literal: (node) => check(node, node.value),
      TemplateElement: (node) => check(node, node.value.raw),
      JSXText: (node) => check(node, node.value)
    };
  }
};

export default tseslint.config(
  {
    ignores: [
      '**/node_modules/**',
      '**/.next/**',
      '**/.open-next/**',
      '**/.wrangler/**',
      '**/dist/**',
      '**/storybook-static/**',
      '**/playwright-report/**',
      '**/test-results/**',
      '**/src/generated/**',
      '**/next-env.d.ts',
      '**/cloudflare-env.d.ts',
      '**/mockServiceWorker.js'
    ]
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx,js,mjs,cjs,jsx}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.node }
    },
    plugins: {
      'simple-import-sort': simpleImportSort,
      'react-hooks': reactHooks,
      at: { rules: { 'no-raw-hex': noRawHex } }
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      '@typescript-eslint/consistent-type-imports': ['error', { fixStyle: 'inline-type-imports' }],
      'simple-import-sort/imports': 'error',
      'simple-import-sort/exports': 'error',
      'no-restricted-syntax': [
        'error',
        {
          selector: "ImportDeclaration[source.value=/^(\\.|@\\/|@afrotransact\\/)/] > ImportNamespaceSpecifier",
          message: 'No namespace imports of app code — import the named members you use.'
        }
      ],
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error'
    }
  },
  {
    files: ['web/apps/*/src/**/*.{ts,tsx}', 'web/packages/ui/src/**/*.{ts,tsx}', 'src/**/*.{ts,tsx}'],
    ignores: ['web/packages/tokens/**'],
    plugins: { 'jsx-a11y': jsxA11y },
    rules: {
      ...jsxA11y.flatConfigs.recommended.rules,
      'at/no-raw-hex': 'error'
    }
  },
  {
    files: ['**/next.config.ts', '**/app/**/*.{ts,tsx}', '**/src/app/**/*.{ts,tsx}'],
    plugins: { '@next/next': nextPlugin },
    rules: {
      ...nextPlugin.configs.recommended.rules,
      ...nextPlugin.configs['core-web-vitals'].rules
    }
  },
  ...storybook.configs['flat/recommended']
);
