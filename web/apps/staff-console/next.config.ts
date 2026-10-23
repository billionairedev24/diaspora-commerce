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
  // Links made before the module path was /organisations (…/orgs?resource=connect-accounts) still work.
  async redirects() {
    return [
      { source: '/orgs', has: [{ type: 'query', key: 'resource', value: 'connect-accounts' }], destination: '/organisations?tab=connect-accounts', permanent: false },
      { source: '/orgs', destination: '/organisations', permanent: false },
      // Modules that moved into Settings: their old addresses open the matching section (the query string is kept).
      { source: '/markets', destination: '/settings?section=markets', permanent: false },
      { source: '/site-content', destination: '/settings?section=general', permanent: false },
      { source: '/developers', destination: '/settings?section=signin', permanent: false },
      { source: '/notifications', destination: '/settings?section=notifications', permanent: false },
      { source: '/fees', destination: '/settings?section=fees', permanent: false }
    ];
  },
  async headers() {
    return [{ source: '/:path*', headers: securityHeaders }];
  }
};

// Source maps are uploaded only in CI release jobs (SENTRY_UPLOAD_SOURCEMAPS=1 + Sentry credentials); see @afrotransact/telemetry/next/config.
export default async function config(): Promise<NextConfig> {
  return withTelemetryConfig(nextConfig);
}
