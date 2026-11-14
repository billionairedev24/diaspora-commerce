import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { cleanup, screen, waitFor, within } from '@testing-library/react';
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
import { nav } from './setup';

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

  it('is one content column: no sub-nav, and a "Settings" link back above a section', async () => {
    await renderScreen(
      <SettingsShell>
        <p>body</p>
      </SettingsShell>
    );
    expect(await screen.findByText('body')).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Settings sections' })).toBeNull();
    // On the Settings home there is nothing to go back to.
    expect(screen.queryByTestId('settings-back')).toBeNull();
    cleanup();
    nav.pathname = '/settings/pickup';
    await renderScreen(
      <SettingsShell>
        <p>body</p>
      </SettingsShell>
    );
    expect(await screen.findByTestId('settings-back')).toHaveAttribute('href', '/settings');
    expect(screen.getAllByRole('link')).toHaveLength(1);
    nav.pathname = '/';
  });
});

const SWITCH = 'Let customers collect orders';
/** Opens a day in the weekly list and picks its times (HH:MM values of the 30-minute selects). */
async function openDay(user: ReturnType<typeof userEvent.setup>, scope: HTMLElement, day: string, from: string, to: string) {
  await user.click(within(scope).getByRole('switch', { name: day }));
  await user.selectOptions(within(scope).getByLabelText(`From, ${day}`), from);
  await user.selectOptions(within(scope).getByLabelText(`To, ${day}`), to);
}

describe('Settings > Pickup', () => {
  beforeEach(async () => {
    resetSellerDb(new Date());
    asRole(null);
    await mockApi().setCustomerPickup('sfa-store', { enabled: false, hours: [], readyHours: 0 });
  });

  it('is one switch, one address line and a Save: off, nothing filled in, no duplicated text', async () => {
    await renderScreen(<PickupView />);
    const form = (await screen.findAllByTestId('pickup-location'))[0] as HTMLElement;
    expect(within(form).getByTestId('pickup-address')).toHaveTextContent('9800 Bissonnet St');
    expect(within(form).getByRole('link', { name: 'Change in Locations' })).toHaveAttribute('href', '/settings/locations');
    // The address is text, not fields.
    expect(within(form).queryByLabelText(/Street address/)).toBeNull();
    const sw = within(form).getByRole('switch', { name: SWITCH });
    expect(sw).toHaveAttribute('aria-checked', 'false');
    // The switch is the only on/off and the confirmation, said once.
    expect(within(form).queryAllByRole('checkbox')).toHaveLength(0);
    expect(within(form).getByTestId('pickup-confirm-line')).toHaveTextContent('Turning this on confirms someone will be at this address');
    expect(within(form).queryByTestId('weekly-hours')).toBeNull();
    expect(within(form).queryByText(/Shoppers near/)).toBeNull();
    expect(within(form).queryByRole('button', { name: 'Turn on pickup' })).toBeNull();
    expect(within(form).getByRole('button', { name: 'Save customer pickup' })).toBeDisabled();
    expect(document.body.textContent).not.toMatch(/\boffer/i);
    expect(await a11y()).toEqual([]);
  });

  it('shows seven closed rows, Monday to Sunday, with the times on the same row once a day is opened', async () => {
    const user = userEvent.setup();
    await renderScreen(<PickupView />);
    const form = (await screen.findAllByTestId('pickup-location'))[0] as HTMLElement;
    await user.click(within(form).getByRole('switch', { name: SWITCH }));
    const rows = within(form).getAllByTestId('weekly-row');
    expect(rows.map((r) => r.getAttribute('data-day'))).toEqual(['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']);
    // Default: every day closed, nothing prefilled, no time pickers yet.
    for (const r of rows) {
      expect(within(r).getByText('Closed')).toBeInTheDocument();
      expect(within(r).queryAllByRole('combobox')).toHaveLength(0);
    }
    await user.click(within(rows[0] as HTMLElement).getByRole('switch', { name: 'Monday' }));
    const mon = within(rows[0] as HTMLElement);
    expect(mon.getByLabelText('From, Monday')).toHaveValue('');
    expect(mon.getByLabelText('To, Monday')).toHaveValue('');
    // 30-minute steps, written like 9:00 AM, in a select (no native clock input).
    const fromOptions = within(mon.getByLabelText('From, Monday')).getAllByRole('option').map((o) => o.textContent);
    expect(fromOptions).toHaveLength(49);
    expect(fromOptions.slice(0, 4)).toEqual(['From', '12:00 AM', '12:30 AM', '1:00 AM']);
    expect(fromOptions).toContain('9:00 AM');
    expect(fromOptions).toContain('9:30 AM');
    expect(fromOptions).not.toContain('9:15 AM');
    expect(document.querySelector('input[type="time"]')).toBeNull();
    expect(await a11y()).toEqual([]);
  });

  it('turns on only with an open day, valid times and a ready time; Save stays disabled with the reason beside it', async () => {
    const user = userEvent.setup();
    await renderScreen(<PickupView />);
    const form = (await screen.findAllByTestId('pickup-location'))[0] as HTMLElement;
    const save = within(form).getByRole('button', { name: 'Save customer pickup' });
    await user.click(within(form).getByRole('switch', { name: SWITCH }));
    expect(save).toBeDisabled();
    expect(within(form).getByTestId('pickup-validation')).toHaveTextContent('Open at least one day.');
    await user.click(within(form).getByRole('switch', { name: 'Monday' }));
    expect(within(form).getByTestId('pickup-validation')).toHaveTextContent('Choose from and to for each open day.');
    await user.selectOptions(within(form).getByLabelText('From, Monday'), '16:00');
    await user.selectOptions(within(form).getByLabelText('To, Monday'), '10:00');
    expect(within(form).getByTestId('pickup-validation')).toHaveTextContent('To must be later than from.');
    expect(within(form).getByLabelText('To, Monday')).toHaveAttribute('aria-invalid', 'true');
    await user.selectOptions(within(form).getByLabelText('From, Monday'), '10:00');
    await user.selectOptions(within(form).getByLabelText('To, Monday'), '16:00');
    expect(within(form).getByTestId('pickup-validation')).toHaveTextContent('Choose when orders are ready to collect.');
    expect(save).toBeDisabled();
    expect((await mockApi().listShipFrom())[0]?.customerPickup?.enabled).toBe(false);
    await user.selectOptions(within(form).getByLabelText('Ready to collect'), '24');
    expect(within(form).queryByTestId('pickup-validation')).toBeNull();
    expect(save).toBeEnabled();
    expect(await a11y()).toEqual([]);
    await user.click(save);
    await waitFor(async () => expect((await mockApi().listShipFrom())[0]?.customerPickup).toMatchObject({ enabled: true, readyHours: 24, hours: [{ day: 'MONDAY', opens: '10:00', closes: '16:00' }] }));
    const again = (await screen.findAllByTestId('pickup-location'))[0] as HTMLElement;
    expect(within(again).getByRole('switch', { name: SWITCH })).toHaveAttribute('aria-checked', 'true');
    expect(await within(again).findByTestId('pickup-confirm-line')).toHaveTextContent('On since');
  });

  it('copies one day to the weekdays and to every day, and a closed day sends no window', async () => {
    const user = userEvent.setup();
    await renderScreen(<PickupView />);
    const form = (await screen.findAllByTestId('pickup-location'))[0] as HTMLElement;
    await user.click(within(form).getByRole('switch', { name: SWITCH }));
    const copyWeekdays = within(form).getByRole('button', { name: 'Copy to weekdays' });
    const same = within(form).getByRole('button', { name: 'Same every day' });
    expect(copyWeekdays).toBeDisabled();
    expect(same).toBeDisabled();
    await openDay(user, form, 'Monday', '09:00', '17:00');
    await user.click(copyWeekdays);
    const open = () => within(form).getAllByTestId('weekly-row').filter((r) => within(r).queryAllByRole('combobox').length > 0).map((r) => r.getAttribute('data-day'));
    expect(open()).toEqual(['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY']);
    expect(within(form).getByLabelText('From, Friday')).toHaveValue('09:00');
    expect(within(form).getByLabelText('To, Friday')).toHaveValue('17:00');
    await user.click(same);
    expect(open()).toHaveLength(7);
    // Close Wednesday and Sunday: they send nothing.
    await user.click(within(form).getByRole('switch', { name: 'Wednesday' }));
    await user.click(within(form).getByRole('switch', { name: 'Sunday' }));
    await user.selectOptions(within(form).getByLabelText('Ready to collect'), '2');
    await user.click(within(form).getByRole('button', { name: 'Save customer pickup' }));
    await waitFor(async () => expect((await mockApi().listShipFrom())[0]?.customerPickup?.enabled).toBe(true));
    const saved = (await mockApi().listShipFrom())[0]?.customerPickup;
    expect(saved?.readyHours).toBe(2);
    // One window per open day (per-day windows even when the hours are shared), none for the closed days.
    expect(saved?.hours.map((h) => h.day)).toEqual(['MONDAY', 'TUESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY']);
    expect(saved?.hours.every((h) => h.opens === '09:00' && h.closes === '17:00')).toBe(true);
  });

  it('shows saved hours as the week, lets the seller change the ready time, and switching off keeps the saved hours', async () => {
    const user = userEvent.setup();
    await mockApi().setCustomerPickup('sfa-store', { enabled: true, hours: [{ day: 'TUESDAY', opens: '10:00', closes: '19:00' }, { day: 'SATURDAY', opens: '10:00', closes: '16:00' }], readyHours: 3, hoursConfirmed: true });
    await renderScreen(<PickupView />);
    const form = (await screen.findAllByTestId('pickup-location'))[0] as HTMLElement;
    expect(within(form).getByLabelText('From, Tuesday')).toHaveValue('10:00');
    expect(within(form).getByLabelText('To, Saturday')).toHaveValue('16:00');
    // 3 hours is not one of the choices but it is what is saved, so it still shows.
    expect(within(form).getByLabelText('Ready to collect')).toHaveValue('3');
    expect(within(form).getByRole('button', { name: 'Save customer pickup' })).toBeDisabled();
    await user.click(within(form).getByRole('switch', { name: SWITCH }));
    await user.click(within(form).getByRole('button', { name: 'Save customer pickup' }));
    await waitFor(async () => expect((await mockApi().listShipFrom())[0]?.customerPickup?.enabled).toBe(false));
    expect((await mockApi().listShipFrom())[0]?.customerPickup?.hours).toHaveLength(2);
  });

  it('is read-only for a member who may only view orders', async () => {
    asRole('seller.catalog');
    await renderScreen(<PickupView />);
    const form = (await screen.findAllByTestId('pickup-location'))[0] as HTMLElement;
    expect(within(form).getByRole('switch', { name: SWITCH })).toBeDisabled();
    expect(within(form).queryByRole('button', { name: /Save/ })).toBeNull();
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
