import { type BackOffice, type Me, MeSchema } from '@afrotransact/api-client';
import { screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { AccountMenu } from '@/components/shell/AccountMenu';
import { accountMenuModel } from '@/components/shell/accountMenuItems';

import { renderWithProviders } from './render';

const person = (backOffices: BackOffice[]): Me => ({
  authenticated: true,
  user: { id: 'u1', firstName: 'Malo', lastName: 'Adeyemi', email: 'malo@example.com', initials: 'MA', roles: ['account.customer'], backOffices },
  counts: { orders: 0, returns: 0, addresses: 1, paymentMethods: 1 }
});

const backOfficeItems = (me: Me) =>
  accountMenuModel(me).items.filter((i) => ['sell', 'provide', 'seller-center', 'provider-hub', 'staff-console'].includes(i.id));

describe('account menu back offices', () => {
  it('an approved seller (APP-30200 Malo Branding) gets Seller Center instead of Sell on AfroTransact', () => {
    renderWithProviders(<AccountMenu me={person([{ app: 'seller-center', state: 'active' }])} onAskLola={vi.fn()} inline defaultOpen />);
    const labels = within(screen.getByRole('menu')).getAllByRole('menuitem').map((i) => i.textContent);
    expect(labels).toContain('Seller Center');
    expect(labels).not.toContain('Sell on AfroTransact');
    expect(labels).toContain('Offer your services');
    expect(labels).toContain('Your orders');
    expect(backOfficeItems(person([{ app: 'seller-center', state: 'active' }]))[0]).toMatchObject({
      id: 'seller-center', href: '/back-office/seller-center', external: true
    });
  });

  it('an application in progress continues it; providers and staff get their back offices', () => {
    const items = backOfficeItems(person([
      { app: 'seller-center', state: 'application' },
      { app: 'provider-hub', state: 'active' },
      { app: 'staff-console', state: 'active' }
    ]));
    expect(items.map((i) => [i.label, i.href])).toEqual([
      ['account.sellerApplication', '/back-office/seller-center?state=application'],
      ['account.providerHub', '/back-office/provider-hub'],
      ['account.staffConsole', '/back-office/staff-console']
    ]);
    renderWithProviders(<AccountMenu me={person([{ app: 'seller-center', state: 'application' }, { app: 'staff-console', state: 'active' }])} onAskLola={vi.fn()} inline defaultOpen />);
    const menu = screen.getByRole('menu');
    expect(within(menu).getByText('Continue your seller application')).toBeInTheDocument();
    expect(within(menu).getByText('Staff console')).toBeInTheDocument();
  });

  it('a shopper (or an older BFF without back offices) keeps Sell on AfroTransact and Offer your services', () => {
    expect(backOfficeItems(person([])).map((i) => i.href)).toEqual(['/sell', '/provide']);
    const legacy = MeSchema.parse({ authenticated: true, counts: null,
      user: { id: 'u1', firstName: 'A', lastName: 'B', email: 'a@b.c', initials: 'AB', roles: [] } });
    expect(backOfficeItems(legacy).map((i) => i.id)).toEqual(['sell', 'provide']);
  });
});
