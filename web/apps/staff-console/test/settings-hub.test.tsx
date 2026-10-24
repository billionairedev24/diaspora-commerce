import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { SettingsHub } from '@/components/settings/SettingsHub';
import { ConsoleShell } from '@/components/shell/ConsoleShell';

import { asRoles } from './mock-api';
import { renderWithProviders } from './render';
import { nav } from './setup';

const railLinks = async () => {
  const rail = await screen.findByRole('navigation', { name: 'Settings sections' });
  return within(rail).getAllByRole('link');
};

describe('Settings', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/settings');
  });

  it('lists every section a super admin can open, grouped, with the first one open', async () => {
    renderWithProviders(
      <ConsoleShell>
        <SettingsHub />
      </ConsoleShell>
    );
    const labels = (await railLinks()).map((a) => a.textContent);
    expect(labels).toEqual(['General', 'Markets & locations', 'Notifications', 'Sign-in & integrations', 'Identity & access', 'Payments & payouts', 'Fees & commissions', 'Sales tax', 'Exchange rates', 'Plans & billing']);
    expect(await screen.findByRole('heading', { name: 'General' })).toBeInTheDocument();
  });

  it('opens the section named in the address and marks it current', async () => {
    nav.set('/settings?section=payments');
    renderWithProviders(
      <ConsoleShell>
        <SettingsHub />
      </ConsoleShell>
    );
    const links = await railLinks();
    const current = links.find((a) => a.getAttribute('aria-current') === 'page');
    expect(current?.textContent).toBe('Payments & payouts');
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
