import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { SettingsHub } from '@/components/settings/SettingsHub';
import { ConsoleShell } from '@/components/shell/ConsoleShell';

import { asRoles } from './mock-api';
import { renderWithProviders } from './render';
import { nav } from './setup';


describe('Settings', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/settings');
  });

  it('is a hub of grouped cards with no second sidebar', async () => {
    renderWithProviders(
      <ConsoleShell>
        <SettingsHub />
      </ConsoleShell>
    );
    const platform = await screen.findByRole('region', { name: 'Platform' });
    const labels = within(platform).getAllByRole('link').map((a) => a.querySelector('span span')?.textContent);
    expect(labels).toEqual(['General', 'Markets & locations', 'Notifications', 'Sign-in & integrations', 'Identity & access']);
    expect(within(await screen.findByRole('region', { name: 'Money' })).getAllByRole('link')).toHaveLength(8);
    expect(screen.queryByRole('navigation', { name: 'Settings sections' })).not.toBeInTheDocument();
  });

  it('opens the section named in the address under a breadcrumb', async () => {
    nav.set('/settings?section=payments');
    renderWithProviders(
      <ConsoleShell>
        <SettingsHub />
      </ConsoleShell>
    );
    const crumbs = await screen.findByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumbs).getByRole('link', { name: 'Settings' })).toHaveAttribute('href', '/settings');
    expect(within(crumbs).getByText('Payments & payouts')).toHaveAttribute('aria-current', 'page');
    expect(await screen.findByRole('heading', { name: 'Payments & payouts' })).toBeInTheDocument();
    expect(await screen.findByText('Hold before a seller can be paid')).toBeInTheDocument();
  });

  it('shows the tax method with the Stripe Tax state under Sales tax', async () => {
    nav.set('/settings?section=tax');
    renderWithProviders(
      <ConsoleShell>
        <SettingsHub />
      </ConsoleShell>
    );
    expect(await screen.findByText('Our own rules')).toBeInTheDocument();
    expect(await screen.findByText('Not set up yet')).toBeInTheDocument();
  });
});
