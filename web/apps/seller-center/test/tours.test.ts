import { type AccessLevel, type ModuleKey, type SellerSession } from '@afrotransact/api-client';
import { keysOf, targetsOf, toursFor } from '@afrotransact/tour';
import { describe, expect, it } from 'vitest';

import { CATALOG_FILES } from '@/i18n/catalogs';
import { SELLER_JOURNEYS, SELLER_TOURS } from '@/tours/seller-tours';
import { sellerGuards, sellerSubject } from '@/tours/subject';

function session(modules: [ModuleKey, AccessLevel][], over: Partial<SellerSession> = {}): SellerSession {
  return {
    user: { id: 'user-1', firstName: 'Ada', lastName: 'Obi', email: 'ada@example.com', initials: 'AO' },
    orgs: [],
    activeOrg: { id: 'org-1', kind: 'SELLER', name: 'Ade Kitchen', status: 'APPROVED', approved: true, roleKey: 'seller.owner', roleLabel: 'Owner' },
    portal: 'SELLER_CENTER',
    approved: true,
    navGroups: [{ key: 'all', label: 'All', modules: modules.map(([key, level]) => ({ key, label: key, level, badge: null })) }],
    landing: 'home',
    accessNote: '',
    capabilities: [],
    ...over
  };
}

const OWNER = session([['home', 'FULL'], ['orders', 'FULL'], ['listings', 'FULL'], ['inventory', 'FULL'], ['payouts', 'FULL'], ['account-health', 'FULL']]);
const idsFor = (s: SellerSession) => toursFor(SELLER_TOURS, sellerSubject(s)).map((t) => t.id);
const ALL_MODULES: [ModuleKey, AccessLevel][] = (
  ['home', 'orders', 'returns', 'disputes', 'listings', 'reviews', 'inventory', 'coupons', 'calendar', 'requests', 'hours', 'services', 'policy', 'earnings', 'payouts', 'fees', 'billing', 'account-health', 'team'] as ModuleKey[]
).map((k) => [k, 'FULL']);

describe('seller tours', () => {
  it('has the chapters in journey order', () => {
    expect(SELLER_TOURS.map((t) => t.id)).toEqual([
      'welcome', 'application', 'account-health', 'team', 'account-settings', 'help-support',
      'listings-overview', 'first-product', 'product-sections', 'images', 'bulk-upload', 'approvals', 'inventory',
      'orders', 'shipping-setup', 'run-pickups', 'returns-disputes',
      'dashboard', 'payouts', 'fees-billing', 'coupons', 'reviews',
      'services', 'availability', 'bookings', 'provider-earnings'
    ]);
  });

  it('every chapter is short (3 to 8 steps), belongs to a listed journey and has unique ids', () => {
    const journeys = new Set(SELLER_JOURNEYS.map((j) => j.id));
    expect(new Set(SELLER_TOURS.map((t) => t.id)).size).toBe(SELLER_TOURS.length);
    for (const tour of SELLER_TOURS) {
      expect(tour.steps.length, tour.id).toBeGreaterThanOrEqual(3);
      expect(tour.steps.length, tour.id).toBeLessThanOrEqual(8);
      expect(journeys.has(tour.journey ?? ''), `${tour.id} journey`).toBe(true);
    }
    for (const j of SELLER_JOURNEYS) expect(SELLER_TOURS.some((t) => t.journey === j.id), j.id).toBe(true);
  });

  it('every title, summary, step title and step body exists in every locale catalogue', () => {
    const keys = [...SELLER_TOURS.flatMap((t) => keysOf(t)), ...SELLER_JOURNEYS.flatMap((j) => [j.title, j.summary])];
    for (const key of keys) {
      for (const [locale, file] of Object.entries(CATALOG_FILES)) {
        expect(typeof file[key], `${locale}: ${key}`).toBe('string');
        expect(String(file[key]).trim(), `${locale}: ${key}`).not.toBe('');
      }
    }
  });

  it('only points at data-tour ids, and the popover and hub words exist too', () => {
    for (const tour of SELLER_TOURS) for (const id of targetsOf(tour)) expect(id).toMatch(/^[a-z][a-z0-9]*(?:[-.][a-z0-9]+)*$/);
    for (const key of ['next', 'back', 'skip', 'done', 'close', 'dontShowAgain', 'dialog', 'stepOf', 'stepDone', 'started', 'finished', 'skipStep', 'doItHint']) {
      expect(CATALOG_FILES.en[`sc.tour.ui.${key}`], key).toBeTruthy();
    }
    for (const key of ['title', 'description', 'intro', 'progress', 'skippedCount', 'stepCount', 'start', 'resume', 'replay', 'continue', 'skipJourney', 'skipChapter', 'statusNotStarted', 'statusInProgress', 'statusDone', 'statusSkipped', 'reset', 'resetConfirm', 'resetYes', 'resetCancel', 'resetFailed', 'resetDone', 'offline', 'autoOff', 'empty', 'pageTitle']) {
      for (const [locale, file] of Object.entries(CATALOG_FILES)) expect(file[`sc.tour.hub.${key}`], `${locale} hub ${key}`).toBeTruthy();
    }
  });

  it('keeps the placeholders of the English text in every language', () => {
    const placeholders = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort().join(',');
    const keys = Object.keys(CATALOG_FILES.en).filter((k) => k.startsWith('sc.tour.'));
    for (const key of keys) {
      for (const [locale, file] of Object.entries(CATALOG_FILES)) expect(placeholders(String(file[key])), `${locale}: ${key}`).toBe(placeholders(String(CATALOG_FILES.en[key])));
    }
  });

  it('tour text uses plain words: no carriers, no collect-and-deliver line, no ids, no tax advice', () => {
    const keys = [...SELLER_TOURS.flatMap((t) => keysOf(t)), ...SELLER_JOURNEYS.flatMap((j) => [j.title, j.summary])];
    const text = keys.map((k) => String(CATALOG_FILES.en[k])).join(' ').toLowerCase();
    expect(text).not.toMatch(/carrier|collects and delivers|collect and deliver|on your behalf|\bfedex\b|\bups\b|\bdhl\b|\busps\b|\buuid\b|\bid:/);
    expect(text).not.toMatch(/you should (claim|deduct|report)|tax advice|you must pay tax|deductible/);
  });

  it('"do it" steps wait for a click on the same control and never act for the person', () => {
    const doIts = SELLER_TOURS.flatMap((t) => t.steps.filter((s) => s.doIt).map((s) => ({ tour: t.id, ...s })));
    expect(doIts.length).toBeGreaterThan(0);
    for (const s of doIts) expect(s.success?.when, `${s.tour}.${s.id}`).toBe('click');
  });

  it('only the busiest first-time chapters start by themselves', () => {
    expect(SELLER_TOURS.filter((t) => t.autoStart).map((t) => t.id)).toEqual(['welcome', 'first-product', 'approvals', 'inventory', 'orders', 'payouts', 'services']);
  });
});

describe('seller tour audiences', () => {
  it('an owner of an approved seller business sees the chapters its modules reach, and none of the provider or plan-gated ones', () => {
    expect(idsFor(OWNER)).toEqual([
      'welcome', 'account-health', 'account-settings', 'help-support',
      'listings-overview', 'first-product', 'product-sections', 'approvals', 'inventory',
      'orders', 'shipping-setup', 'run-pickups',
      'dashboard', 'payouts'
    ]);
  });

  it('plan capabilities add the image library and bulk upload chapters', () => {
    const withCaps = session([['home', 'FULL'], ['listings', 'FULL']], { capabilities: ['imageLibrary', 'bulkUpload'] as SellerSession['capabilities'] });
    expect(idsFor(withCaps)).toEqual(expect.arrayContaining(['images', 'bulk-upload']));
    expect(idsFor(OWNER)).not.toContain('images');
  });

  it('a seller with every module sees every chapter but the application one', () => {
    const ids = idsFor(session(ALL_MODULES, { capabilities: ['imageLibrary', 'bulkUpload'] as SellerSession['capabilities'] }));
    expect(ids).toEqual(expect.arrayContaining(['team', 'returns-disputes', 'fees-billing', 'coupons', 'reviews', 'images', 'bulk-upload']));
    expect(ids).not.toContain('application');
    expect(ids).toHaveLength(SELLER_TOURS.length - 1);
  });

  it('a fulfilment role sees only what its permissions reach', () => {
    const fulfilment = session([['home', 'VIEW'], ['orders', 'EDIT'], ['inventory', 'EDIT']], { activeOrg: { ...OWNER.activeOrg!, roleKey: 'seller.fulfilment', roleLabel: 'Fulfilment' } });
    expect(idsFor(fulfilment)).toEqual(['welcome', 'account-settings', 'help-support', 'inventory', 'orders', 'shipping-setup', 'run-pickups', 'dashboard']);
  });

  it('view-only access to listings offers the overview but not the product-building chapters', () => {
    const viewer = session([['home', 'VIEW'], ['listings', 'VIEW'], ['orders', 'VIEW']]);
    const ids = idsFor(viewer);
    expect(ids).toContain('listings-overview');
    expect(ids).not.toContain('first-product');
    expect(ids).not.toContain('product-sections');
    expect(ids).not.toContain('approvals');
  });

  it('the service chapters are for providers that offer services', () => {
    const provider = session([['home', 'FULL'], ['services', 'FULL'], ['hours', 'FULL'], ['policy', 'FULL'], ['calendar', 'FULL'], ['requests', 'FULL'], ['earnings', 'FULL'], ['payouts', 'FULL']], {
      portal: 'PROVIDER_HUB',
      activeOrg: { ...OWNER.activeOrg!, kind: 'PROVIDER', roleKey: 'provider.owner' }
    });
    const ids = idsFor(provider);
    expect(ids).toEqual(expect.arrayContaining(['services', 'availability', 'bookings', 'provider-earnings', 'payouts']));
    expect(idsFor(session([['home', 'FULL'], ['orders', 'FULL']]))).not.toContain('services');
  });

  it('seller-only chapters are not offered in the Provider Hub', () => {
    const provider = session([['home', 'FULL'], ['orders', 'FULL'], ['returns', 'FULL'], ['coupons', 'FULL'], ['reviews', 'FULL']], { portal: 'PROVIDER_HUB' });
    const ids = idsFor(provider);
    for (const seller of ['orders', 'shipping-setup', 'run-pickups', 'returns-disputes', 'coupons', 'reviews']) expect(ids).not.toContain(seller);
  });

  it('a person whose application is not approved gets the welcome and application chapters, plus account and help', () => {
    const applying = session([['onboarding', 'FULL']], { approved: false, activeOrg: { ...OWNER.activeOrg!, status: 'PENDING_REVIEW', approved: false } });
    expect(idsFor(applying)).toEqual(['welcome', 'application', 'account-settings', 'help-support']);
    expect(sellerGuards(applying).applying?.()).toBe(true);
    expect(sellerGuards(applying).approved?.()).toBe(false);
  });

  it('the subject carries an opaque id, roles and module permissions, and no personal details', () => {
    const subject = sellerSubject(OWNER);
    expect(subject.userId).toBe('user-1');
    expect(subject.roles).toEqual(['seller.owner']);
    expect(subject.permissions).toEqual(expect.arrayContaining(['listings:view', 'listings:edit', 'listings:approve', 'listings:full']));
    expect(JSON.stringify(subject)).not.toMatch(/ada|obi|example\.com/i);
  });

  it('page guards follow the modules the role can open', () => {
    const guards = sellerGuards(session([['home', 'FULL'], ['hours', 'VIEW']]));
    expect(guards['can-hours']?.()).toBe(true);
    expect(guards['can-calendar']?.()).toBe(false);
  });

  it('every chapter step that goes to another page is guarded by a module the audience reaches', () => {
    const guards = sellerGuards(session(ALL_MODULES));
    for (const tour of SELLER_TOURS) for (const step of tour.steps) if (step.when) expect(guards[step.when], `${tour.id}.${step.id} guard "${step.when}"`).toBeTypeOf('function');
  });
});
