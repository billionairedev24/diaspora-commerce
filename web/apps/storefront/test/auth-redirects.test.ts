import { beforeEach, describe, expect, it, vi } from 'vitest';

const redirect = vi.fn((url: string) => {
  throw new Error(`NEXT_REDIRECT ${url}`);
});
vi.mock('next/navigation', () => ({ redirect: (url: string) => redirect(url) }));
vi.mock('server-only', () => ({}));
vi.mock('@/i18n/server', async () => {
  const { tEn: t } = await import('@/i18n/t');
  return { getLocale: async () => 'en', getT: async () => t };
});

const { hostedLoginTarget } = await import('@/components/auth/hostedLogin');
const { signInHref, signUpHref } = await import('@/lib/links');
const { default: SignInPage } = await import('@/app/(auth)/signin/page');
const { default: SignUpPage } = await import('@/app/(auth)/signup/page');

describe('sign-in links go to identity’s hosted pages through the BFF (single sign-on)', () => {
  it('builds the BFF login start with returnTo, and prompt=create for Create account', () => {
    expect(signInHref()).toBe('/api/bff/v1/auth/login');
    expect(signInHref('/cart')).toBe('/api/bff/v1/auth/login?returnTo=%2Fcart');
    expect(signUpHref('/checkout')).toBe('/api/bff/v1/auth/login?returnTo=%2Fcheckout&prompt=create');
    expect(signUpHref(undefined, { loginHint: 'ada@example.com', invitation: 'inv-1' })).toBe(
      '/api/bff/v1/auth/login?prompt=create&login_hint=ada%40example.com&invitation=inv-1'
    );
  });

  it('maps legacy /signin and /signup query strings, including invite links and other apps’ returnTo', () => {
    expect(hostedLoginTarget({ returnTo: '/orders' }, 'signin')).toEqual({
      href: '/api/bff/v1/auth/login?returnTo=%2Forders',
      error: null,
      returnTo: '/orders'
    });
    expect(hostedLoginTarget({ invitation: 'tok', email: 'kemi@example.com' }, 'signup').href).toBe(
      '/api/bff/v1/auth/login?prompt=create&login_hint=kemi%40example.com&invitation=tok'
    );
    // Seller Center / staff console pass absolute URLs; the BFF checks them against its allowed origins.
    expect(hostedLoginTarget({ returnTo: 'http://localhost:3002/users' }, 'signin').href).toBe(
      '/api/bff/v1/auth/login?returnTo=http%3A%2F%2Flocalhost%3A3002%2Fusers'
    );
    expect(hostedLoginTarget({ error: 'exchange_failed', returnTo: '/cart' }, 'signin').error).toBe('exchange_failed');
  });
});

describe('/signin and /signup routes stay for old links and redirect', () => {
  beforeEach(() => {
    redirect.mockClear();
  });

  it('/signin redirects to the hosted sign-in start', async () => {
    await expect(SignInPage({ searchParams: Promise.resolve({ returnTo: '/account' }) })).rejects.toThrow('NEXT_REDIRECT');
    expect(redirect).toHaveBeenCalledWith('/api/bff/v1/auth/login?returnTo=%2Faccount');
  });

  it('/signin directly redirects to the foreign app login start without showing an error page when returnTo is external', async () => {
    await expect(SignInPage({ searchParams: Promise.resolve({ error: 'state_mismatch', returnTo: 'http://localhost:3010/' }) })).rejects.toThrow('NEXT_REDIRECT');
    expect(redirect).toHaveBeenCalledWith('/api/bff/v1/auth/login?returnTo=http%3A%2F%2Flocalhost%3A3010%2F');
  });

  it('/signin shows the error page instead of redirecting when error is set for local storefront returnTo', async () => {
    const page = await SignInPage({ searchParams: Promise.resolve({ error: 'exchange_failed', returnTo: '/cart' }) });
    expect(redirect).not.toHaveBeenCalled();
    expect(page).toBeDefined();
  });

  it('/signup redirects to the hosted sign-up page (prompt=create)', async () => {
    await expect(SignUpPage({ searchParams: Promise.resolve({ returnTo: '/cart' }) })).rejects.toThrow('NEXT_REDIRECT');
    expect(redirect).toHaveBeenCalledWith('/api/bff/v1/auth/login?returnTo=%2Fcart&prompt=create');
  });
});
