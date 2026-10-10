import { PROVIDER_ORG, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { PayoutsView } from '@/components/money/PayoutsView';
import { OrdersView } from '@/components/orders/OrdersView';
import { EarningsView, ProviderFeesCard } from '@/components/provider/EarningsView';
import { PolicyEditor } from '@/components/provider/PolicyEditor';
import { TeamView } from '@/components/team/TeamView';

import { asRole } from './mock-api';
import { renderScreen, renderWithProviders } from './render';

describe('Seller Center screens (against the seller mocks)', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('lists orders in a table that does not scroll sideways', async () => {
    await renderScreen(<OrdersView />);
    const table = await screen.findByRole('table');
    await waitFor(() => expect(within(table).getAllByRole('row').length).toBeGreaterThan(5));
    expect(table.closest('[class*="overflow-x-auto"]')).toBeNull();
  });

  it('shows every seller role as a capability card and highlights the member’s own role', async () => {
    await renderScreen(<TeamView />);
    const own = await screen.findByText('Your role');
    expect(own.closest('section')).toHaveAttribute('aria-current', 'true');
    expect(screen.getAllByRole('region', { name: / role$/ })).toHaveLength(7);
  });

  it('explains paused payouts on the payouts screen', async () => {
    await renderScreen(<PayoutsView />);
    expect(await screen.findAllByText(/paused/i)).not.toHaveLength(0);
  });

  it('shows outstanding provider fees next to commission, Stripe and late-cancel fees', async () => {
    await renderScreen(<EarningsView />, PROVIDER_ORG);
    const totals = await screen.findByLabelText('Earnings totals');
    expect(within(totals).getByText(/Commission/)).toBeInTheDocument();
    expect(within(totals).getByText('Stripe card fees')).toBeInTheDocument();
    expect(within(totals).getByText('Late-cancel fees')).toBeInTheDocument();
    expect(await screen.findByText(/outstanding\./)).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Provider fee history' })).toBeInTheDocument();
  });

  it('says when provider fees are unavailable instead of showing zero', () => {
    renderWithProviders(<ProviderFeesCard fees={{ available: false, outstandingMinor: 0, collectedMinor: 0, items: [] }} currency="USD" />);
    expect(screen.getByText(/can’t be shown right now/)).toBeInTheDocument();
  });

  it('validates a custom cancellation policy before saving', async () => {
    const user = userEvent.setup();
    await renderScreen(<PolicyEditor />, PROVIDER_ORG);
    await user.click(await screen.findByRole('radio', { name: /Custom/ }));
    const free = screen.getByLabelText('Free cancellation up to (hours before)');
    await user.clear(free);
    await user.type(free, '24');
    const partial = screen.queryByLabelText('Partial refund until (hours before)') ?? (await (async () => {
      await user.click(screen.getByLabelText('Give a partial refund after the free window'));
      return screen.getByLabelText('Partial refund until (hours before)');
    })());
    await user.clear(partial);
    await user.type(partial, '48');
    await user.click(screen.getByRole('button', { name: 'Save policy' }));
    expect(await screen.findByText('Must be less than the free-cancellation window')).toBeInTheDocument();
  });
});
