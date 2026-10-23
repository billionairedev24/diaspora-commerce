import { withTelemetryConfig } from '@afrotransact/telemetry/next/config';
import { type NextConfig } from 'next';

import { LOCAL_MEDIA_PUBLIC_URL, mediaRemotePatterns } from './src/lib/media-remote-patterns';

const securityHeaders = [
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(self), publickey-credentials-get=(self)' }
];

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  // Workspace packages ship TypeScript source.
  transpilePackages: ['@afrotransact/account', '@afrotransact/ui', '@afrotransact/data-table', '@afrotransact/api-client', '@afrotransact/tokens', '@afrotransact/i18n', '@afrotransact/telemetry'],
  // Allows a separate build output (e.g. NEXT_DIST_DIR=.next-e2e) without clobbering .next.
  distDir: process.env.NEXT_DIST_DIR || '.next',
  // Local LAN mode (make up MOBILE_HOST=lan): the dev server is opened as http://<LAN IP>:<port> from a phone; Next.js
  // only serves its dev assets and HMR to hosts listed here (comma-separated, set by tools/dev/local.sh).
  allowedDevOrigins: (process.env.NEXT_ALLOWED_DEV_ORIGINS ?? '').split(',').map((h) => h.trim()).filter(Boolean),
  experimental: { optimizePackageImports: ['@phosphor-icons/react'], agentUpgrade: 'security' },
  // Product, campaign and tile images come from the media service's public URL: this stack's MinIO bucket locally
  // (http://localhost:9000/media), each environment's media CDN otherwise (MEDIA_PUBLIC_URL, docs/engineering/environments.md).
  // They render as plain <img> (unoptimized); the patterns matter once Next.js image optimisation is switched on.
  images: {
    unoptimized: true,
    remotePatterns: mediaRemotePatterns([process.env.MEDIA_PUBLIC_URL || LOCAL_MEDIA_PUBLIC_URL, process.env.NEXT_PUBLIC_MEDIA_PUBLIC_URL])
  },
  async headers() {
    return [{ source: '/:path*', headers: securityHeaders }];
  },
  // Content placements (hero, tiles, announcements, banners) may link to the canonical marketing paths; they all land on
  // search (/s). /communities/<c> is a route of its own (it capitalises the community name).
  async redirects() {
    return [
      { source: '/search', destination: '/s', permanent: false },
      { source: '/brands/afrotransact', destination: '/s?soldBy=platform', permanent: false },
      { source: '/terms', destination: '/legal/conditions-of-use', permanent: true },
      { source: '/terms-of-use', destination: '/legal/conditions-of-use', permanent: true },
      { source: '/privacy', destination: '/legal/privacy', permanent: true },
      { source: '/cookies', destination: '/legal/cookies', permanent: true },
      { source: '/cookie-policy', destination: '/legal/cookies', permanent: true }
    ];
  }
};

// Source maps are uploaded only in CI release jobs (SENTRY_UPLOAD_SOURCEMAPS=1 + Sentry credentials); see @afrotransact/telemetry/next/config.
export default async function config(): Promise<NextConfig> {
  return withTelemetryConfig(nextConfig);
}
