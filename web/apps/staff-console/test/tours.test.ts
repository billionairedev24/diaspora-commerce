import { type AccessLevel, type StaffModuleKey, type StaffSession } from '@afrotransact/api-client';
import { keysOf, targetsOf, toursFor } from '@afrotransact/tour';
import { describe, expect, it } from 'vitest';

import { CATALOG_FILES } from '@/i18n/catalogs';
import { STAFF_JOURNEYS, STAFF_TOURS } from '@/tours/staff-tours';
import { staffGuards, staffSubject } from '@/tours/subject';

function session(modules: [StaffModuleKey, AccessLevel][], over: Partial<StaffSession> = {}): StaffSession {
  return {
    user: { id: 'staff-1', firstName: 'Kemi', lastName: 'Ade', email: 'kemi@example.com', initials: 'KA' },
    roles: [{ key: 'platform.admin', label: 'Administrator' }],
    roleLabel: 'Administrator',
    permissions: ['catalog.moderate'],
    navGroups: [{ key: 'overview', label: 'All', modules: modules.map(([key, level]) => ({ key, label: key, description: '', level, badge: null })) }],
    landing: 'dash',
    accessNote: '',
    refundLimitMinor: null,
    ...over
  };
}

const EVERY_MODULE: StaffModuleKey[] = [
  'dash', 'exports', 'orders', 'catalog', 'inventory', 'retail', 'dispatch', 'orgs', 'apps', 'deptRequests', 'deliveryQuotes', 'support', 'rbac', 'payouts', 'fees', 'marketing',
  'disputes', 'audit', 'markets', 'siteCfg', 'dev', 'identity', 'notifications', 'preorders', 'plans'
];
const ADMIN = session(EVERY_MODULE.map((k): [StaffModuleKey, AccessLevel] => [k, 'FULL']));
const idsFor = (s: StaffSession) => toursFor(STAFF_TOURS, staffSubject(s)).map((t) => t.id);

describe('staff tours', () => {
  it('has the chapters in journey order', () => {
    expect(STAFF_TOURS.map((t) => t.id)).toEqual([
      'admin-home', 'my-account',
      'applications-review', 'selling-approvals', 'organisations', 'department-requests', 'delivery-quotes',
      'moderation-queue', 'catalogue-rules',
      'orders-shipments', 'disputes-returns', 'support-refunds', 'payouts', 'tax-review', 'tax-profiles', 'fees-plans',
      'inventory', 'retail', 'dispatch', 'preorders', 'exports',
      'roles-permissions', 'settings', 'site-content', 'notifications', 'identity-access', 'marketing', 'audit-log'
    ]);
  });

  it('every chapter is short (3 to 8 steps), belongs to a listed journey and has unique ids', () => {
    const journeys = new Set(STAFF_JOURNEYS.map((j) => j.id));
    expect(new Set(STAFF_TOURS.map((t) => t.id)).size).toBe(STAFF_TOURS.length);
    for (const tour of STAFF_TOURS) {
      expect(tour.steps.length, tour.id).toBeGreaterThanOrEqual(3);
      expect(tour.steps.length, tour.id).toBeLessThanOrEqual(8);
      expect(journeys.has(tour.journey ?? ''), `${tour.id} journey`).toBe(true);
    }
    for (const j of STAFF_JOURNEYS) expect(STAFF_TOURS.some((t) => t.journey === j.id), j.id).toBe(true);
  });

  it('every title, summary, step title and step body exists in every locale catalogue', () => {
    const keys = [...STAFF_TOURS.flatMap((t) => keysOf(t)), ...STAFF_JOURNEYS.flatMap((j) => [j.title, j.summary])];
    for (const key of keys) {
      for (const [locale, file] of Object.entries(CATALOG_FILES)) {
        expect(typeof file[key], `${locale}: ${key}`).toBe('string');
        expect(String(file[key]).trim(), `${locale}: ${key}`).not.toBe('');
      }
    }
  });

  it('only points at data-tour ids, and the popover, help and hub words exist', () => {
    for (const tour of STAFF_TOURS) for (const id of targetsOf(tour)) expect(id).toMatch(/^[a-z][a-z0-9]*(?:[-.][a-z0-9]+)*$/);
    for (const key of ['ui.next', 'ui.back', 'ui.skip', 'ui.done', 'ui.close', 'ui.dontShowAgain', 'ui.dialog', 'ui.stepOf', 'ui.stepDone', 'ui.started', 'ui.finished', 'ui.skipStep', 'ui.doItHint', 'help.label', 'help.heading', 'help.hub', 'help.resume', 'help.new']) {
      for (const [locale, file] of Object.entries(CATALOG_FILES)) expect(file[`st.tour.${key}`], `${locale} ${key}`).toBeTruthy();
    }
    for (const key of ['title', 'description', 'intro', 'progress', 'skippedCount', 'stepCount', 'start', 'resume', 'replay', 'continue', 'skipJourney', 'skipChapter', 'statusNotStarted', 'statusInProgress', 'statusDone', 'statusSkipped', 'reset', 'resetConfirm', 'resetYes', 'resetCancel', 'resetFailed', 'resetDone', 'offline', 'autoOff', 'empty', 'pageTitle']) {
      for (const [locale, file] of Object.entries(CATALOG_FILES)) expect(file[`st.tour.hub.${key}`], `${locale} hub ${key}`).toBeTruthy();
    }
  });

  it('keeps the placeholders of the English text in every language', () => {
    const placeholders = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort().join(',');
    for (const key of Object.keys(CATALOG_FILES.en).filter((k) => k.startsWith('st.tour.'))) {
      for (const [locale, file] of Object.entries(CATALOG_FILES)) expect(placeholders(String(file[key])), `${locale}: ${key}`).toBe(placeholders(String(CATALOG_FILES.en[key])));
    }
  });

  it('the orders tour warns that marking a package picked up charges the customer', () => {
    const body = String(CATALOG_FILES.en['st.tour.orders.pickedUp.body']);
    expect(body).toMatch(/picked up/i);
    expect(body).toMatch(/charges the customer/i);
    const tour = STAFF_TOURS.find((t) => t.id === 'orders-shipments');
    expect(tour?.steps.some((s) => s.body === 'st.tour.orders.pickedUp.body')).toBe(true);
  });

  it('the applications tour says the same person cannot approve their own review', () => {
    expect(String(CATALOG_FILES.en['st.tour.applications.samePerson.body'])).toMatch(/cannot do step 2/i);
  });

  it('tax wording is safe: the tax chapters say they are not tax advice and give none', () => {
    expect(String(CATALOG_FILES.en['st.tour.taxProfiles.wrap.body'])).toMatch(/not tax advice/i);
    const text = STAFF_TOURS.flatMap((t) => keysOf(t)).map((k) => String(CATALOG_FILES.en[k])).join(' ').toLowerCase();
    expect(text).not.toMatch(/you should (claim|deduct|report)|you must pay tax|deductible/);
  });

  it('plain words: no carriers, no collect-and-deliver line, no ids', () => {
    const keys = [...STAFF_TOURS.flatMap((t) => keysOf(t)), ...STAFF_JOURNEYS.flatMap((j) => [j.title, j.summary])];
    const text = keys.map((k) => String(CATALOG_FILES.en[k])).join(' ').toLowerCase();
    expect(text).not.toMatch(/carrier|collects and delivers|collect and deliver|on your behalf|\bfedex\b|\bups\b|\bdhl\b|\busps\b|\buuid\b|\bid:/);
  });

  it('only the busiest first-time chapters start by themselves', () => {
    expect(STAFF_TOURS.filter((t) => t.autoStart).map((t) => t.id)).toEqual(['admin-home', 'applications-review', 'moderation-queue', 'orders-shipments', 'roles-permissions']);
  });
});

describe('staff tour audiences: only the chapters a person\'s permissions allow', () => {
  it('an administrator sees every chapter', () => {
    expect(idsFor(ADMIN)).toEqual(STAFF_TOURS.map((t) => t.id));
  });

  it('customer support sees orders and support but not applications, tax or roles', () => {
    const support = session([['dash', 'VIEW'], ['orders', 'EDIT'], ['support', 'EDIT']], { roles: [{ key: 'platform.customer_support', label: 'Support' }] });
    expect(idsFor(support)).toEqual(['admin-home', 'my-account', 'orders-shipments', 'support-refunds']);
  });

  it('a catalogue moderator gets the catalogue chapters only (besides the first two)', () => {
    expect(idsFor(session([['dash', 'VIEW'], ['catalog', 'APPROVE']]))).toEqual(['admin-home', 'my-account', 'moderation-queue', 'catalogue-rules']);
  });

  it('a finance-only reader gets the tax, fees and settings chapters through the fees area', () => {
    expect(idsFor(session([['dash', 'VIEW'], ['fees', 'VIEW']]))).toEqual(['admin-home', 'my-account', 'tax-review', 'tax-profiles', 'fees-plans', 'settings']);
  });

  it('the roles chapter needs the people-and-roles area', () => {
    expect(idsFor(session([['dash', 'VIEW'], ['rbac', 'VIEW']]))).toEqual(['admin-home', 'my-account', 'roles-permissions']);
    expect(idsFor(session([['dash', 'VIEW']]))).toEqual(['admin-home', 'my-account']);
  });

  it('the subject carries an opaque id, role keys and permissions, and no personal details', () => {
    const subject = staffSubject(ADMIN);
    expect(subject.userId).toBe('staff-1');
    expect(subject.roles).toEqual(['platform.admin']);
    expect(subject.permissions).toEqual(expect.arrayContaining(['catalog:view', 'catalog:full', 'catalog.moderate']));
    expect(JSON.stringify(subject)).not.toMatch(/kemi|ade\b|example\.com/i);
  });

  it('guards follow the modules and settings sections the person can open', () => {
    const guards = staffGuards(session([['dash', 'VIEW'], ['markets', 'VIEW']]));
    expect(guards['can-settings-markets']?.()).toBe(true);
    expect(guards['can-settings-membership']?.()).toBe(false);
    expect(guards['can-markets']?.()).toBe(true);
    expect(guards['can-plans']?.()).toBe(false);
  });

  it('every guard a step names exists', () => {
    const guards = staffGuards(ADMIN);
    for (const tour of STAFF_TOURS) for (const step of tour.steps) if (step.when) expect(guards[step.when], `${tour.id}.${step.id} guard "${step.when}"`).toBeTypeOf('function');
  });
});
