import { sessionEndBus } from '@afrotransact/api-client';
import { endMockSession, resetMockSession, resetSellerDb, setMockSessionPolicy } from '@afrotransact/api-client/mocks/seller';
import { announceSignOut } from '@afrotransact/ui';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PortalShell } from '@/components/shell/PortalShell';
import { sessionReturnTo } from '@/lib/session-return';

import { asRole } from './mock-api';
import { nav } from './setup';

vi.mock('@afrotransact/ui', async () => ({ ...(await vi.importActual<Record<string, unknown>>('@afrotransact/ui')), announceSignOut: vi.fn() }));

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={client}>
      <PortalShell>
        <p>seller page body</p>
      </PortalShell>
    </QueryClientProvider>
  );
  return { client, ...view };
}

function stubBrowser(pathname = '/orders') {
  const assign = vi.fn();
  vi.stubGlobal('location', { ...window.location, pathname, search: '', assign });
  const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => Response.json({ redirectTo: 'http://identity.test/connect/logout?x=1' }));
  vi.stubGlobal('fetch', fetchMock);
  return { assign, fetchMock };
}

describe('Seller Center: sign-out timer', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    resetMockSession('seller');
    asRole(null);
    nav.pathname = '/orders';
    vi.mocked(announceSignOut).mockClear();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('mounts for a signed-in seller without a warning', async () => {
    mount();
    await screen.findByText('seller page body');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('warns before the end, and "Stay signed in" keeps the seller signed in', async () => {
    setMockSessionPolicy({ idle: 6, warn: 5, absolute: 3600 });
    mount();
    await screen.findByText('seller page body');
    const dialog = await screen.findByRole('dialog', { name: 'You’re about to be signed out' }, { timeout: 5000 });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stay signed in' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByText('seller page body')).toBeInTheDocument();
  }, 15_000);

  it('at the end it clears the cache, drops the page, says why and leads to sign-in with the page to return to', async () => {
    const { assign, fetchMock } = stubBrowser('/orders');
    setMockSessionPolicy({ idle: 3, warn: 2, absolute: 3600 });
    const { client } = mount();
    await screen.findByText('seller page body');
    const ended = await screen.findByRole('alertdialog', { name: 'You’ve been signed out' }, { timeout: 8000 });
    expect(ended).toHaveTextContent('You were inactive for a while');
    expect(screen.queryByText('seller page body')).not.toBeInTheDocument();
    expect(client.getQueryCache().getAll()).toEqual([]);
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/v1/auth/sign-out'))).toBe(true));
    const signOut = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/v1/auth/sign-out'));
    expect(JSON.parse(String(signOut?.[1]?.body))).toEqual({ returnTo: '/orders' });
    const button = within(ended).getByRole('button', { name: 'Sign in again' });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    expect(assign).toHaveBeenCalledWith('http://identity.test/connect/logout?x=1');
  }, 20_000);

  it('a 401 with the absolute-limit code from any request ends it with that reason', async () => {
    stubBrowser();
    mount();
    await screen.findByText('seller page body');
    endMockSession('SESSION_ABSOLUTE_TIMEOUT');
    await act(async () => {
      fireEvent.pointerDown(window);
    });
    expect(await screen.findByRole('alertdialog')).toHaveTextContent('maximum length');
    expect(sessionEndBus.ended()).toBe('SESSION_ABSOLUTE_TIMEOUT');
  });

  it('the absolute limit warns but cannot be extended', async () => {
    setMockSessionPolicy({ idle: 3600, warn: 5, absolute: 60, signInAgeSeconds: 57 });
    mount();
    const dialog = await screen.findByRole('dialog', { name: 'You’re about to be signed out' });
    expect(dialog).toHaveTextContent(/you need to sign in again at/);
    expect(within(dialog).queryByRole('button', { name: 'Stay signed in' })).not.toBeInTheDocument();
  });

  it('"Sign out now" signs out for real and tells the other tabs', async () => {
    const { assign } = stubBrowser();
    setMockSessionPolicy({ idle: 8, warn: 7, absolute: 3600 });
    mount();
    const dialog = await screen.findByRole('dialog', { name: 'You’re about to be signed out' }, { timeout: 5000 });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Sign out now' }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('http://identity.test/connect/logout?x=1'));
    expect(announceSignOut).toHaveBeenCalledTimes(1);
  }, 15_000);
});

describe('where Seller Center returns after signing in again', () => {
  it('keeps ordinary pages, drops the landing page, account pages and anything that is not a path', () => {
    expect(sessionReturnTo('/orders', '?status=open')).toBe('/orders?status=open');
    expect(sessionReturnTo('/')).toBeUndefined();
    expect(sessionReturnTo('/account/security')).toBeUndefined();
    expect(sessionReturnTo('//evil.example')).toBeUndefined();
  });
});
