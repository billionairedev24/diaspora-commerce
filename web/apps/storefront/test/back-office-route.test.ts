import { afterEach, describe, expect, it, vi } from 'vitest';

const redirect = vi.fn((url: string) => {
  throw new Error(`NEXT_REDIRECT ${url}`);
});
const notFound = vi.fn(() => {
  throw new Error('NEXT_NOT_FOUND');
});
vi.mock('next/navigation', () => ({ redirect: (url: string) => redirect(url), notFound: () => notFound() }));

const { backOfficeHref, backOfficeUrl } = await import('@/lib/back-office');
const { GET } = await import('@/app/back-office/[app]/route');
const { NextRequest } = await import('next/server');

describe('/back-office/<app>: the configured app URLs, signed in through single sign-on', () => {
  const urls = { sellerCenterUrl: 'http://localhost:3010/', staffConsoleUrl: 'http://localhost:3012' };
  const decoded = (url: string | null) => {
    const u = new URL(url ?? '');
    return { path: `${u.origin}${u.pathname}`, returnTo: u.searchParams.get('returnTo') };
  };

  afterEach(() => {
    vi.unstubAllEnvs();
    redirect.mockClear();
  });

  it('maps each back office to its app', () => {
    expect(decoded(backOfficeUrl('seller-center', null, urls))).toEqual({ path: 'http://localhost:3010/api/bff/v1/auth/login', returnTo: 'http://localhost:3010/' });
    expect(decoded(backOfficeUrl('seller-center', 'application', urls)).returnTo).toBe('http://localhost:3010/onboarding?role=seller');
    expect(decoded(backOfficeUrl('provider-hub', 'application', urls)).returnTo).toBe('http://localhost:3010/onboarding?role=provider');
    expect(decoded(backOfficeUrl('provider-hub', null, urls)).returnTo).toBe('http://localhost:3010/');
    expect(decoded(backOfficeUrl('staff-console', null, urls))).toEqual({ path: 'http://localhost:3012/api/bff/v1/auth/login', returnTo: 'http://localhost:3012/' });
    expect(backOfficeUrl('grafana', null, urls)).toBeNull();
    expect(backOfficeHref({ app: 'provider-hub', state: 'application' })).toBe('/back-office/provider-hub?state=application');
  });

  it('reads SELLER_CENTER_URL and STAFF_CONSOLE_URL per request', async () => {
    vi.stubEnv('SELLER_CENTER_URL', 'http://localhost:3010');
    vi.stubEnv('STAFF_CONSOLE_URL', 'http://localhost:3012');
    const call = (app: string, query = '') =>
      GET(new NextRequest(`http://localhost:3000/back-office/${app}${query}`), { params: Promise.resolve({ app }) });

    await expect(call('seller-center', '?state=application')).rejects.toThrow('NEXT_REDIRECT');
    expect(decoded(redirect.mock.calls[0]?.[0] ?? null).returnTo).toBe('http://localhost:3010/onboarding?role=seller');
    await expect(call('staff-console')).rejects.toThrow('NEXT_REDIRECT');
    expect(decoded(redirect.mock.calls[1]?.[0] ?? null).path).toBe('http://localhost:3012/api/bff/v1/auth/login');
    await expect(call('nope')).rejects.toThrow('NEXT_NOT_FOUND');
  });
});
