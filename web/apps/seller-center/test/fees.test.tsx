import { PROVIDER_ORG, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { FeesView } from '@/components/money/FeesView';

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
    const groceries = within(table).getByRole('row', { name: /^Groceries/ });
    expect(within(groceries).getByText('7.00%')).toBeInTheDocument();
    expect(within(groceries).getByText('$7.00')).toBeInTheDocument();
    expect(within(groceries).getByText('Your negotiated rate')).toBeInTheDocument();
    const fashion = within(table).getByRole('row', { name: /^Fashion/ });
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
    const services = within(table).getByRole('row', { name: /^Services/ });
    expect(within(services).getByText('9.00%')).toBeInTheDocument();
    expect(within(services).getByText('Your negotiated rate')).toBeInTheDocument();
  });
});
