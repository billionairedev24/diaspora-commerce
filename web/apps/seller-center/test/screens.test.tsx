import { PROVIDER_ORG, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { DisputesView } from '@/components/cases/DisputesView';
import { ReturnsView } from '@/components/cases/ReturnsView';
import { CouponsView } from '@/components/growth/CouponsView';
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

  it('lists returns and opens one with its items, refund and reason', async () => {
    await renderScreen(<ReturnsView />);
    await userEvent.click(await screen.findByRole('button', { name: 'RMA-5E11E5A1' }));
    const sheet = await screen.findByRole('dialog');
    expect(within(sheet).getByText(/2 × Garri 2kg/)).toBeInTheDocument();
    expect(within(sheet).getByText(/damaged/i)).toBeInTheDocument();
    expect(within(sheet).getByText(/\$17\.98/)).toBeInTheDocument();
  });

  it('lists disputes with the response deadline and never names the buyer', async () => {
    await renderScreen(<DisputesView />);
    expect(await screen.findByText('Waiting for your response', { selector: 'span' })).toBeInTheDocument();
    expect(await screen.findByText('At risk')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'CLM-1001' }));
    const sheet = await screen.findByRole('dialog');
    expect(within(sheet).getByText(/different brand/)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/customerId/);
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

  it('names each order on hold by its order number, never by payments’ id', async () => {
    await renderScreen(<PayoutsView />);
    const holds = await screen.findAllByTestId('hold-order');
    expect(holds.map((h) => h.textContent)).toEqual(['AT-2026-400160', 'AT-2026-400197']);
    expect(screen.queryByText(/ord-400160/)).not.toBeInTheDocument();
  });

  it('shows outstanding provider fees next to the connection fee, Stripe and late-cancel fees', async () => {
    await renderScreen(<EarningsView />, PROVIDER_ORG);
    const totals = await screen.findByLabelText('Earnings totals');
    expect(within(totals).getByText(/Connection fee/)).toBeInTheDocument();
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

  it('renders coupons in TanStack DataTable with create and delete buttons', async () => {
    const user = userEvent.setup();
    await renderScreen(<CouponsView />);
    expect(await screen.findByRole('table', { name: 'Coupons' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New seller coupon' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /Delete coupon ADE10/i })).toBeInTheDocument();

    // Clicking delete opens confirmation dialog
    await user.click(screen.getByRole('button', { name: /Delete coupon ADE10/i }));
    const dialog = await screen.findByRole('dialog', { name: /Delete ADE10/ });
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByText(/Customers will no longer be able to use it/)).toBeInTheDocument();

    // Confirm deletion
    const deleteBtn = within(dialog).getByRole('button', { name: 'Delete' });
    await user.click(deleteBtn);
    await waitFor(() => expect(screen.queryByText('ADE10')).not.toBeInTheDocument());
  });
});
