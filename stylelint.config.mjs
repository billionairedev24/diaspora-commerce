// Shared Stylelint config. Components style with design tokens only: raw hex colours are errors
// everywhere except the token source itself (web/packages/tokens).
/** @type {import('stylelint').Config} */
export default {
  ignoreFiles: ['**/node_modules/**', '**/.next/**', '**/dist/**', '**/storybook-static/**', '**/.open-next/**'],
  rules: {
    'color-no-hex': true,
    'color-named': 'never',
    'declaration-no-important': true,
    'block-no-empty': true,
    'no-duplicate-selectors': true
  },
  overrides: [
    {
      files: ['web/packages/tokens/**/*.css', '**/tokens/src/*.css'],
      rules: { 'color-no-hex': null, 'color-named': null, 'declaration-no-important': null, 'no-duplicate-selectors': null }
    }
  ]
};
