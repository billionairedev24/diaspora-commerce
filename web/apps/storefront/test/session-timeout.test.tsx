import { sessionEndBus } from '@afrotransact/api-client';
import { endMockSession, resetMockSession, setMockSessionPolicy } from '@afrotransact/api-client/mocks';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useSearchParams } from 'next/navigation';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { SignedOutNotice } from '@/components/shell/SignedOutNotice';
import { signedOutLanding, StorefrontSessionTimeout } from '@/components/shell/StorefrontSessionTimeout';

import { installMockBff, sent, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const page = (
  <StorefrontSessionTimeout>
    <p>account page</p>
  </StorefrontSessionTimeout>
);

const isPolicy = (r: Request) => new URL(r.url).pathname.endsWith('/v1/session/policy');

describe('storefront: sign-out timer', () => {
  const assign = vi.fn();
  beforeEach(() => {
    resetMockSession('customer-web');
    assign.mockReset();
    vi.stubGlobal('location', { ...window.location, pathname: '/p/AT000000P2', search: '?v=2', assign });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    sessionEndBus.reset();
  });

  it('does nothing for a guest: no policy request, the page is untouched', async () => {
    renderWithProviders(page);
    await screen.findByText('account page');
    await waitFor(() => expect(sent.some((r) => new URL(r.url).pathname.endsWith('/v1/me'))).toBe(true));
    await act(async () => {
      fireEvent.keyDown(window, { key: 'a' });
    });
    expect(sent.some(isPolicy)).toBe(false);
    expect(sent.some((r) => new URL(r.url).pathname.endsWith('/v1/session/activity'))).toBe(false);
  });

  it('runs for a signed-in shopper: reads the policy in the background and shows no warning (no warning for shoppers)', async () => {
    await signInDemo();
    renderWithProviders(page);
    await waitFor(() => expect(sent.some(isPolicy)).toBe(true));
    expect(sent.find(isPolicy)?.headers.get('X-Background')).toBe('1');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByText('account page')).toBeInTheDocument();
  });

  it('a 401 SESSION_IDLE_TIMEOUT clears the account from the page and lands on the same page as a guest', async () => {
    await signInDemo();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}>{page}</QueryClientProvider>);
    await waitFor(() => expect(sent.some(isPolicy)).toBe(true));
    endMockSession('SESSION_IDLE_TIMEOUT');
    await act(async () => {
      fireEvent.pointerDown(window); // real input: reported to the server, answered 401
    });
    const ended = await screen.findByRole('alertdialog');
    expect(ended).toHaveTextContent('You were inactive for a while');
    expect(screen.queryByText('account page')).not.toBeInTheDocument();
    expect(client.getQueryCache().getAll()).toEqual([]);
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1));
    // identity's end-session / the BFF's signed-out landing, which brings the shopper back to this page as a guest
    expect(String(assign.mock.calls[0]?.[0])).toMatch(/signedOut=1|signed-out/);
    const signOut = sent.find((r) => new URL(r.url).pathname.endsWith('/v1/auth/sign-out'));
    expect(await signOut?.json()).toEqual({ returnTo: '/p/AT000000P2?v=2' });
  });

  it('lands on the page the shopper was on, never on an account page', () => {
    expect(signedOutLanding('/p/AT000000P2?v=2')).toBe('/p/AT000000P2?v=2&signedOut=1');
    expect(signedOutLanding(undefined)).toBe('/?signedOut=1');
  });
});

describe('storefront: the signed-out message says why', () => {
  beforeEach(() => {
    resetMockSession('customer-web');
    vi.mocked(useSearchParams).mockReturnValue(new URLSearchParams('signedOut=1') as never);
  });
  afterEach(() => {
    vi.mocked(useSearchParams).mockReturnValue(new URLSearchParams() as never);
  });

  it('idle: from /v1/me signedOutReason, even though this page never saw the 401', async () => {
    endMockSession('SESSION_IDLE_TIMEOUT');
    renderWithProviders(<SignedOutNotice />);
    expect(await screen.findByText(/You were inactive for a while/)).toBeInTheDocument();
  });

  it('absolute limit', async () => {
    endMockSession('SESSION_ABSOLUTE_TIMEOUT');
    renderWithProviders(<SignedOutNotice />);
    expect(await screen.findByText(/maximum length/)).toBeInTheDocument();
  });

  it('a plain sign-out keeps the usual message', async () => {
    setMockSessionPolicy({ audience: 'customer-web' });
    renderWithProviders(<SignedOutNotice />);
    expect(await screen.findByText('You’re signed out')).toBeInTheDocument();
  });
});
