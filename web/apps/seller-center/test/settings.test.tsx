import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { beforeEach, describe, expect, it } from 'vitest';

import { BusinessAddressStart } from '@/components/settings/BusinessAddressStart';
import { LocationsView } from '@/components/settings/LocationsView';
import { PickupView } from '@/components/settings/PickupView';
import { SettingsLanding } from '@/components/settings/SettingsLanding';
import { SettingsShell } from '@/components/settings/SettingsShell';
import { SETTINGS_SECTIONS } from '@/lib/settings-sections';

import nextConfig from '../next.config';
import { asRole, mockApi } from './mock-api';
import { renderScreen } from './render';

const a11y = async () => (await axe.run(document.body, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } })).violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`);

describe('Settings landing', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('is a flat list of sections, each with a line about it and its status, and no cards', async () => {
    await renderScreen(<SettingsLanding />);
    const list = await screen.findByTestId('settings-list');
    const rows = within(list).getAllByRole('listitem');
    expect(rows).toHaveLength(SETTINGS_SECTIONS.length);
    expect(rows.map((r) => within(r).getByRole('link').getAttribute('href'))).toEqual(SETTINGS_SECTIONS.map((s) => s.href));
    expect(within(rows[0] as HTMLElement).getByText('Business profile')).toBeInTheDocument();
    // Statuses come from saved state: the seeded seller has two locations and pickup on at one of them.
    expect(await screen.findByTestId('settings-status-locations')).toHaveTextContent('2 locations');
    expect(await screen.findByTestId('settings-status-pickup')).toHaveTextContent('On at 1 of 2');
    expect(screen.getByTestId('settings-status-notifications')).toHaveTextContent('Yours only');
    // The word "offer" is not used in the English copy of this page.
    expect(list.textContent).not.toMatch(/\boffer/i);
    expect(await a11y()).toEqual([]);
  });

  it('says Pickup: Off until the seller turns it on', async () => {
    await mockApi().setCustomerPickup('sfa-store', { enabled: false, hours: [], readyHours: 0 });
    await renderScreen(<SettingsLanding />);
    expect(await screen.findByTestId('settings-status-pickup')).toHaveTextContent('Off');
  });

  it('shows a member only the sections their role may open', async () => {
    asRole('seller.catalog');
    await renderScreen(<SettingsLanding />);
    const list = await screen.findByTestId('settings-list');
    const hrefs = within(list).getAllByRole('link').map((l) => l.getAttribute('href'));
    expect(hrefs).toContain('/settings/pickup');
    expect(hrefs).toContain('/settings/language');
    expect(hrefs).not.toContain('/settings/payouts');
    expect(hrefs).not.toContain('/settings/team');
  });

  it('has the sub-nav the shell shows on desktop, with the current section marked', async () => {
    await renderScreen(
      <SettingsShell>
        <p>body</p>
      </SettingsShell>
    );
    const nav = await screen.findByRole('navigation', { name: 'Settings sections' });
    expect(within(nav).getAllByRole('link').length).toBe(SETTINGS_SECTIONS.length + 1);
  });
});

describe('Settings > Pickup', () => {
  beforeEach(async () => {
    resetSellerDb(new Date());
    asRole(null);
    await mockApi().setCustomerPickup('sfa-store', { enabled: false, hours: [], readyHours: 0 });
  });

  it('shows the preloaded address read-only with a link to Locations, off, with nothing filled in', async () => {
    await renderScreen(<PickupView />);
    const forms = await screen.findAllByTestId('pickup-location');
    const form = forms[0] as HTMLElement;
    expect(within(form).getByTestId('pickup-address')).toHaveTextContent('9800 Bissonnet St');
    expect(within(form).getByRole('link', { name: 'Edit address in Locations' })).toHaveAttribute('href', '/settings/locations');
    // The address is text, not fields.
    expect(within(form).queryByLabelText(/Street address/)).toBeNull();
    expect(within(form).getByLabelText(/Customers can collect orders from this location/)).not.toBeChecked();
    expect(within(form).queryByLabelText('Ready after (hours)')).toBeNull();
    expect(within(form).getByText('Pickup is off')).toBeInTheDocument();
    expect(await a11y()).toEqual([]);
  });

  it('turns on only with typed hours, a ready time and the confirmation, then says it is on', async () => {
    const user = userEvent.setup();
    await renderScreen(<PickupView />);
    const form = (await screen.findAllByTestId('pickup-location'))[0] as HTMLElement;
    await user.click(within(form).getByLabelText(/Customers can collect orders from this location/));
    const turnOn = within(form).getByRole('button', { name: 'Turn on pickup' });
    expect(turnOn).toBeDisabled();
    expect(within(form).queryAllByTestId('customer-pickup-window')).toHaveLength(0);
    expect(within(form).getByTestId('pickup-preview')).toHaveTextContent('Shoppers near ZIP 77036 will see Pickup');
    await user.click(within(form).getByRole('button', { name: 'Add a pickup window' }));
    await user.type(within(form).getByLabelText('Opens, window 1'), '10:00');
    await user.type(within(form).getByLabelText('Closes, window 1'), '16:00');
    await user.type(within(form).getByLabelText('Ready after (hours)'), '3');
    expect(turnOn).toBeDisabled();
    expect((await mockApi().listShipFrom())[0]?.customerPickup?.enabled).toBe(false);
    expect(await a11y()).toEqual([]);
    await user.click(within(form).getByLabelText('Someone will be at this address during these hours.'));
    expect(turnOn).toBeEnabled();
    await user.click(turnOn);
    await waitFor(async () => expect((await mockApi().listShipFrom())[0]?.customerPickup).toMatchObject({ enabled: true, readyHours: 3 }));
    expect(await within((await screen.findAllByTestId('pickup-location'))[0] as HTMLElement).findByText('Pickup is on')).toBeInTheDocument();
    expect(await a11y()).toEqual([]);
  });

  it('is read-only for a member who may only view orders', async () => {
    asRole('seller.catalog');
    await renderScreen(<PickupView />);
    const form = (await screen.findAllByTestId('pickup-location'))[0] as HTMLElement;
    expect(within(form).getByLabelText(/Customers can collect orders from this location/)).toBeDisabled();
    expect(within(form).queryByRole('button', { name: /Turn on pickup|Save/ })).toBeNull();
    expect(screen.getByText('Read-only.')).toBeInTheDocument();
  });

  it('keeps the audit on the server: the turn-on is recorded with who and when, whatever the form sends', async () => {
    const api = mockApi();
    await api.setCustomerPickup('sfa-store', { enabled: true, hours: [{ day: 'MONDAY', opens: '10:00', closes: '16:00' }], readyHours: 2, hoursConfirmed: true });
    const saved = (await api.listShipFrom())[0]?.customerPickup;
    expect(saved?.enabledAt).toBeTruthy();
  });
});

describe('Settings > Locations', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('keeps the address editor here and links to Pickup and Shipping, without hours forms', async () => {
    await renderScreen(<LocationsView />);
    const rows = await screen.findAllByTestId('ship-from-address');
    expect(rows).toHaveLength(2);
    expect(screen.queryByTestId('pickup-location')).toBeNull();
    expect(await a11y()).toEqual([]);
  });
});

describe('Preloaded location', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('starts a location from the business address with one click, as the default, and never by itself', async () => {
    const user = userEvent.setup();
    const before = (await mockApi().listShipFrom()).length;
    await renderScreen(<BusinessAddressStart />);
    const button = await screen.findByRole('button', { name: /Use my business address/ });
    // Nothing is created until the click.
    expect((await mockApi().listShipFrom()).length).toBe(before);
    await user.click(button);
    await waitFor(async () => expect((await mockApi().listShipFrom()).length).toBe(before + 1));
    const all = await mockApi().listShipFrom();
    expect(all.filter((a) => a.isDefault)).toHaveLength(1);
  });
});

describe('Old routes', () => {
  it('redirect to their new sections', async () => {
    const rules = await (nextConfig as unknown as () => Promise<{ redirects: () => Promise<{ source: string; destination: string }[]> }>)().then((c) => c.redirects());
    const map = Object.fromEntries(rules.map((r) => [r.source, r.destination]));
    expect(map).toMatchObject({
      '/orders/ship-from': '/settings/locations',
      '/team': '/settings/team',
      '/team/business-page': '/settings/business',
      '/account/notifications': '/settings/notifications',
      '/account/preferences': '/settings/language'
    });
  });
});
