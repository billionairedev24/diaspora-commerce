import { sessionEndBus } from '@afrotransact/api-client';
import { endMockSession, resetMockSession, resetStaffDb, setMockSessionPolicy } from '@afrotransact/api-client/mocks/staff';
import { announceSignOut } from '@afrotransact/ui';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ConsoleShell } from '@/components/shell/ConsoleShell';
import { sessionReturnTo } from '@/lib/session-return';

import { asRoles } from './mock-api';
import { nav } from './setup';

vi.mock('@afrotransact/ui', async () => ({ ...(await vi.importActual<Record<string, unknown>>('@afrotransact/ui')), announceSignOut: vi.fn() }));

const page = () => (
  <ConsoleShell>
    <p>staff page body</p>
  </ConsoleShell>
);

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}>{page()}</QueryClientProvider>);
  return { client, ...view };
}

/** The BFF's sign-out answer, and the address the browser is sent to. */
function stubBrowser() {
  const assign = vi.fn();
  vi.stubGlobal('location', { ...window.location, pathname: nav.pathname, search: nav.search, assign });
  const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => Response.json({ redirectTo: 'http://identity.test/connect/logout?x=1' }));
  vi.stubGlobal('fetch', fetchMock);
  return { assign, fetchMock };
}

describe('staff console: sign-out timer', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    resetMockSession('staff');
    asRoles(null);
    nav.set('/users');
    vi.mocked(announceSignOut).mockClear();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('mounts for a signed-in staff member, and reads the policy without counting as activity', async () => {
    const seen: Request[] = [];
    const { interceptStaff } = await import('./mock-api');
    interceptStaff(async (req, next) => {
      seen.push(req.clone());
      return next(req);
    });
    try {
      mount();
      await screen.findByText('staff page body');
      await waitFor(() => expect(seen.some((r) => new URL(r.url).pathname.endsWith('/v1/session/policy'))).toBe(true));
      const policy = seen.find((r) => new URL(r.url).pathname.endsWith('/v1/session/policy'));
      expect(policy?.headers.get('X-Background')).toBe('1');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(seen.some((r) => new URL(r.url).pathname.endsWith('/v1/session/activity'))).toBe(false);
    } finally {
      interceptStaff(null);
    }
  });

  it('warns before the end with a live countdown, and "Stay signed in" keeps the person signed in', async () => {
    setMockSessionPolicy({ idle: 6, warn: 5, absolute: 3600 });
    mount();
    await screen.findByText('staff page body');
    const dialog = await screen.findByRole('dialog', { name: 'You’re about to be signed out' }, { timeout: 5000 });
    expect(within(dialog).getAllByText(/You’ll be signed out in \d:\d\d because you’ve been inactive\./).length).toBeGreaterThan(0);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stay signed in' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByText('staff page body')).toBeInTheDocument();
  }, 15_000);

  it('"Sign out now" signs out for real and tells the other tabs', async () => {
    const { assign, fetchMock } = stubBrowser();
    setMockSessionPolicy({ idle: 8, warn: 7, absolute: 3600 });
    mount();
    const dialog = await screen.findByRole('dialog', { name: 'You’re about to be signed out' }, { timeout: 5000 });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Sign out now' }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('http://identity.test/connect/logout?x=1'));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(announceSignOut).toHaveBeenCalledTimes(1);
  }, 15_000);

  it('at the end it clears every cached query, drops the page and says why', async () => {
    const { assign, fetchMock } = stubBrowser();
    setMockSessionPolicy({ idle: 3, warn: 2, absolute: 3600 });
    const { client } = mount();
    await screen.findByText('staff page body');
    expect(client.getQueryCache().getAll().length).toBeGreaterThan(0);
    const ended = await screen.findByRole('alertdialog', { name: 'You’ve been signed out' }, { timeout: 8000 });
    expect(ended).toHaveTextContent('You were inactive for a while');
    expect(screen.queryByText('staff page body')).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Staff console' })).not.toBeInTheDocument();
    expect(client.getQueryCache().getAll()).toEqual([]);
    // The server is told, with the page to come back to; then the button leads to identity's end-session URL
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({ returnTo: '/users' });
    const button = await within(ended).findByRole('button', { name: 'Sign in again' });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    expect(assign).toHaveBeenCalledWith('http://identity.test/connect/logout?x=1');
  }, 20_000);

  it('a 401 with the absolute-limit code from any request ends it at once with that reason', async () => {
    stubBrowser();
    mount();
    await screen.findByText('staff page body');
    endMockSession('SESSION_ABSOLUTE_TIMEOUT');
    await act(async () => {
      fireEvent.pointerDown(window); // real input: the report is refused with 401 SESSION_ABSOLUTE_TIMEOUT
    });
    const ended = await screen.findByRole('alertdialog', { name: 'You’ve been signed out' });
    expect(ended).toHaveTextContent('maximum length');
    expect(sessionEndBus.ended()).toBe('SESSION_ABSOLUTE_TIMEOUT');
  });

  it('the absolute limit warns but offers no "Stay signed in"', async () => {
    setMockSessionPolicy({ idle: 3600, warn: 5, absolute: 60, signInAgeSeconds: 57 });
    mount();
    const dialog = await screen.findByRole('dialog', { name: 'You’re about to be signed out' });
    expect(dialog).toHaveTextContent(/you need to sign in again at/);
    expect(dialog).toHaveTextContent('staying active won’t extend it');
    expect(within(dialog).queryByRole('button', { name: 'Stay signed in' })).not.toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Got it' })).toBeInTheDocument();
  });

  it('does not return to sensitive pages after signing in again', async () => {
    const { fetchMock } = stubBrowser();
    nav.set('/settings');
    vi.stubGlobal('location', { ...window.location, pathname: '/settings/identity', search: '', assign: vi.fn() });
    mount();
    await screen.findByText('staff page body');
    endMockSession();
    await act(async () => {
      fireEvent.keyDown(window, { key: 'a' });
    });
    await screen.findByRole('alertdialog');
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({});
  });
});

describe('where the staff console returns after signing in again', () => {
  it('keeps ordinary pages and their filters', () => {
    expect(sessionReturnTo('/users', '?q=ada')).toBe('/users?q=ada');
    expect(sessionReturnTo('/preorders/abc')).toBe('/preorders/abc');
  });
  it('drops the landing page, security and account pages and anything that is not a path', () => {
    expect(sessionReturnTo('/')).toBeUndefined();
    for (const p of ['/settings', '/settings/identity', '/account', '/account/security']) expect(sessionReturnTo(p)).toBeUndefined();
    expect(sessionReturnTo('//evil.example/x')).toBeUndefined();
    expect(sessionReturnTo('https://evil.example')).toBeUndefined();
    expect(sessionReturnTo('/settingsx')).toBe('/settingsx');
  });
});
