import { withTelemetryConfig } from '@afrotransact/telemetry/next/config';
import { type NextConfig } from 'next';

const securityHeaders = [
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(), publickey-credentials-get=(self)' }
];

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  // Workspace packages ship TypeScript source.
  transpilePackages: ['@afrotransact/account', '@afrotransact/ui', '@afrotransact/data-table', '@afrotransact/api-client', '@afrotransact/tokens', '@afrotransact/i18n', '@afrotransact/telemetry'],
  distDir: process.env.NEXT_DIST_DIR || '.next',
  // Local LAN mode (make up MOBILE_HOST=lan): the dev server is opened as http://<LAN IP>:<port> from a phone; Next.js
  // only serves its dev assets and HMR to hosts listed here (comma-separated, set by tools/dev/local.sh).
  allowedDevOrigins: (process.env.NEXT_ALLOWED_DEV_ORIGINS ?? '').split(',').map((h) => h.trim()).filter(Boolean),
  experimental: { optimizePackageImports: ['@phosphor-icons/react', 'recharts'], agentUpgrade: 'security' },
  images: { unoptimized: true },
  async headers() {
    return [{ source: '/:path*', headers: securityHeaders }];
  },
  async redirects() {
    // The multi-step product builder is gone (S307): any step URL opens the one-step form.
    return [
      { source: '/listings/new/:step+', destination: '/listings/new', permanent: true },
      { source: '/listings/builder/:step*', destination: '/listings/new', permanent: true },
      { source: '/listings/upload', destination: '/listings/bulk', permanent: true }
    ];
  }
};

// Source maps are uploaded only in CI release jobs (SENTRY_UPLOAD_SOURCEMAPS=1 + Sentry credentials); see @afrotransact/telemetry/next/config.
export default async function config(): Promise<NextConfig> {
  return withTelemetryConfig(nextConfig);
}
