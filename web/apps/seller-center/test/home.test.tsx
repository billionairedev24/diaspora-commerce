import { PROVIDER_ORG, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { DashboardView } from '@/components/dashboard/DashboardView';

import { asRole } from './mock-api';
import { renderScreen } from './render';

describe('Home (module summaries)', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
    window.localStorage.clear();
  });

  it('seller home: a section per module with a headline, a period and quick actions that are links', async () => {
    await renderScreen(<DashboardView />);
    const sales = await screen.findByRole('region', { name: 'Sales' });
    expect(within(sales).getByText('Last 30 days')).toBeInTheDocument();
    expect(within(sales).getByRole('img', { name: /Sales per day, last 30 days/ })).toBeInTheDocument();
    expect(within(sales).getByRole('link', { name: 'All orders' })).toHaveAttribute('href', '/orders');
    for (const name of ['Orders', 'Listings', 'Payouts', 'Inventory', 'Reviews', 'Account health', 'Promotions']) {
      expect(screen.getByRole('region', { name })).toBeInTheDocument();
    }
    expect(screen.getByRole('heading', { name: 'We’re here to help' })).toBeInTheDocument();
    // Reports with exports stay below the modules.
    expect(screen.getByRole('heading', { name: 'Reports and exports' })).toBeInTheDocument();
  });

  it('remembers the period per viewer and refetches the figures for it', async () => {
    await renderScreen(<DashboardView />);
    await screen.findByRole('region', { name: 'Sales' });
    await userEvent.click(screen.getByRole('button', { name: '7 days' }));
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Sales' })).getByText('Last 7 days')).toBeInTheDocument());
    expect(window.localStorage.getItem('at.sc.home.period')).toBe('7');
  });

  it('shows only the modules the role may open', async () => {
    asRole('seller.support');
    await renderScreen(<DashboardView />);
    await screen.findByRole('region', { name: 'Orders' });
    expect(screen.queryByRole('region', { name: 'Payouts' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Promotions' })).not.toBeInTheDocument();
  });

  it('provider home: bookings, revenue, availability and payouts', async () => {
    await renderScreen(<DashboardView />, PROVIDER_ORG);
    expect(await screen.findByRole('region', { name: 'Bookings' })).toBeInTheDocument();
    for (const name of ['Revenue', 'Availability', 'Payouts']) expect(screen.getByRole('region', { name })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Listings' })).not.toBeInTheDocument();
  });

  it('never shows an empty number: an empty module offers a prompt with one action', async () => {
    const { findAllByText } = await renderScreen(<DashboardView />);
    await findAllByText('Last 30 days');
    for (const region of screen.getAllByRole('region')) {
      if (region.getAttribute('data-state') === 'empty') expect(within(region).getAllByRole('link')).toHaveLength(1);
    }
  });
});
