import { type AccessLevel, type ModuleKey, type SellerSession } from '@afrotransact/api-client';
import { keysOf, targetsOf, toursFor } from '@afrotransact/tour';
import { describe, expect, it } from 'vitest';

import { CATALOG_FILES } from '@/i18n/catalogs';
import { SELLER_TOURS } from '@/tours/seller-tours';
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

describe('seller tours', () => {
  it('has the seven tours in order', () => {
    expect(SELLER_TOURS.map((t) => t.id)).toEqual(['welcome', 'first-product', 'approvals', 'inventory', 'orders', 'payouts', 'services']);
  });

  it('every title, summary, step title and step body exists in every locale catalogue', () => {
    for (const tour of SELLER_TOURS) {
      for (const key of keysOf(tour)) {
        for (const [locale, file] of Object.entries(CATALOG_FILES)) {
          expect(typeof file[key], `${locale}: ${key}`).toBe('string');
          expect(String(file[key]).trim(), `${locale}: ${key}`).not.toBe('');
        }
      }
    }
  });

  it('only points at data-tour ids, and the popover words exist too', () => {
    for (const tour of SELLER_TOURS) for (const id of targetsOf(tour)) expect(id).toMatch(/^[a-z][a-z0-9]*(?:[-.][a-z0-9]+)*$/);
    for (const key of ['next', 'back', 'skip', 'done', 'close', 'dontShowAgain', 'dialog', 'stepOf', 'stepDone', 'started', 'finished']) {
      expect(CATALOG_FILES.en[`sc.tour.ui.${key}`], key).toBeTruthy();
    }
  });

  it('tour text uses plain words: no carriers, no collect-and-deliver line, no ids', () => {
    const text = SELLER_TOURS.flatMap((t) => keysOf(t)).map((k) => String(CATALOG_FILES.en[k])).join(' ').toLowerCase();
    expect(text).not.toMatch(/carrier|collects and delivers|\bfedex\b|\bups\b|\bdhl\b|\buuid\b|\bid:/);
  });
});

describe('seller tour audiences', () => {
  it('an owner of an approved seller business sees every seller tour except services', () => {
    expect(idsFor(OWNER)).toEqual(['welcome', 'first-product', 'approvals', 'inventory', 'orders', 'payouts']);
  });

  it('a fulfilment role sees only what its permissions reach', () => {
    const fulfilment = session([['home', 'VIEW'], ['orders', 'EDIT'], ['inventory', 'EDIT']], { activeOrg: { ...OWNER.activeOrg!, roleKey: 'seller.fulfilment', roleLabel: 'Fulfilment' } });
    expect(idsFor(fulfilment)).toEqual(['welcome', 'inventory', 'orders']);
  });

  it('view-only access to listings does not offer the product tours', () => {
    const viewer = session([['home', 'VIEW'], ['listings', 'VIEW'], ['orders', 'VIEW']]);
    expect(idsFor(viewer)).toEqual(['welcome', 'orders']);
  });

  it('the services tour is for providers that offer services', () => {
    const provider = session([['home', 'FULL'], ['services', 'FULL'], ['calendar', 'FULL'], ['payouts', 'FULL']], {
      portal: 'PROVIDER_HUB',
      activeOrg: { ...OWNER.activeOrg!, kind: 'PROVIDER', roleKey: 'provider.owner' }
    });
    expect(idsFor(provider)).toEqual(['welcome', 'payouts', 'services']);
    expect(idsFor(session([['home', 'FULL'], ['orders', 'FULL']]))).not.toContain('services');
  });

  it('the orders tour is not offered in the Provider Hub', () => {
    const provider = session([['home', 'FULL'], ['orders', 'FULL']], { portal: 'PROVIDER_HUB' });
    expect(idsFor(provider)).not.toContain('orders');
  });

  it('a person whose application is not approved only gets the welcome tour', () => {
    const applying = session([['onboarding', 'FULL']], { approved: false, activeOrg: { ...OWNER.activeOrg!, status: 'IN_REVIEW', approved: false } });
    expect(idsFor(applying)).toEqual(['welcome']);
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
});
