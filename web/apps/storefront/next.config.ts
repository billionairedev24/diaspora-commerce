import { withTelemetryConfig } from '@afrotransact/telemetry/next/config';
import { type NextConfig } from 'next';

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
  transpilePackages: ['@afrotransact/ui', '@afrotransact/api-client', '@afrotransact/tokens', '@afrotransact/i18n', '@afrotransact/telemetry'],
  // Allows a separate build output (e.g. NEXT_DIST_DIR=.next-e2e) without clobbering .next.
  distDir: process.env.NEXT_DIST_DIR || '.next',
  experimental: { optimizePackageImports: ['@phosphor-icons/react'] },
  images: { unoptimized: true },
  async headers() {
    return [{ source: '/:path*', headers: securityHeaders }];
  },
  // Content placements (hero, tiles, announcements, banners) may link to the canonical marketing paths; they all land on
  // search (/s). /communities/<c> is a route of its own (it capitalises the community name).
  async redirects() {
    return [
      { source: '/search', destination: '/s', permanent: false },
      { source: '/brands/afrotransact', destination: '/s?soldBy=platform', permanent: false }
    ];
  }
};

// Source maps are uploaded only in CI release jobs (SENTRY_UPLOAD_SOURCEMAPS=1 + Sentry credentials); see @afrotransact/telemetry/next/config.
export default async function config(): Promise<NextConfig> {
  return withTelemetryConfig(nextConfig);
}
