import { type AccessLevel, type StaffModuleKey, type StaffSession } from '@afrotransact/api-client';
import { keysOf, targetsOf, toursFor } from '@afrotransact/tour';
import { describe, expect, it } from 'vitest';

import { CATALOG_FILES } from '@/i18n/catalogs';
import { STAFF_TOURS } from '@/tours/staff-tours';
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

const ADMIN = session([['dash', 'FULL'], ['orders', 'FULL'], ['catalog', 'FULL'], ['apps', 'FULL'], ['fees', 'FULL'], ['rbac', 'FULL'], ['markets', 'FULL'], ['plans', 'FULL'], ['preorders', 'FULL']]);
const idsFor = (s: StaffSession) => toursFor(STAFF_TOURS, staffSubject(s)).map((t) => t.id);

describe('staff tours', () => {
  it('has the seven tours in order', () => {
    expect(STAFF_TOURS.map((t) => t.id)).toEqual(['admin-home', 'applications-review', 'moderation-queue', 'tax-review', 'orders-shipments', 'settings', 'roles-permissions']);
  });

  it('every title, summary, step title and step body exists in every locale catalogue', () => {
    for (const tour of STAFF_TOURS) {
      for (const key of keysOf(tour)) {
        for (const [locale, file] of Object.entries(CATALOG_FILES)) {
          expect(typeof file[key], `${locale}: ${key}`).toBe('string');
          expect(String(file[key]).trim(), `${locale}: ${key}`).not.toBe('');
        }
      }
    }
  });

  it('only points at data-tour ids, and the popover and help words exist', () => {
    for (const tour of STAFF_TOURS) for (const id of targetsOf(tour)) expect(id).toMatch(/^[a-z][a-z0-9]*(?:[-.][a-z0-9]+)*$/);
    for (const key of ['ui.next', 'ui.back', 'ui.skip', 'ui.done', 'ui.close', 'ui.dontShowAgain', 'ui.dialog', 'ui.stepOf', 'ui.stepDone', 'ui.started', 'ui.finished', 'help.label', 'help.heading', 'help.reset']) {
      expect(CATALOG_FILES.en[`st.tour.${key}`], key).toBeTruthy();
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

  it('plain words: no carriers, no collect-and-deliver line', () => {
    const text = STAFF_TOURS.flatMap((t) => keysOf(t)).map((k) => String(CATALOG_FILES.en[k])).join(' ').toLowerCase();
    expect(text).not.toMatch(/carrier|collects and delivers/);
  });
});

describe('staff tour audiences: only the tours a person\'s permissions allow', () => {
  it('an administrator sees every tour', () => {
    expect(idsFor(ADMIN)).toEqual(['admin-home', 'applications-review', 'moderation-queue', 'tax-review', 'orders-shipments', 'settings', 'roles-permissions']);
  });

  it('customer support sees orders but not applications, tax or roles', () => {
    const support = session([['dash', 'VIEW'], ['orders', 'EDIT'], ['support', 'EDIT']], { roles: [{ key: 'platform.customer_support', label: 'Support' }] });
    expect(idsFor(support)).toEqual(['admin-home', 'orders-shipments']);
  });

  it('a catalogue moderator gets the moderation tour only (besides the home tour)', () => {
    expect(idsFor(session([['dash', 'VIEW'], ['catalog', 'APPROVE']]))).toEqual(['admin-home', 'moderation-queue']);
  });

  it('a finance-only reader gets the tax and settings tours through the fees area', () => {
    expect(idsFor(session([['dash', 'VIEW'], ['fees', 'VIEW']]))).toEqual(['admin-home', 'tax-review', 'settings']);
  });

  it('the roles tour needs the people-and-roles area', () => {
    expect(idsFor(session([['dash', 'VIEW'], ['rbac', 'VIEW']]))).toEqual(['admin-home', 'roles-permissions']);
    expect(idsFor(session([['dash', 'VIEW']]))).toEqual(['admin-home']);
  });

  it('the subject carries an opaque id, role keys and permissions, and no personal details', () => {
    const subject = staffSubject(ADMIN);
    expect(subject.userId).toBe('staff-1');
    expect(subject.roles).toEqual(['platform.admin']);
    expect(subject.permissions).toEqual(expect.arrayContaining(['catalog:view', 'catalog:full', 'catalog.moderate']));
    expect(JSON.stringify(subject)).not.toMatch(/kemi|ade\b|example\.com/i);
  });

  it('settings sections are guarded by what the person can open', () => {
    const guards = staffGuards(session([['dash', 'VIEW'], ['markets', 'VIEW']]));
    expect(guards['can-settings-markets']?.()).toBe(true);
    expect(guards['can-settings-membership']?.()).toBe(false);
  });
});
