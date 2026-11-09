import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { DashboardView } from '@/components/dashboard/DashboardView';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const NOW = new Date('2026-11-03T12:00:00Z');

describe('Staff home (module summaries)', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.operations_manager');
    nav.set('/');
    window.localStorage.clear();
  });

  it('shows a section per module the roles open, with filtered-list quick actions', async () => {
    await renderScreen(<DashboardView />);
    const apps = await screen.findByRole('region', { name: 'Applications' });
    expect(within(apps).getByRole('link', { name: 'Review queue' })).toHaveAttribute('href', '/applications?status=PENDING_REVIEW');
    expect(within(apps).getByRole('img', { name: /^Applications by status\. Total \d+/ })).toBeInTheDocument();
    for (const name of ['Orders & shipments', 'Payments & payouts', 'Trust & safety', 'Support', 'Catalog', 'Platform health']) {
      expect(screen.getByRole('region', { name })).toBeInTheDocument();
    }
    expect(screen.getByRole('heading', { name: 'We’re here to help' })).toBeInTheDocument();
  });

  it('remembers the period and refetches for it', async () => {
    const user = userEvent.setup();
    await renderScreen(<DashboardView />);
    await screen.findByRole('region', { name: 'Orders & shipments' });
    await user.click(screen.getByRole('button', { name: '90 days' }));
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Orders & shipments' })).getByText('Last 90 days')).toBeInTheDocument());
    expect(window.localStorage.getItem('at.st.home.period')).toBe('90');
  });

  it('leaves out the modules the roles cannot open and names people, not ids', async () => {
    asRoles('platform.marketing');
    await renderScreen(<DashboardView />);
    await screen.findByRole('region', { name: 'Platform health' }).catch(() => undefined);
    expect(screen.queryByRole('region', { name: 'Payments & payouts' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Applications' })).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/\b(stf|org|cus)-[0-9a-z]+/);
  });
});
