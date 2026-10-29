import { type Me } from '@afrotransact/api-client';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AccountMenu } from '@/components/shell/AccountMenu';
import { accountMenuModel } from '@/components/shell/accountMenuItems';

import { renderWithProviders } from './render';

const guest: Me = { authenticated: false, user: null, counts: null };
const ada: Me = {
  authenticated: true,
  user: { id: 'u1', firstName: 'Adaeze', lastName: 'Nwosu', email: 'adaeze@example.com', initials: 'AN', roles: ['customer'], backOffices: [] },
  counts: { orders: 2, addresses: 2, paymentMethods: 3 }
};

describe('avatar menu variants', () => {
  it('guest: "Welcome, Guest" with Sign in and Create account only', () => {
    renderWithProviders(<AccountMenu me={guest} onAskLola={vi.fn()} inline defaultOpen />);
    const menu = screen.getByRole('menu');
    expect(within(menu).getByText('Welcome, Guest')).toBeInTheDocument();
    expect(within(menu).getByText('Sign in for orders, returns and faster checkout')).toBeInTheDocument();
    expect(within(menu).getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Sign in', 'Create account']);
    expect(within(menu).queryByText('Sign out')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Account menu: Welcome, Guest/ })).toBeInTheDocument();
  });

  it('signed in: initials avatar, account items with counts, Ask Lola, Sell, Offer services and Sign out', () => {
    renderWithProviders(<AccountMenu me={ada} onAskLola={vi.fn()} inline defaultOpen />);
    const menu = screen.getByRole('menu');
    expect(within(menu).getByText('Adaeze Nwosu')).toBeInTheDocument();
    expect(within(menu).getByText('adaeze@example.com')).toBeInTheDocument();
    const labels = within(menu).getAllByRole('menuitem').map((i) => i.textContent);
    expect(labels).toEqual([
      'Your account',
      'Your orders2',
      'Get help with an order',
      'Addresses2',
      'Payment methods3',
      'Gift cards & credit',
      'Login & security',
      'Ask Lola',
      'Sell on AfroTransact',
      'Offer your services',
      'Sign out'
    ]);
    expect(screen.getByRole('button', { name: /Account menu: Adaeze Nwosu/ })).toHaveTextContent('AN');
  });

  it('builds return-to links for guests', () => {
    const m = accountMenuModel(guest, '/p/AT000000P2');
    expect(m.items.map((i) => i.href)).toEqual(['/api/bff/v1/auth/login?returnTo=%2Fp%2FAT000000P2', '/api/bff/v1/auth/login?returnTo=%2Fp%2FAT000000P2&prompt=create']);
    expect(m.items.every((i) => i.external)).toBe(true);
    expect(m.showSignOut).toBe(false);
  });
});

describe('sign out', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('ends the identity session too and comes back to this page signed out', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, pathname: '/p/AT000000P2', search: '', assign });
    const fetchMock = vi.fn(async (_url: string | URL | Request, _init?: RequestInit) =>
      Response.json({ redirectTo: 'http://localhost:8100/connect/logout?id_token_hint=x' })
    );
    vi.stubGlobal('fetch', fetchMock);
    renderWithProviders(<AccountMenu me={ada} onAskLola={vi.fn()} inline defaultOpen />);
    fireEvent.click(within(screen.getByRole('menu')).getByText('Sign out'));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('http://localhost:8100/connect/logout?id_token_hint=x'));
    const [input, init] = fetchMock.mock.calls[0] ?? [];
    const req = input instanceof Request ? input : new Request(new URL(String(input), 'http://localhost'), init);
    expect(req.url).toContain('/v1/auth/sign-out');
    expect(await req.clone().json()).toEqual({ returnTo: '/p/AT000000P2' });
  });
});
