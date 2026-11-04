import { beforeEach, describe, expect, it, vi } from 'vitest';

const redirect = vi.fn((url: string) => {
  throw new Error(`NEXT_REDIRECT ${url}`);
});
const getMe = vi.fn();
vi.mock('next/navigation', () => ({ redirect: (url: string) => redirect(url) }));
vi.mock('server-only', () => ({}));
vi.mock('@/lib/api/server', () => ({ serverApi: () => Promise.resolve({ getMe }) }));

const { onboardingUrl, sellEntryUrl } = await import('@/lib/sell/entry');
const { GET: sell } = await import('@/app/sell/route');
const { GET: provide } = await import('@/app/provide/route');

const SC = 'http://localhost:3010';
const decoded = (url: string) => {
  const u = new URL(url);
  return { path: `${u.origin}${u.pathname}`, params: Object.fromEntries(u.searchParams) };
};

describe('Start selling / Offer your services entry (S284)', () => {
  it('signed in: plain hosted sign-in on Seller Center, which single sign-on passes straight through to onboarding', () => {
    expect(decoded(sellEntryUrl({ role: 'seller', signedIn: true, sellerCenterUrl: SC }))).toEqual({
      path: `${SC}/api/bff/v1/auth/login`,
      params: { returnTo: `${SC}/onboarding?role=seller` }
    });
  });

  it('signed out: identity’s create-account page with the seller or provider copy, then onboarding', () => {
    expect(decoded(sellEntryUrl({ role: 'seller', signedIn: false, sellerCenterUrl: `${SC}/` }))).toEqual({
      path: `${SC}/api/bff/v1/auth/login`,
      params: { returnTo: `${SC}/onboarding?role=seller`, prompt: 'create', intent: 'sell' }
    });
    expect(decoded(sellEntryUrl({ role: 'provider', signedIn: false, sellerCenterUrl: SC })).params).toMatchObject({ prompt: 'create', intent: 'provide' });
    expect(onboardingUrl(SC, 'provider')).toBe(`${SC}/onboarding?role=provider`);
  });

  describe('routes use the configured SELLER_CENTER_URL and the storefront session', () => {
    beforeEach(() => {
      redirect.mockClear();
      getMe.mockReset();
      vi.stubEnv('SELLER_CENTER_URL', SC);
    });

    it('/sell for an approved seller opens the Seller Center dashboard, not a new application', async () => {
      getMe.mockResolvedValue({ authenticated: true, user: { backOffices: [{ app: 'seller-center', state: 'active' }] } });
      await expect(sell()).rejects.toThrow('NEXT_REDIRECT');
      expect(redirect).toHaveBeenCalledWith(`${SC}/api/bff/v1/auth/login?returnTo=${encodeURIComponent(`${SC}/`)}`);
      // Offering services is a separate application: still onboarding for the provider role.
      redirect.mockClear();
      await expect(provide()).rejects.toThrow('NEXT_REDIRECT');
      expect(redirect).toHaveBeenCalledWith(`${SC}/api/bff/v1/auth/login?returnTo=${encodeURIComponent(`${SC}/onboarding?role=provider`)}`);
    });

    it('/sell for a signed-in shopper never asks for credentials', async () => {
      getMe.mockResolvedValue({ authenticated: true });
      await expect(sell()).rejects.toThrow('NEXT_REDIRECT');
      expect(redirect).toHaveBeenCalledWith(`${SC}/api/bff/v1/auth/login?returnTo=${encodeURIComponent(`${SC}/onboarding?role=seller`)}`);
    });

    it('/sell for a guest (or when the session check fails) creates an account first', async () => {
      getMe.mockResolvedValue({ authenticated: false });
      await expect(sell()).rejects.toThrow('NEXT_REDIRECT');
      expect(redirect.mock.calls[0]?.[0]).toContain('prompt=create&intent=sell');
      getMe.mockRejectedValue(new Error('down'));
      await expect(provide()).rejects.toThrow('NEXT_REDIRECT');
      expect(redirect.mock.calls[1]?.[0]).toContain(`returnTo=${encodeURIComponent(`${SC}/onboarding?role=provider`)}&prompt=create&intent=provide`);
    });
  });
});
