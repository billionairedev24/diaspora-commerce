import { PROVIDER_ORG, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { FeesView } from '@/components/money/FeesView';
import { EarningsView } from '@/components/provider/EarningsView';
import { RequestsView } from '@/components/provider/RequestsView';

import { asRole } from './mock-api';
import { renderScreen } from './render';

describe('Fees & commission page (read-only)', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('shows the commission per category with the seller’s own override, the fees and the upcoming change', async () => {
    await renderScreen(<FeesView />);
    const table = await screen.findByRole('table', { name: 'Commission by category' });
    const groceries = within(table).getByRole('row', { name: /^Select Groceries/ });
    expect(within(groceries).getByText('7.00%')).toBeInTheDocument();
    expect(within(groceries).getByText('$7.00')).toBeInTheDocument();
    expect(within(groceries).getByText('Negotiated rate until Dec 31, 2026')).toBeInTheDocument();
    expect(within(within(table).getByRole('row', { name: /^Select Beauty/ })).getByText('Your plan: Seller Growth')).toBeInTheDocument();
    expect(screen.getByTestId('fees-plan')).toHaveTextContent('Your plan: Seller Growth.');
    expect(screen.getByRole('list', { name: 'Your negotiated rates' })).toHaveTextContent('Groceries');
    const fashion = within(table).getByRole('row', { name: /^Select Fashion/ });
    expect(within(fashion).getByText('11.50%')).toBeInTheDocument();
    expect(within(fashion).getByText('13.00%')).toBeInTheDocument();
    expect(screen.getAllByText('$3.25 / unit')).not.toHaveLength(0);
    expect(screen.getAllByText('2.90% + $0.30')).not.toHaveLength(0);
    expect(screen.getByText(/Fees change on/).closest('[role="status"]')).toHaveTextContent('Fashion commission: 11.50% → 13.00%');
    expect(screen.getByText('$3.50 / unit')).toBeInTheDocument();
    // Read-only: nothing to edit.
    expect(screen.queryByRole('button', { name: /save|edit|request/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('shows the provider’s services rate', async () => {
    await renderScreen(<FeesView />, PROVIDER_ORG);
    const table = await screen.findByRole('table', { name: 'Commission by category' });
    const services = within(table).getByRole('row', { name: /^Select Services/ });
    expect(within(services).getByText('9.00%')).toBeInTheDocument();
    expect(within(services).getByText(/^Negotiated rate until/)).toBeInTheDocument();
    const connection = screen.getByRole('table', { name: 'Connection fees on bookings' });
    const hair = within(connection).getByRole('row', { name: /Hair & beauty/ });
    expect(within(hair).getByText('7.00%')).toBeInTheDocument();
    expect(within(hair).getByText(/^Negotiated rate until/)).toBeInTheDocument();
    const other = within(connection).getByRole('row', { name: /Other services/ });
    expect(within(other).getByText('8.00%')).toBeInTheDocument();
    expect(within(other).getByText('$8.00')).toBeInTheDocument();
    expect(within(other).getByText('Your plan: Provider Studio')).toBeInTheDocument();
  });

  it('shows what the provider keeps before accepting a request, and the connection fee on earnings', async () => {
    const requests = await renderScreen(<RequestsView />, PROVIDER_ORG);
    const homes = await screen.findAllByTestId('take-home');
    expect(homes[0]).toHaveTextContent(/You keep \$[\d,.]+ after the (8%|7%) connection fee/);
    requests.unmount();
    await renderScreen(<EarningsView />, PROVIDER_ORG);
    expect(await screen.findByText('Connection fee (8%)')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Connection fees on bookings' })).toBeInTheDocument();
    expect(screen.getAllByRole('columnheader', { name: /Connection fee/ }).length).toBeGreaterThan(0);
  });
});
