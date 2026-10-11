import { afterEach, describe, expect, it, vi } from 'vitest';

import { loginHref, signOutEverywhere } from '@/lib/auth';

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

  it('follows identity’s end-session URL so the identity session ends too', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ redirectTo: 'http://localhost:8100/connect/logout?id_token_hint=x' })));
    await signOutEverywhere('http://localhost:3000');
    expect(assign).toHaveBeenCalledWith('http://localhost:8100/connect/logout?id_token_hint=x');
  });

  it('falls back to the given URL without an identity session', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ redirectTo: null })));
    await signOutEverywhere('http://localhost:3000');
    expect(assign).toHaveBeenCalledWith('http://localhost:3000');
  });
});
