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
    expect(labels).toEqual(['Published pages', 'General', 'Markets & locations', 'Notifications', 'Sign-in & integrations', 'Identity & access', 'Application review: SLAs and escalation']);
    expect(within(await screen.findByRole('region', { name: 'Money' })).getAllByRole('link')).toHaveLength(11);
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
    expect(await screen.findByRole('heading', { name: 'Our own rules works out the tax now' })).toBeInTheDocument();
    expect(await screen.findByText(/Can’t be chosen yet: Avalara isn’t set up yet/)).toBeInTheDocument();
  });

  const hub = () =>
    renderWithProviders(
      <ConsoleShell>
        <SettingsHub />
      </ConsoleShell>
    );
  const cardLabels = (region: HTMLElement) => within(region).getAllByRole('link').map((a) => a.querySelector('span span')?.textContent);

  it('hides the sections a role has no access to, and a group with nothing left', async () => {
    asRoles('platform.finance');
    hub();
    // Finance runs the money sections; none of the other platform ones (pages, markets, notifications, sign-in, identity) are theirs.
    const money = await screen.findByRole('region', { name: 'Money' });
    expect(cardLabels(money)).toEqual([
      'Payments & payouts', 'Credit & gift cards', 'Membership', 'Referral program', 'Fees & commissions', 'Flat delivery fee', 'Delivery fees', 'Run economics', 'Sales tax', 'Exchange rates', 'Plans & billing'
    ]);
    // Finance can view Applications, so the review SLAs are theirs to read; the other platform sections are not.
    expect(cardLabels(await screen.findByRole('region', { name: 'Platform' }))).toEqual(['Application review: SLAs and escalation']);
  });

  it('shows a developer the platform sections they can reach, without identity, delivery fees or run economics', async () => {
    asRoles('platform.developer');
    hub();
    expect(cardLabels(await screen.findByRole('region', { name: 'Platform' }))).toEqual(['Published pages', 'General', 'Markets & locations', 'Notifications', 'Sign-in & integrations', 'Application review: SLAs and escalation']);
    const money = cardLabels(await screen.findByRole('region', { name: 'Money' }));
    expect(money).not.toContain('Delivery fees');
    expect(money).not.toContain('Run economics');
    expect(money).toContain('Payments & payouts');
  });

  it('an auditor sees only the sections their view-only access reaches', async () => {
    asRoles('platform.auditor');
    hub();
    expect(cardLabels(await screen.findByRole('region', { name: 'Platform' }))).toEqual(['Markets & locations', 'Sign-in & integrations', 'Application review: SLAs and escalation']);
  });

  it('does not open a section the role cannot see, even from the address', async () => {
    asRoles('platform.finance');
    nav.set('/settings?section=identity');
    hub();
    expect(await screen.findByRole('region', { name: 'Money' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Identity & access' })).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Breadcrumb' })).not.toBeInTheDocument();
  });

  it('says a section is read only when the role can view but not change it', async () => {
    asRoles('platform.auditor');
    nav.set('/settings?section=payments');
    hub();
    expect(await screen.findByRole('heading', { name: 'Payments & payouts' })).toBeInTheDocument();
    expect(await screen.findByText('Read only')).toBeInTheDocument();
    expect(screen.getByText('Auditor (read-only) can view these settings but not change them.')).toBeInTheDocument();
    // the switcher lists only the sections they can open
    const options = within(screen.getByLabelText('Go to another setting')).getAllByRole('option').map((o) => o.textContent);
    expect(options).toContain('Payments & payouts');
    expect(options).not.toContain('Identity & access');
  });

  it('shows no read-only notice to a role that can change the section', async () => {
    asRoles('platform.finance');
    nav.set('/settings?section=fees');
    hub();
    expect(await screen.findByRole('heading', { name: 'Fees & commissions' })).toBeInTheDocument();
    expect(screen.queryByText('Read only')).not.toBeInTheDocument();
    asRoles('platform.developer');
    nav.set('/settings?section=signin');
    hub();
    expect(await screen.findByRole('heading', { name: 'Sign-in & integrations' })).toBeInTheDocument();
    expect(screen.queryByText('Read only')).not.toBeInTheDocument();
  });
});
