// Metro config: Expo defaults (monorepo-aware, via Sentry's wrapper) plus one rule — React and TanStack Query always resolve to this app's
// copies. Workspace packages such as @afrotransact/api-client carry their own (web, React 19.3) dev install; without
// this rule a second React could be bundled through them.
const path = require('node:path');

// Sentry's Expo config = Expo defaults + debug IDs in bundles and source maps, so errors map back to source per release (S178).
const { getSentryExpoConfig } = require('@sentry/react-native/metro');

const config = getSentryExpoConfig(__dirname);
const SINGLETONS = ['react', 'react-dom', 'react-native', '@tanstack/react-query'];
const appOrigin = path.join(__dirname, 'package.json');

const upstream = config.resolver.resolveRequest;
config.resolver.resolveRequest = (context, moduleName, platform) => {
  const resolve = upstream ?? context.resolveRequest;
  const singleton = SINGLETONS.some((s) => moduleName === s || moduleName.startsWith(`${s}/`));
  return resolve(singleton ? { ...context, originModulePath: appOrigin } : context, moduleName, platform);
};

module.exports = config;
