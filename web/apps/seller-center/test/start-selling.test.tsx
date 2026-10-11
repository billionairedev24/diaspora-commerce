import { createSellerApiClient } from '@afrotransact/api-client';
import { getSellerDb, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { handleSellerMockRequest } from '@afrotransact/api-client/mocks/seller-runtime';
import { screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { OnboardingView } from '@/components/onboarding/OnboardingView';
import { PortalShell } from '@/components/shell/PortalShell';

import { renderWithProviders } from './render';
import { nav } from './setup';

/* S286: arriving from the storefront's "Start selling" (/onboarding?role=seller). The BFF is the in-process mock;
   `signedOut` makes every call answer 401 like a browser without a Seller Center session. */
const state = { signedOut: false, starts: 0 };
vi.mock('@/lib/api', () => ({
  sellerApi: () =>
    createSellerApiClient({
      baseUrl: 'http://seller.test/api/bff',
      fetch: async (req: Request) => {
        if (state.signedOut) {
          return Response.json({ type: 'about:blank', title: 'Unauthorized', status: 401, code: 'UNAUTHENTICATED' }, { status: 401, headers: { 'content-type': 'application/problem+json' } });
        }
        if (req.method === 'POST' && new URL(req.url).pathname.endsWith('/v1/seller/onboarding')) state.starts += 1;
        return handleSellerMockRequest(req);
      }
    })
}));

const assign = vi.fn();

/** Opens the URL (jsdom history) and captures full-page navigations. */
function arrive(url: string) {
  vi.unstubAllGlobals();
  window.history.replaceState({}, '', url);
  nav.pathname = new URL(url, 'http://localhost').pathname;
  vi.stubGlobal('location', { ...window.location, assign });
}

describe('Start selling arrival in Seller Center', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    state.signedOut = false;
    state.starts = 0;
    nav.replace.mockReset();
    assign.mockReset();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.replaceState({}, '', '/');
  });

  it('a signed-in customer gets the application started once, prefilled, with no sign-in page', async () => {
    getSellerDb().orgs = [];
    arrive('/onboarding?role=seller');
    renderWithProviders(
      <PortalShell>
        <OnboardingView />
      </PortalShell>
    );
    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/onboarding'));
    expect(state.starts).toBe(1);
    expect(await screen.findByText('Using your existing AfroTransact account.')).toBeInTheDocument();
    expect(screen.getByLabelText(/Legal first name/)).toHaveValue('Titilayo');
    expect(screen.getByLabelText(/Street address/)).toHaveValue('4410 Bellaire Blvd');
    expect(screen.getByLabelText(/Contact email/)).toHaveValue('titilayo@adeskitchen.com');
    expect(screen.queryByText(/Sign in to Seller Central/)).not.toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
    // A second "Start selling" resumes the same application.
    const api = createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', fetch: (req: Request) => handleSellerMockRequest(req) });
    const again = await api.startOnboarding('SELLER');
    expect(again.orgId).toBe('org_new');
    expect(getSellerDb().orgs).toHaveLength(1);
  });

  it('an approved seller opens Seller Center home instead of a new application', async () => {
    arrive('/onboarding?role=seller');
    renderWithProviders(
      <PortalShell>
        <p>page</p>
      </PortalShell>
    );
    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/'));
    expect(state.starts).toBe(0);
  });

  it('signed out: goes straight to the hosted sign-in (seller copy) and comes back to onboarding', async () => {
    state.signedOut = true;
    arrive('/onboarding?role=seller');
    renderWithProviders(
      <PortalShell>
        <p>page</p>
      </PortalShell>
    );
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1));
    const target = new URL(String(assign.mock.calls[0]?.[0]), 'http://localhost');
    expect(target.pathname).toBe('/api/bff/v1/auth/login');
    expect(target.searchParams.get('intent')).toBe('sell');
    expect(target.searchParams.get('returnTo')).toMatch(/\/onboarding\?role=seller$/);
    expect(screen.getByText('Taking you to sign in…')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign in' })).not.toBeInTheDocument();
  });

  it('a direct visit while signed out also signs in automatically (no interstitial)', async () => {
    state.signedOut = true;
    arrive('/orders');
    renderWithProviders(
      <PortalShell>
        <p>page</p>
      </PortalShell>
    );
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1));
    expect(new URL(String(assign.mock.calls[0]?.[0]), 'http://localhost').searchParams.get('intent')).toBeNull();
  });
});
