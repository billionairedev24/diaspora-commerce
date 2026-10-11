import { createSellerApiClient } from '@afrotransact/api-client';
import { PENDING_ORG, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { handleSellerMockRequest } from '@afrotransact/api-client/mocks/seller-runtime';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PortalShell } from '@/components/shell/PortalShell';
import { AutoSignIn, ShellLoading, SIGN_IN_LOOP_MS } from '@/components/shell/ShellStates';
import { signOutEverywhere } from '@/lib/auth';

import { renderWithProviders } from './render';
import { nav } from './setup';

/* The shell never loads forever: a session that doesn't answer offers Retry and Sign out, a failing one says why,
   and a sign-in that comes back still signed out stops instead of looping. */
const state: { mode: 'ok' | 'hang' | 'fail' | 'signedOut' } = { mode: 'ok' };
vi.mock('@/lib/api', () => ({
  sellerApi: () =>
    createSellerApiClient({
      baseUrl: 'http://seller.test/api/bff',
      fetch: (req: Request) => {
        const session = new URL(req.url).pathname.endsWith('/v1/seller/session');
        if (session && state.mode === 'hang') return new Promise<Response>(() => undefined);
        if (session && state.mode === 'fail') {
          return Promise.resolve(Response.json({ type: 'about:blank', title: 'Service unavailable', status: 503, code: 'UPSTREAM_UNAVAILABLE', detail: 'Identity is down.' }, { status: 503, headers: { 'content-type': 'application/problem+json' } }));
        }
        if (state.mode === 'signedOut') {
          return Promise.resolve(Response.json({ type: 'about:blank', title: 'Unauthorized', status: 401, code: 'UNAUTHENTICATED' }, { status: 401, headers: { 'content-type': 'application/problem+json' } }));
        }
        return handleSellerMockRequest(req);
      }
    })
}));
vi.mock('@/lib/auth', async (orig) => ({ ...(await orig<Record<string, unknown>>()), signOutEverywhere: vi.fn(() => Promise.resolve()) }));

const assign = vi.fn();

describe('shell loading and sign-in states', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    state.mode = 'ok';
    nav.pathname = '/onboarding';
    nav.replace.mockReset();
    assign.mockReset();
    window.sessionStorage.clear();
    vi.stubGlobal('location', { ...window.location, href: 'http://localhost/onboarding?role=seller', assign });
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('a loader that runs long says so and offers Try again and Sign out', async () => {
    const user = userEvent.setup();
    const retry = vi.fn();
    renderWithProviders(<ShellLoading label="Loading your back office…" onRetry={retry} slowAfterMs={30} />);
    expect(screen.getByText('Loading your back office…')).toBeInTheDocument();
    expect(await screen.findByText('This is taking longer than usual')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(retry).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(signOutEverywhere).toHaveBeenCalled();
  });

  it('a session request that never answers does not leave the page loading forever', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    state.mode = 'hang';
    renderWithProviders(
      <PortalShell>
        <p>page</p>
      </PortalShell>
    );
    expect(screen.getByText('Loading your back office…')).toBeInTheDocument();
    await act(async () => {
      vi.advanceTimersByTime(10_500);
    });
    expect(screen.getByText('This is taking longer than usual')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });

  it('a failing session shows the reason with Try again and Sign out', async () => {
    state.mode = 'fail';
    renderWithProviders(
      <PortalShell>
        <p>page</p>
      </PortalShell>
    );
    expect(await screen.findByText('Identity is down.', {}, { timeout: 8000 })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Try again|Retry/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });

  it('signed out: goes to the hosted sign-in once, and stops if it comes back still signed out', async () => {
    const first = renderWithProviders(<AutoSignIn role="seller" label="Taking you to sign in…" />);
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1));
    expect(String(assign.mock.calls[0]?.[0])).toContain('intent=sell');
    first.unmount();

    // Back from identity, still without a Seller Center session: no second round trip.
    renderWithProviders(<AutoSignIn role="seller" label="Taking you to sign in…" />);
    expect(await screen.findByRole('heading', { name: 'We couldn’t sign you in to Seller Center' })).toBeInTheDocument();
    expect(assign).toHaveBeenCalledTimes(1);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));
    expect(assign).toHaveBeenCalledTimes(2);
  });

  it('an old sign-in attempt does not count: an expired session signs in again straight away', async () => {
    window.sessionStorage.setItem('at.sc.signInStartedAt', String(Date.now() - SIGN_IN_LOOP_MS - 1000));
    state.mode = 'signedOut';
    renderWithProviders(
      <PortalShell>
        <p>page</p>
      </PortalShell>
    );
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1));
  });

  it('while the application is in progress, back-office routes and My account lead to onboarding', async () => {
    const api = createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', fetch: (req: Request) => handleSellerMockRequest(req) });
    await api.setActiveOrg(PENDING_ORG);
    nav.pathname = '/account/preferences';
    renderWithProviders(
      <PortalShell>
        <p>page</p>
      </PortalShell>
    );
    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/onboarding'));
    expect(window.sessionStorage.getItem('at.sc.signInStartedAt')).toBeNull();
  });
});
