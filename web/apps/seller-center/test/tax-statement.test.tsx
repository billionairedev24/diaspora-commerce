import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { TaxStatementView } from '@/components/money/TaxStatementView';

import { asRole } from './mock-api';
import { renderScreen } from './render';

describe('Sales tax statement', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('shows the tax collected per state with totals, who remits it, and a CSV link for the month', async () => {
    await renderScreen(<TaxStatementView />);
    const table = await screen.findByRole('table', { name: 'Sales tax statement' });
    const texas = within(table).getByRole('row', { name: /^TX/ });
    expect(within(texas).getByText('$20.63')).toBeInTheDocument();
    expect(within(texas).getByText('-$5.36')).toBeInTheDocument();
    expect(within(texas).getByText('$15.27')).toBeInTheDocument();
    expect(within(within(table).getByRole('row', { name: /^Total/ })).getByText('$24.15')).toBeInTheDocument();
    expect(screen.getByText(/collects and remits this sales tax as marketplace facilitator/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Download CSV' }).getAttribute('href')).toMatch(/^\/api\/bff\/v1\/seller\/tax\/statement\/export\?month=\d{4}-\d{2}$/);
  });

  it('says so when a month had no sales to taxed states', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxStatementView />);
    await screen.findByRole('table', { name: 'Sales tax statement' });
    const select = screen.getByRole('combobox', { name: 'Month' });
    const options = within(select).getAllByRole('option');
    await user.selectOptions(select, options[10]?.getAttribute('value') ?? '');
    expect(await screen.findByText('No taxable sales were shipped to the United States this month.')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Sales tax statement' })).not.toBeInTheDocument();
  });
});
