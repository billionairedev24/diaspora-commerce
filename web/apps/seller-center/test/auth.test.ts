import { afterEach, describe, expect, it, vi } from 'vitest';

import { loginHref, signedOutHref, signOutEverywhere } from '@/lib/auth';

describe('hosted sign-in (single sign-on through identity)', () => {
  it('starts sign-in on this app’s own BFF proxy and returns to the absolute URL', () => {
    expect(loginHref('http://localhost:3001/orders')).toBe('/api/bff/v1/auth/login?returnTo=http%3A%2F%2Flocalhost%3A3001%2Forders');
    expect(loginHref('http://localhost:3001/', { prompt: 'create', loginHint: 'kemi@example.com', invitation: 'tok' })).toBe(
      '/api/bff/v1/auth/login?returnTo=http%3A%2F%2Flocalhost%3A3001%2F&prompt=create&login_hint=kemi%40example.com&invitation=tok'
    );
  });
});

describe('sign-out', () => {
  afterEach(() => vi.unstubAllGlobals());

  const stubPage = (path: string, search = '') => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, pathname: path, search, assign });
    return assign;
  };

  it('sends the page it signs out on and follows identity’s end-session URL so the identity session ends too', async () => {
    const assign = stubPage('/orders', '?tab=open');
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => Response.json({ redirectTo: 'http://localhost:8100/connect/logout?id_token_hint=x' }));
    vi.stubGlobal('fetch', fetchMock);
    await signOutEverywhere();
    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(url).toBe('/api/bff/v1/auth/sign-out');
    expect(JSON.parse(String(init?.body))).toEqual({ returnTo: '/orders?tab=open' });
    expect(assign).toHaveBeenCalledWith('http://localhost:8100/connect/logout?id_token_hint=x');
  });

  it('without an answer goes to this app’s own sign-in (never the storefront or another app)', async () => {
    const assign = stubPage('/orders');
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('offline');
    }));
    await signOutEverywhere();
    expect(assign).toHaveBeenCalledWith('/api/bff/v1/auth/signed-out/seller-center?state=%2Forders');
    expect(signedOutHref('/')).toBe('/api/bff/v1/auth/signed-out/seller-center');
  });
});
