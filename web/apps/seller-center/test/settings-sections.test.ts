import { type ShipFromAddress } from '@afrotransact/api-client';
import { describe, expect, it } from 'vitest';

import { draftOf, pickupHint, pickupSave } from '@/lib/pickup-settings';
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
const MSG = { ready: 'ready', confirm: 'confirm' };

describe('settings sections', () => {
  it('has one page per section, each under /settings, with a unique key and path', () => {
    expect(new Set(SETTINGS_SECTIONS.map((s) => s.key)).size).toBe(SETTINGS_SECTIONS.length);
    expect(new Set(SETTINGS_SECTIONS.map((s) => s.href)).size).toBe(SETTINGS_SECTIONS.length);
    expect(SETTINGS_SECTIONS.every((s) => s.href === `/settings/${s.key}`)).toBe(true);
    expect(settingsSectionOf('/settings/pickup')).toBe('pickup');
    expect(settingsSectionOf('/settings')).toBeNull();
    expect(settingsSectionOf('/orders')).toBeNull();
  });

  it('shows a member only the sections their role can open, and shipping sections to sellers only', () => {
    const owner = level({ orders: 'FULL', payouts: 'FULL', team: 'FULL', 'account-health': 'FULL' });
    expect(visibleSections(false, owner).map((s) => s.key)).toEqual(['business', 'locations', 'shipping', 'pickup', 'payouts', 'team', 'notifications', 'tax', 'documents', 'language']);
    expect(visibleSections(true, owner).map((s) => s.key)).toEqual(['business', 'payouts', 'team', 'notifications', 'tax', 'documents', 'language']);
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
  it('starts empty and unconfirmed while pickup is off; shows saved hours when it is on', () => {
    expect(draftOf(undefined)).toEqual({ enabled: false, hours: [], ready: '', confirmed: false });
    expect(draftOf({ enabled: true, hours: [monday], readyHours: 3 })).toEqual({ enabled: true, hours: [monday], ready: '3', confirmed: true });
  });

  it('never sends an unconfirmed turn-on, and always sends hoursConfirmed with a turn-on', () => {
    const off = undefined;
    const draft = { enabled: true, hours: [monday], ready: '2', confirmed: false };
    expect(pickupSave(off, draft, MSG)).toEqual({ ok: false, errors: { confirmed: 'confirm' } });
    expect(pickupSave(off, { ...draft, ready: '' }, MSG)).toMatchObject({ ok: false, errors: { readyHours: 'ready' } });
    expect(pickupSave(off, { ...draft, hours: [] }, MSG)).toMatchObject({ ok: false });
    expect(pickupSave(off, { ...draft, confirmed: true }, MSG)).toEqual({ ok: true, request: { enabled: true, hours: [monday], readyHours: 2, hoursConfirmed: true } });
  });

  it('lets an edit of a place that stays on through, and switching off needs nothing', () => {
    const on = { enabled: true, hours: [monday], readyHours: 2 };
    expect(pickupSave(on, { enabled: true, hours: [monday], ready: '5', confirmed: true }, MSG)).toMatchObject({ ok: true, request: { readyHours: 5, hoursConfirmed: true } });
    expect(pickupSave(on, { enabled: false, hours: [], ready: '', confirmed: false }, MSG)).toEqual({ ok: true, request: { enabled: false, hours: [monday], readyHours: 2, hoursConfirmed: false } });
  });

  it('names the first thing missing, in the order the form is filled', () => {
    expect(pickupHint(undefined, { enabled: false, hours: [], ready: '', confirmed: false })).toBeNull();
    expect(pickupHint(undefined, { enabled: true, hours: [], ready: '', confirmed: false })).toBe('hours');
    expect(pickupHint(undefined, { enabled: true, hours: [monday], ready: '', confirmed: false })).toBe('ready');
    expect(pickupHint(undefined, { enabled: true, hours: [monday], ready: '1', confirmed: false })).toBe('confirm');
    expect(pickupHint(undefined, { enabled: true, hours: [monday], ready: '1', confirmed: true })).toBeNull();
  });
});
