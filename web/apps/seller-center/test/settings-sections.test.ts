import { type ShipFromAddress } from '@afrotransact/api-client';
import { describe, expect, it } from 'vitest';

import { draftOf, pickupCanSave, pickupProblem, pickupSave } from '@/lib/pickup-settings';
import {
  addressFromBusiness,
  canEditSection,
  documentsStatus,
  locationsStatus,
  payoutsStatus,
  pickupStatus,
  SETTINGS_SECTIONS,
  settingsSectionOf,
  shippingStatus,
  visibleSections
} from '@/lib/settings-sections';
import { closedWeek, type DayRow, weekOf, windowsOf } from '@/lib/weekly-hours';

const level = (m: Record<string, 'VIEW' | 'EDIT' | 'FULL'>) => (key: string) => m[key] ?? null;
const addr = (over: Partial<ShipFromAddress> & { pickup?: boolean; driver?: boolean }): ShipFromAddress =>
  ({
    id: 'a',
    isDefault: false,
    customerPickup: { enabled: over.pickup ?? false, hours: [], readyHours: 0 },
    afrotransactPickup: { enabled: over.driver ?? false, hours: [] },
    ...over
  }) as unknown as ShipFromAddress;
const monday = { day: 'MONDAY' as const, opens: '10:00', closes: '16:00' };
const MSG = { none: 'none', missing: 'missing', order: 'order', ready: 'ready' };

describe('settings sections', () => {
  it('has one page per section, each under /settings, with a unique key and path', () => {
    expect(new Set(SETTINGS_SECTIONS.map((s) => s.key)).size).toBe(SETTINGS_SECTIONS.length);
    expect(new Set(SETTINGS_SECTIONS.map((s) => s.href)).size).toBe(SETTINGS_SECTIONS.length);
    expect(SETTINGS_SECTIONS.every((s) => s.href === `/settings/${s.key}`)).toBe(true);
    expect(settingsSectionOf('/settings/pickup')).toBe('pickup');
    expect(settingsSectionOf('/settings')).toBeNull();
    expect(settingsSectionOf('/orders')).toBeNull();
  });

  it('shows a member only the sections their role can open, and shipping sections to sellers only (the store page is for both)', () => {
    const owner = level({ orders: 'FULL', payouts: 'FULL', team: 'FULL', 'account-health': 'FULL' });
    expect(visibleSections(false, owner).map((s) => s.key)).toEqual(['business', 'store-page', 'locations', 'shipping', 'pickup', 'payouts', 'team', 'notifications', 'tax', 'documents', 'language']);
    expect(visibleSections(true, owner).map((s) => s.key)).toEqual(['business', 'store-page', 'payouts', 'team', 'notifications', 'tax', 'documents', 'language']);
    // Fulfilment can run pickup and shipping but not money, team or compliance; personal sections stay.
    const fulfilment = level({ orders: 'FULL' });
    expect(visibleSections(false, fulfilment).map((s) => s.key)).toEqual(['locations', 'shipping', 'pickup', 'notifications', 'language']);
  });

  it('lets a member change a section only with Edit on its module (personal sections always)', () => {
    const finance = level({ payouts: 'FULL', orders: 'VIEW' });
    const by = (k: string) => SETTINGS_SECTIONS.find((s) => s.key === k)!;
    expect(canEditSection(by('payouts'), finance)).toBe(true);
    expect(canEditSection(by('pickup'), finance)).toBe(false);
    expect(canEditSection(by('language'), finance)).toBe(true);
  });

  it('words each status from saved state: Pickup is Off until a location has it on', () => {
    expect(pickupStatus([])).toMatchObject({ key: 'sc.settings.status.pickupNoLocation' });
    expect(pickupStatus([addr({}), addr({})])).toMatchObject({ key: 'sc.settings.status.pickupOff', tone: 'neutral' });
    expect(pickupStatus([addr({ pickup: true }), addr({})])).toMatchObject({ key: 'sc.settings.status.pickupOn', vars: { on: 1, total: 2 }, tone: 'good' });
    expect(locationsStatus([addr({}), addr({})])).toMatchObject({ vars: { count: 2 } });
    expect(locationsStatus([])).toMatchObject({ key: 'sc.settings.status.locationsNone', tone: 'warn' });
    expect(shippingStatus([addr({ driver: true }), addr({})])).toMatchObject({ key: 'sc.settings.status.shippingDriver', vars: { on: 1, total: 2 } });
    expect(documentsStatus({ openItems: 0, payoutsPaused: false })).toMatchObject({ tone: 'good' });
    expect(documentsStatus({ openItems: 3, payoutsPaused: false })).toMatchObject({ vars: { count: 3 }, tone: 'warn' });
    expect(payoutsStatus({ payoutBank: { bankName: 'Chase', last4: '6789', status: null }, payoutsPaused: false })).toMatchObject({ vars: { bank: 'Chase', last4: '6789' } });
    expect(payoutsStatus({ payoutBank: null, payoutsPaused: true })).toMatchObject({ key: 'sc.settings.status.payoutsPaused' });
  });

  it('preloads a location from the business address only when it is complete', () => {
    expect(addressFromBusiness({ line1: '1 Main St', city: 'Houston', state: 'TX', postalCode: '77002' })).toEqual({ line1: '1 Main St', line2: '', city: 'Houston', state: 'TX', postalCode: '77002' });
    expect(addressFromBusiness({ line1: '1 Main St', city: 'Houston', state: 'TX' })).toBeNull();
    expect(addressFromBusiness(null)).toBeNull();
  });
});

describe('pickup form rules', () => {
  const open = (week: ReturnType<typeof weekOf>, day: DayRow['day'], from: string, to: string) => week.map((r) => (r.day === day ? { ...r, open: true, from, to } : r));

  it('starts all closed and empty while pickup is off; shows the saved week when it is on', () => {
    expect(draftOf(undefined)).toEqual({ enabled: false, week: closedWeek(), ready: '' });
    expect(draftOf(undefined).week.every((r) => !r.open && r.from === '' && r.to === '')).toBe(true);
    const on = draftOf({ enabled: true, hours: [monday], readyHours: 3 });
    expect(on).toMatchObject({ enabled: true, ready: '3' });
    expect(windowsOf(on.week)).toEqual([monday]);
  });

  it('never sends a turn-on without hours and a ready time, and a turn-on always carries the confirmation', () => {
    const week = open(closedWeek(), 'MONDAY', '10:00', '16:00');
    expect(pickupSave(undefined, { enabled: true, week: closedWeek(), ready: '2' }, MSG)).toMatchObject({ ok: false, message: 'none' });
    expect(pickupSave(undefined, { enabled: true, week, ready: '' }, MSG)).toMatchObject({ ok: false, message: 'ready' });
    expect(pickupSave(undefined, { enabled: true, week, ready: '2' }, MSG)).toEqual({ ok: true, request: { enabled: true, hours: [monday], readyHours: 2, hoursConfirmed: true } });
  });

  it('lets an edit of a place that stays on through, and switching off needs nothing', () => {
    const on = { enabled: true, hours: [monday], readyHours: 2 };
    expect(pickupSave(on, { enabled: true, week: weekOf([monday]), ready: '24' }, MSG)).toMatchObject({ ok: true, request: { readyHours: 24, hoursConfirmed: true } });
    expect(pickupSave(on, { enabled: false, week: closedWeek(), ready: '' }, MSG)).toEqual({ ok: true, request: { enabled: false, hours: [monday], readyHours: 2, hoursConfirmed: false } });
  });

  it('can save only with a change that is valid', () => {
    const on = { enabled: true, hours: [monday], readyHours: 2 };
    const same = draftOf(on);
    expect(pickupCanSave(on, same, MSG)).toBe(false);
    expect(pickupCanSave(on, { ...same, ready: '4' }, MSG)).toBe(true);
    expect(pickupCanSave(on, { ...same, week: open(same.week, 'TUESDAY', '10:00', '09:00') }, MSG)).toBe(false);
    expect(pickupCanSave(undefined, { enabled: true, week: closedWeek(), ready: '' }, MSG)).toBe(false);
    expect(pickupCanSave(on, { ...same, enabled: false }, MSG)).toBe(true);
    expect(pickupCanSave(undefined, draftOf(undefined), MSG)).toBe(false);
  });

  it('names the first thing wrong', () => {
    expect(pickupProblem({ enabled: false, week: closedWeek(), ready: '' }, MSG)).toBeNull();
    expect(pickupProblem({ enabled: true, week: closedWeek(), ready: '' }, MSG)?.message).toBe('none');
    expect(pickupProblem({ enabled: true, week: open(closedWeek(), 'MONDAY', '10:00', ''), ready: '' }, MSG)).toEqual({ message: 'missing', days: ['MONDAY'] });
    expect(pickupProblem({ enabled: true, week: open(closedWeek(), 'MONDAY', '10:00', '16:00'), ready: '' }, MSG)?.message).toBe('ready');
  });
});
