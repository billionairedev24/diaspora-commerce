import { buildCsp } from '@afrotransact/ui/csp';
import { NextRequest } from 'next/server';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { publicWebUrl, sellerCenterUrl, staffConsoleUrl } from '@/lib/config';
import { middleware } from '@/middleware';

// Free certificate layout (docs/engineering/environments.md, "Free certificate layout (flat web hosts)"): the storefront is the
// base domain, Seller Center and staff console are one label under the zone, NOT subdomains of the base. The apps read the
// three origins from the environment (the ovh-web workflow writes them into the Worker vars), never derive them from a domain.
describe('explicit web hosts', () => {
  afterEach(() => vi.unstubAllEnvs());

  const flat = () => {
    vi.stubEnv('PUBLIC_WEB_URL', 'https://test.example.test');
    vi.stubEnv('SELLER_CENTER_URL', 'https://seller-test.example.test/');
    vi.stubEnv('STAFF_CONSOLE_URL', 'https://admin-test.example.test');
  };

  it('reads the Seller Center and staff console origins as given (no seller./admin. derivation)', () => {
    flat();
    expect(sellerCenterUrl()).toBe('https://seller-test.example.test');
    expect(staffConsoleUrl()).toBe('https://admin-test.example.test');
    expect(publicWebUrl()?.origin).toBe('https://test.example.test');
  });

  it('keeps the local defaults when nothing is set', () => {
    expect(sellerCenterUrl()).toBe('http://localhost:3010');
    expect(staffConsoleUrl()).toBe('http://localhost:3002');
  });

  it('the CSP names no other app host: the browser only talks to its own origin, forms only post to it', () => {
    flat();
    vi.stubEnv('MEDIA_PUBLIC_URL', 'https://media.test.example.test');
    const policy = buildCsp('storefront', 'abc', { MEDIA_PUBLIC_URL: process.env.MEDIA_PUBLIC_URL });
    expect(policy).toContain("form-action 'self'");
    expect(policy).toContain("connect-src 'self'");
    expect(policy).not.toContain('seller-test');
    expect(policy).not.toContain('admin-test');
    expect(policy).not.toMatch(/\*\.example\.test/);
    // Product images come from the media host the deploy sets (OVH: MEDIA_PUBLIC_URL, docs/runbooks/cloudflare-setup.md).
    expect(policy).toMatch(/img-src[^;]*https:\/\/media\.test\.example\.test/);
  });

  it('the middleware allows the configured media origin in the served policy', () => {
    flat();
    vi.stubEnv('MEDIA_PUBLIC_URL', 'https://media-test.example.test');
    const response = middleware(new NextRequest('https://test.example.test/'));
    expect(response.headers.get('Content-Security-Policy-Report-Only')).toMatch(/img-src[^;]*https:\/\/media-test\.example\.test/);
  });
});
