/*
 * Build-time wiring for source maps. Source maps are uploaded only in CI, and only when all of these are set:
 *
 *   SENTRY_UPLOAD_SOURCEMAPS=1   opt-in switch (CI release jobs only)
 *   SENTRY_AUTH_TOKEN            org token with project:releases scope (CI secret)
 *   SENTRY_ORG, SENTRY_PROJECT   the project for this app (e.g. afrotransact / storefront-web)
 *   SENTRY_URL                   optional: self-hosted Sentry or a Sentry-compatible server
 *   NEXT_PUBLIC_RELEASE          the release the maps belong to (same value the browser reports)
 *
 * Otherwise the Next.js config is returned untouched (local builds, previews, E2E): no network, no CLI.
 */
import { type NextConfig } from 'next';

export function sourceMapUploadEnabled(env: Record<string, string | undefined> = process.env): boolean {
  return env.SENTRY_UPLOAD_SOURCEMAPS === '1' && Boolean(env.SENTRY_AUTH_TOKEN && env.SENTRY_ORG && env.SENTRY_PROJECT);
}

export async function withTelemetryConfig(config: NextConfig): Promise<NextConfig> {
  if (!sourceMapUploadEnabled()) return config;
  const { withSentryConfig } = await import('@sentry/nextjs');
  return withSentryConfig(config, {
    org: process.env.SENTRY_ORG,
    project: process.env.SENTRY_PROJECT,
    authToken: process.env.SENTRY_AUTH_TOKEN,
    sentryUrl: process.env.SENTRY_URL,
    release: { name: process.env.NEXT_PUBLIC_RELEASE, create: true },
    sourcemaps: { deleteSourcemapsAfterUpload: true },
    widenClientFileUpload: true,
    telemetry: false,
    silent: true
  });
}
