import { type Me } from '@afrotransact/api-client';
import { screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { AccountMenu } from '@/components/shell/AccountMenu';
import { accountMenuModel } from '@/components/shell/accountMenuItems';

import { renderWithProviders } from './render';

const guest: Me = { authenticated: false, user: null, counts: null };
const ada: Me = {
  authenticated: true,
  user: { id: 'u1', firstName: 'Adaeze', lastName: 'Nwosu', email: 'adaeze@example.com', initials: 'AN', roles: ['customer'] },
  counts: { orders: 2, returns: 1, addresses: 2, paymentMethods: 3 }
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
      'Returns & refunds1',
      'Addresses2',
      'Payment methods3',
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
    expect(m.items.map((i) => i.href)).toEqual(['/signin?returnTo=%2Fp%2FAT000000P2', '/signup?returnTo=%2Fp%2FAT000000P2']);
    expect(m.showSignOut).toBe(false);
  });
});
