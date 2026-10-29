import { afterAll, beforeAll, beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import {
  ApiError,
  type ConsentRecord,
  type CookiePolicy,
  createApiClient,
  LEGAL_DOCUMENT_ALIASES,
  LEGAL_DOCUMENT_KEYS,
  LEGAL_DOCUMENT_TITLES,
  LEGAL_GROUPS,
  legalDocumentKeyOf,
  legalGroupTitle,
  legalKeys,
  type LegalPage,
  type PrivacyRequestReceipt
} from '../src';
import { type components } from '../src/generated/gateway-bff';
import { resetDb } from '../src/mocks';
import { mockServer } from '../src/mocks/node';

type S = components['schemas'];

const api = createApiClient({ baseUrl: 'http://bff.test' });

beforeAll(() => mockServer.listen({ onUnhandledRequest: 'error' }));
afterAll(() => mockServer.close());
beforeEach(() => { resetDb(); });

describe('legal schemas match the contract', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<LegalPage['outline']>().toExtend<S['LegalPage']['outline']>();
    expectTypeOf<CookiePolicy['categories']>().toExtend<S['CookiePolicy']['categories']>();
    expectTypeOf<ConsentRecord['saleOptOut']>().toExtend<S['ConsentRecord']['saleOptOut']>();
    expectTypeOf<PrivacyRequestReceipt>().toExtend<S['PrivacyRequestReceipt']>();
  });
});

describe('legal document keys', () => {
  it('resolves canonical keys and the legacy aliases', () => {
    expect(legalDocumentKeyOf('sms-terms')).toBe('sms-terms');
    expect(legalDocumentKeyOf('terms')).toBe('conditions-of-use');
    expect(legalDocumentKeyOf('SMS')).toBe('sms-terms');
    expect(legalDocumentKeyOf('choices')).toBe('privacy-choices');
    expect(legalDocumentKeyOf('nope')).toBeNull();
    expect(legalKeys.document('privacy', 'fr')).toEqual(['legal', 'document', 'privacy', 'fr', 'current']);
  });

  it('knows the twenty-one documents and the new keys and their short links', () => {
    expect(LEGAL_DOCUMENT_KEYS).toHaveLength(21);
    expect(new Set(LEGAL_DOCUMENT_KEYS).size).toBe(21);
    expect(legalDocumentKeyOf('gift-cards')).toBe('gift-card-terms');
    expect(legalDocumentKeyOf('DPA')).toBe('data-processing');
    expect(legalDocumentKeyOf('fees')).toBe('seller-fees');
    expect(legalDocumentKeyOf('Pre-Order')).toBe('preorder-delivery-terms');
  });

  it('every alias points at a real document and never shadows a canonical key', () => {
    for (const [alias, key] of Object.entries(LEGAL_DOCUMENT_ALIASES)) {
      expect(LEGAL_DOCUMENT_KEYS).toContain(key);
      expect(LEGAL_DOCUMENT_KEYS).not.toContain(alias);
    }
  });

  it('has an English title and a section for every document', () => {
    expect(Object.keys(LEGAL_DOCUMENT_TITLES).sort()).toEqual([...LEGAL_DOCUMENT_KEYS].sort());
    expect(legalGroupTitle('seller-fees')).toBe('Payments and money');
    expect(legalGroupTitle('privacy')).toBe('Privacy and cookies');
    expect(legalGroupTitle('nope')).toBe('');
    for (const key of LEGAL_DOCUMENT_KEYS) expect(legalGroupTitle(key), key).not.toBe('');
  });

  it('groups the index into the five sections with every document exactly once', () => {
    expect(LEGAL_GROUPS.map((g) => g.id)).toEqual(['customers', 'payments', 'sellers', 'privacy', 'policies']);
    const all: string[] = LEGAL_GROUPS.flatMap((g) => [...g.documents]);
    expect(all).toHaveLength(LEGAL_DOCUMENT_KEYS.length);
    expect([...all].sort()).toEqual([...LEGAL_DOCUMENT_KEYS].sort());
  });
});

describe('mock BFF: legal & privacy center', () => {
  it('lists every document in the legal center index', async () => {
    const index = await api.getLegalIndex();
    expect(index.documents.map((d) => d.key)).toEqual([...LEGAL_DOCUMENT_KEYS]);
    expect(index.documents.every((d) => d.href === `/legal/${d.key}` && d.version !== '')).toBe(true);
  });

  it('serves every document with an outline, versions and filled-in contacts', async () => {
    for (const key of LEGAL_DOCUMENT_KEYS) {
      const page = await api.getLegalDocument(key, { locale: 'fr' });
      expect(page.key).toBe(key);
      expect(page.outline.length).toBeGreaterThan(0);
      expect(page.translationFallback).toBe(true);
      expect(page.body).not.toMatch(/\{(phone|privacy|legal|accessibility|address|smsProgramName)\}/);
    }
    const sms = await api.getLegalDocument('sms-terms');
    expect(sms.body).toContain('AfroTransact Alerts is a text-message program');
    const archived = await api.getLegalDocument('conditions-of-use', { version: '3.1' });
    expect(archived.status).toBe('ARCHIVED');
    await expect(api.getLegalDocument('conditions-of-use', { version: '0.1' })).rejects.toBeInstanceOf(ApiError);
  });

  it('records consent with GPC and the sale/sharing opt-out forcing advertising off', async () => {
    const policy = await api.getCookiePolicy();
    expect(policy.categories.map((c) => c.key)).toEqual(['necessary', 'functional', 'analytics', 'advertising']);
    const gpc = await api.recordConsent({ categories: { analytics: true, advertising: true }, gpc: true, source: 'banner' });
    expect(gpc.categories).toEqual({ necessary: true, functional: false, analytics: true, advertising: false });
    expect(gpc.saleOptOut).toBe(true);
    const all = await api.recordConsent({ categories: { functional: true, analytics: true, advertising: true } });
    expect(all.categories.advertising).toBe(true);
    const optOut = await api.recordConsent({ categories: { advertising: true }, saleOptOut: true, source: 'privacy-choices' });
    expect(optOut.categories.advertising).toBe(false);
  });

  it('validates privacy requests with the form wording and returns a reference', async () => {
    const err = await api.submitPrivacyRequest({ type: 'access', email: 'nope', state: 'ZZ' }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).fieldErrors).toMatchObject({ email: 'Enter the email address on your account', state: 'Choose your state' });
    const r = await api.submitPrivacyRequest({ type: 'delete', email: 'ada@example.com', state: 'TX' });
    expect(r.reference).toMatch(/^PR-\d{6}$/);
    expect(r.status).toBe('RECEIVED');
  });
});
