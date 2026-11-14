import { type PickupPrompt } from '@afrotransact/api-client';
import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { DashboardView } from '@/components/dashboard/DashboardView';
import { OrdersView } from '@/components/orders/OrdersView';
import { blankWindow, cardVisible, checkTurnOn, nudgeVisible, previewZip, turnOnRequest } from '@/lib/pickup-prompt';

import { asRole, mockApi } from './mock-api';
import { renderScreen } from './render';

const NOW = new Date('2026-11-12T12:00:00Z');
const ADDRESS = { addressId: 'sfa-store', name: 'Ade’s Kitchen', line1: '9800 Bissonnet St', line2: '', city: 'Houston', state: 'TX', postalCode: '77036', phone: '+17135550142' };
const prompt = (over: Partial<PickupPrompt> = {}): PickupPrompt => ({
  eligible: true,
  hasPickupPlace: false,
  show: true,
  nudge: true,
  state: 'NONE',
  snoozedUntil: null,
  address: ADDRESS,
  ...over
});
const MSG = { ready: 'ready', confirm: 'confirm' };
const monday = { day: 'MONDAY' as const, opens: '10:00', closes: '16:00' };

describe('pickup card rules', () => {
  it('is for an approved seller with an address and no pickup place', () => {
    expect(cardVisible(prompt(), NOW)).toBe(true);
    expect(cardVisible(undefined, NOW)).toBe(false);
    expect(cardVisible(prompt({ eligible: false, show: false, address: null }), NOW)).toBe(false);
    expect(cardVisible(prompt({ hasPickupPlace: true, show: false, nudge: false, address: null }), NOW)).toBe(false);
    expect(cardVisible(prompt({ address: null }), NOW)).toBe(false);
  });

  it('stays away after "Don’t ask again", and after "Not now" until the snooze ends', () => {
    expect(cardVisible(prompt({ state: 'NEVER', show: false }), NOW)).toBe(false);
    // Even if a server bug said show, never means never.
    expect(cardVisible(prompt({ state: 'NEVER', show: true }), NOW)).toBe(false);
    const until = '2026-12-12T12:00:00Z';
    expect(cardVisible(prompt({ state: 'SNOOZED', snoozedUntil: until, show: false }), NOW)).toBe(false);
    expect(cardVisible(prompt({ state: 'SNOOZED', snoozedUntil: until, show: true }), NOW)).toBe(false);
    expect(cardVisible(prompt({ state: 'SNOOZED', snoozedUntil: until, show: true }), new Date('2026-12-12T12:00:00Z'))).toBe(true);
    expect(cardVisible(prompt({ state: 'SNOOZED', snoozedUntil: until, show: false }), new Date('2027-01-01T00:00:00Z'))).toBe(false);
    expect(cardVisible(prompt({ state: 'SNOOZED', snoozedUntil: null, show: true }), NOW)).toBe(false);
  });

  it('shows the small nudge only while no pickup place exists, whatever was dismissed', () => {
    expect(nudgeVisible(prompt({ state: 'NEVER', show: false }))).toBe(true);
    expect(nudgeVisible(prompt({ hasPickupPlace: true, nudge: false }))).toBe(false);
    expect(nudgeVisible(prompt({ eligible: false, nudge: false }))).toBe(false);
    expect(nudgeVisible(undefined)).toBe(false);
  });

  it('needs typed hours, a ready time and the explicit confirmation before pickup can be turned on', () => {
    expect(blankWindow()).toEqual({ day: 'MONDAY', opens: '', closes: '' });
    expect(checkTurnOn([], '2', true, MSG).errors.hours).toBeDefined();
    expect(checkTurnOn([blankWindow()], '2', true, MSG).ok).toBe(false);
    expect(checkTurnOn([monday], '', true, MSG).errors.readyHours).toBe('ready');
    expect(checkTurnOn([monday], '169', true, MSG).errors.readyHours).toBe('ready');
    expect(checkTurnOn([monday], '2', false, MSG).errors.confirmed).toBe('confirm');
    expect(checkTurnOn([monday, { ...monday, opens: '15:00', closes: '18:00' }], '2', true, MSG).ok).toBe(false);
    expect(checkTurnOn([monday], '0', true, MSG)).toEqual({ ok: true, errors: {} });
  });

  it('builds the turn-on request only when everything is confirmed, and always with the confirmation flag', () => {
    expect(turnOnRequest([], '2', true)).toBeNull();
    expect(turnOnRequest([monday], '2', false)).toBeNull();
    expect(turnOnRequest([monday], '', true)).toBeNull();
    expect(turnOnRequest([monday], '3', true)).toEqual({ enabled: true, hours: [monday], readyHours: 3, hoursConfirmed: true });
  });

  it('previews the ZIP shoppers are matched on', () => {
    expect(previewZip({ postalCode: '77002-1234' })).toBe('77002');
    expect(previewZip({ postalCode: ' 77002 ' })).toBe('77002');
    expect(previewZip({ postalCode: '7700' })).toBeNull();
    expect(previewZip(null)).toBeNull();
  });
});

describe('pickup prompt in Seller Center', () => {
  beforeEach(async () => {
    resetSellerDb(new Date());
    asRole(null);
    // The seeded organisation already collects at its Warehouse; start from "no pickup place" like a new seller.
    await mockApi().setCustomerPickup('sfa-store', { enabled: false, hours: [], readyHours: 0 });
  });

  it('points to Settings > Pickup and never switches pickup on by itself', async () => {
    const user = userEvent.setup();
    await renderScreen(<DashboardView />);
    const card = await screen.findByTestId('pickup-prompt');
    expect(within(card).getByText(/Only switch this on if someone will be there/)).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'Set up pickup' })).toHaveAttribute('href', '/settings/pickup');
    // No form on Home: no hours, no ready time, no turn-on button.
    expect(within(card).queryByRole('button', { name: 'Turn on pickup' })).toBeNull();
    expect(within(card).queryByLabelText('Ready after (hours)')).toBeNull();
    await user.click(within(card).getByRole('button', { name: 'Not now' }));
    await waitFor(() => expect(screen.queryByTestId('pickup-prompt')).toBeNull());
    expect((await mockApi().listShipFrom())[0]?.customerPickup?.enabled).toBe(false);
  });

  it('remembers "Not now" and "Don’t ask again" on the server', async () => {
    const user = userEvent.setup();
    await renderScreen(<DashboardView />);
    await user.click(within(await screen.findByTestId('pickup-prompt')).getByRole('button', { name: 'Not now' }));
    await waitFor(() => expect(screen.queryByTestId('pickup-prompt')).toBeNull());
    expect(await mockApi().getPickupPrompt()).toMatchObject({ state: 'SNOOZED', show: false, nudge: true });
    await mockApi().answerPickupPrompt('DONT_ASK_AGAIN');
    expect(await mockApi().getPickupPrompt()).toMatchObject({ state: 'NEVER', show: false, nudge: true });
  });

  it('shows a small nudge on Orders, linking to Settings > Pickup, even after "Don’t ask again", but not the card', async () => {
    await mockApi().answerPickupPrompt('DONT_ASK_AGAIN');
    await renderScreen(<OrdersView />);
    expect(await screen.findByText(/Customers can’t collect orders from you yet/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Set up pickup' })).toHaveAttribute('href', '/settings/pickup');
    expect(screen.queryByTestId('pickup-prompt')).toBeNull();
    // Orders is for orders: no ship-from or pickup configuration is reachable from its header.
    expect(screen.queryByRole('link', { name: /Ship-from/ })).toBeNull();
  });

  it('is refused by the server without the confirmation, and never shows when pickup already exists', async () => {
    const api = mockApi();
    await expect(api.setCustomerPickup('sfa-store', { enabled: true, hours: [monday], readyHours: 2 })).rejects.toMatchObject({ code: 'PICKUP_HOURS_NOT_CONFIRMED' });
    expect((await api.listShipFrom())[0]?.customerPickup?.enabled).toBe(false);
    await api.setCustomerPickup('sfa-store', { enabled: true, hours: [monday], readyHours: 2, hoursConfirmed: true });
    expect(await api.getPickupPrompt()).toMatchObject({ hasPickupPlace: true, show: false, nudge: false, address: null });
    await renderScreen(<DashboardView />);
    await screen.findAllByText(/Home|Sales|Orders/);
    expect(screen.queryByTestId('pickup-prompt')).toBeNull();
  });
});
