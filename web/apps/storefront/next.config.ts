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
  transpilePackages: ['@afrotransact/ui', '@afrotransact/api-client', '@afrotransact/tokens'],
  // Allows a separate build output (e.g. NEXT_DIST_DIR=.next-e2e) without clobbering .next.
  distDir: process.env.NEXT_DIST_DIR || '.next',
  experimental: { optimizePackageImports: ['@phosphor-icons/react'] },
  images: { unoptimized: true },
  async headers() {
    return [{ source: '/:path*', headers: securityHeaders }];
  }
};

export default nextConfig;
