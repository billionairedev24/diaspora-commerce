import { describe, expect, it } from 'vitest';

import { barcodeReady, blockerKey, candidateAsMatch, digitsOf, emptyOffer, type OfferForm, offerProblems, searchTextOf, serverOfferField, sizeToConfirm, toOfferInput } from '@/components/listings/add/match-model';
import { withStart } from '@/components/listings/form-model';
import { emptyForm } from '@/components/listings/form-model';
import { CATALOG_FILES } from '@/i18n/catalogs';
import { EN_MESSAGES } from '@/i18n/t';

describe('the add-product search and the barcode shortcut (S382)', () => {
  it('cleans spaces and dashes and looks up on its own from 12 to 14 digits', () => {
    expect(digitsOf('0123 4567-8905')).toBe('012345678905');
    expect(barcodeReady('012345678905', false)).toBe(true);
    expect(barcodeReady('4006381333931', false)).toBe(true);
    expect(barcodeReady('04006381333931', false)).toBe(true);
    expect(barcodeReady('01234567890', false)).toBe(false);
    expect(barcodeReady('0123456789012345', false)).toBe(false);
    expect(barcodeReady('abc', true)).toBe(false);
  });

  it('shorter codes (EAN-8, ISBN-10) wait for the button', () => {
    expect(barcodeReady('96385074', false)).toBe(false);
    expect(barcodeReady('96385074', true)).toBe(true);
    expect(barcodeReady('043942089X', true)).toBe(true);
  });

  it('searches a name only from 3 letters, with one space between words and at most 200 characters', () => {
    expect(searchTextOf('  basmati   rice 5kg ')).toBe('basmati rice 5kg');
    expect(searchTextOf('ba')).toBeNull();
    expect(searchTextOf('  a b ')).toBeNull();
    expect(searchTextOf('x'.repeat(300))).toHaveLength(200);
  });

  it('asks the one-tap size question from the page’s size, else from the chosen variant, and none when the page states none', () => {
    const page = { parentAtin: 'ATRICE0001', title: 'Rice', kind: 'SIMILAR' as const, likely: false, canOffer: true, blockers: [], variants: [{ atin: 'ATRICEV001', options: { Size: '5 kg' } }, { atin: 'ATRICEV002', options: { Size: '10 kg' } }] };
    expect(sizeToConfirm({ ...page, sizeLabel: '5 kg' }, 'ATRICEV001')).toBe('5 kg');
    expect(sizeToConfirm(page, 'ATRICEV002')).toBe('Size: 10 kg');
    expect(sizeToConfirm({ ...page, variants: [{ atin: 'ATRICEV001', options: {} }] }, 'ATRICEV001')).toBeNull();
  });

  it('hands a found page to the price-and-stock form as the same match the barcode shortcut gives', () => {
    const m = candidateAsMatch({ parentAtin: 'ATSHEA0001', title: 'Raw Shea Butter, 8 oz', brand: 'Kente Naturals', kind: 'SAME_NAME', likely: true, canOffer: true, blockers: [], sellerCount: 2,
      variants: [{ atin: 'ATSHEAV001', options: { Size: '8 oz' } }] }, 'ATSHEAV001');
    expect(m).toMatchObject({ result: 'MATCH', atin: 'ATSHEAV001', title: 'Raw Shea Butter, 8 oz', sellerCount: 2, canOffer: true, variantOptions: { Size: '8 oz' } });
  });

  it('has the seller’s words for every blocker catalog and offers send', () => {
    for (const code of ['catalog.page-not-live', 'offers.selling-approval-required', 'catalog.brand-unavailable', 'offers.offer-exists', 'offers.listing-limit-reached',
      'catalog.wrong-match-open', 'catalog.gtin-exists']) {
      const key = blockerKey(code);
      expect(key, code).toBeDefined();
      expect(EN_MESSAGES[key ?? 'sc.match.back'], code).toBeTruthy();
    }
    expect(blockerKey('something.new')).toBeUndefined();
  });

  it('starts the full form from the barcode, or from the name and category chip typed in the search box', () => {
    const withCode = withStart(emptyForm(), { gtin: '012345678905', brand: ' Kente Naturals ' });
    expect(withCode.single.gtin).toBe('012345678905');
    expect(withCode.noGtin).toBe(false);
    expect(withCode.attrs.brand).toBe('Kente Naturals');
    const typed = withStart(emptyForm(), { noGtin: true, gtin: '012345678905', title: ' basmati rice 5kg ', category: { id: 'pt-grains', path: 'Groceries › Pantry › Rice', productType: 'grocery' } });
    expect(typed.noGtin).toBe(true);
    expect(typed.single.gtin).toBe('');
    expect(typed).toMatchObject({ title: 'basmati rice 5kg', categoryId: 'pt-grains', productType: 'grocery' });
    expect(withStart(emptyForm(), { noGtin: true }).categoryId).toBeNull();
    expect(withStart(emptyForm(), undefined)).toEqual(emptyForm());
  });

  it('has no part number anywhere a seller reads', () => {
    for (const [key, text] of Object.entries(EN_MESSAGES)) if (key.startsWith('sc.match.') || key.startsWith('sc.tour.firstProduct') || key.startsWith('sc.bulk.')) expect(text, key).not.toMatch(/part number|\bMPN\b/i);
  });
});

describe('the offer-only form', () => {
  const form = (over: Partial<OfferForm> = {}): OfferForm => ({ ...emptyOffer(), price: '12.50', qty: '8', ...over });

  it('has no photo, title or attribute field in what it sends', () => {
    const input = toOfferInput('ATSHEAV001', form({ listPrice: '15', shipping: '4.99', sku: ' SHEA-8 ' }));
    expect(input).toEqual({ atin: 'ATSHEAV001', priceMinor: 1250, listPriceMinor: 1500, shippingMinor: 499, fulfilment: 'SELLER', condition: 'NEW', handlingDays: 2, quantity: 8, sellerSku: 'SHEA-8' });
    for (const content of ['title', 'images', 'attributes', 'brand', 'category', 'gtin', 'description', 'productType']) expect(input).not.toHaveProperty(content);
  });

  it('asks for what the chosen way of shipping needs', () => {
    expect(offerProblems(form())).toEqual({});
    expect(Object.keys(offerProblems(form({ price: '' })))).toEqual(['of-price']);
    expect(Object.keys(offerProblems(form({ price: '10', listPrice: '8' })))).toEqual(['of-list-price']);
    expect(Object.keys(offerProblems(form({ qty: '' })))).toEqual(['of-qty']);
    expect(Object.keys(offerProblems(form({ condition: 'USED_GOOD' })))).toEqual(['of-note']);
    expect(offerProblems(form({ condition: 'USED_GOOD', note: 'Box dented' }))).toEqual({});
    expect(Object.keys(offerProblems(form({ fulfilment: 'AFROTRANSACT_DELIVERY' })))).toEqual(['of-ship-from']);
    expect(Object.keys(offerProblems(form({ fulfilment: 'AFROTRANSACT' })))).toEqual(['of-pkg']);
    expect(offerProblems(form({ fulfilment: 'AFROTRANSACT', qty: '', pkg: { length: '12', width: '8', height: '4', weight: '2.5' } }))).toEqual({});
    expect(Object.keys(offerProblems(form({ handling: '45' })))).toEqual(['of-handling']);
  });

  it('sends the package only for Fulfilled by AfroTransact and the pickup place only when it is chosen', () => {
    const fba = toOfferInput('ATSHEAV001', form({ fulfilment: 'AFROTRANSACT', qty: '', pkg: { length: '12', width: '8', height: '4', weight: '2.5' }, shipFromId: 'loc-1' }));
    expect(fba.packaging).toEqual({ lengthIn: 12, widthIn: 8, heightIn: 4, weightLb: 2.5 });
    expect(fba).not.toHaveProperty('quantity');
    expect(fba).not.toHaveProperty('shipFromLocationId');
    const pickup = toOfferInput('ATSHEAV001', form({ fulfilment: 'AFROTRANSACT_DELIVERY', shipFromId: 'loc-1' }));
    expect(pickup.shipFromLocationId).toBe('loc-1');
    expect(pickup).not.toHaveProperty('packaging');
  });

  it('puts a refusal of the server on the field it is about', () => {
    expect(serverOfferField('priceMinor')).toBe('of-price');
    expect(serverOfferField('conditionNote')).toBe('of-note');
    expect(serverOfferField('shipFromLocationId')).toBe('of-ship-from');
  });
});

describe('the matching words in the five locales', () => {
  it('has every sc.match.* and sc.change.* key in en fr es ar yo, flagged for native review', () => {
    const keys = Object.keys(EN_MESSAGES).filter((k) => k.startsWith('sc.match.') || k.startsWith('sc.change.'));
    expect(keys.length).toBeGreaterThan(100);
    for (const [locale, file] of Object.entries(CATALOG_FILES)) {
      for (const k of keys) expect(typeof file[k], `${locale}: ${k}`).toBe('string');
      if (locale !== 'en') expect(file.$meta, locale).toMatchObject({ source: 'machine', needsReview: true });
    }
  });

  it('says "listing", never "offer", to the seller', () => {
    const bad = Object.entries(EN_MESSAGES).filter(([k, v]) => /\boffers?\b/i.test(v) && (k.startsWith('sc.match.') || k.startsWith('sc.change.')));
    expect(bad.map(([k]) => k)).toEqual([]);
  });

  it('shows the tax hold in the words the brief gave', () => {
    expect(EN_MESSAGES['sc.match.offer.held.title']).toBe('We are checking the tax details for this item.');
  });
});
