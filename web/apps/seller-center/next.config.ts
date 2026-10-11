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
  transpilePackages: ['@afrotransact/ui', '@afrotransact/data-table', '@afrotransact/api-client', '@afrotransact/tokens', '@afrotransact/i18n', '@afrotransact/telemetry'],
  distDir: process.env.NEXT_DIST_DIR || '.next',
  experimental: { optimizePackageImports: ['@phosphor-icons/react', 'recharts'] },
  images: { unoptimized: true },
  async headers() {
    return [{ source: '/:path*', headers: securityHeaders }];
  }
};

// Source maps are uploaded only in CI release jobs (SENTRY_UPLOAD_SOURCEMAPS=1 + Sentry credentials); see @afrotransact/telemetry/next/config.
export default async function config(): Promise<NextConfig> {
  return withTelemetryConfig(nextConfig);
}
