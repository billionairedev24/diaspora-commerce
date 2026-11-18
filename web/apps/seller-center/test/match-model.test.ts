import { type ChangeRequest } from '@afrotransact/api-client';
import { describe, expect, it } from 'vitest';

import { asksCondition, barcodeReady, blockerKey, candidateAsMatch, changeAsMatch, chosenLocation, conditionsAllowed, digitsOf, emptyOffer, locationProblem, type OfferForm, offerProblems, readyToSell, requestedSize, searchTextOf, serverOfferField, sizeNeedsNewProduct, sizeProblemKey, sizeToConfirm, toOfferInput } from '@/components/listings/add/match-model';
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
  const form = (over: Partial<OfferForm> = {}): OfferForm => ({ ...emptyOffer(), price: '12.50', qty: '8', shipFromId: 'loc-1', ...over });

  it('has no photo, title or attribute field in what it sends', () => {
    const input = toOfferInput('ATSHEAV001', form({ listPrice: '15', shipping: '4.99' }));
    expect(input).toEqual({ atin: 'ATSHEAV001', priceMinor: 1250, listPriceMinor: 1500, shippingMinor: 499, fulfilment: 'SELLER', condition: 'NEW', handlingDays: 2, quantity: 8, shipFromLocationId: 'loc-1' });
    for (const content of ['title', 'images', 'attributes', 'brand', 'category', 'gtin', 'description', 'productType']) expect(input).not.toHaveProperty(content);
  });

  it('asks for what the chosen way of shipping needs', () => {
    expect(offerProblems(form())).toEqual({});
    expect(Object.keys(offerProblems(form({ price: '' })))).toEqual(['of-price']);
    expect(Object.keys(offerProblems(form({ price: '10', listPrice: '8' })))).toEqual(['of-list-price']);
    expect(Object.keys(offerProblems(form({ qty: '' })))).toEqual(['of-qty']);
    expect(Object.keys(offerProblems(form({ condition: 'USED_GOOD' })))).toEqual(['of-note']);
    expect(offerProblems(form({ condition: 'USED_GOOD', note: 'Box dented' }))).toEqual({});
    expect(Object.keys(offerProblems(form({ fulfilment: 'AFROTRANSACT_DELIVERY', shipFromId: '' })))).toEqual(['of-ship-from']);
    expect(offerProblems(form({ shipFromId: '' }))['of-ship-from']).toBe('sc.match.err.pickupLocation');
    expect(Object.keys(offerProblems(form({ fulfilment: 'AFROTRANSACT', shipFromId: '' })))).toEqual(['of-pkg']);
    expect(offerProblems(form({ fulfilment: 'AFROTRANSACT', qty: '', shipFromId: '', pkg: { length: '12', width: '8', height: '4', weight: '2.5' } }))).toEqual({});
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

  it('never puts a refusal about the place on the price', () => {
    // The offers service names the place "shipFrom" or "shipFrom.locationId"; a name the form does not have is not guessed onto the price.
    expect(serverOfferField('shipFrom')).toBe('of-ship-from');
    expect(serverOfferField('shipFrom.locationId')).toBe('of-ship-from');
    expect(serverOfferField('somethingNew')).toBeUndefined();
  });

  it('sends the place that is shown selected: the default location when none was picked (the owner’s Pick up case)', () => {
    const places = [{ id: 'loc-2', isDefault: false }, { id: 'loc-1', isDefault: true }];
    expect(chosenLocation('', places)).toBe('loc-1');
    expect(chosenLocation('loc-2', places)).toBe('loc-2');
    expect(chosenLocation('gone', places)).toBe('loc-1');
    expect(chosenLocation('', [{ id: 'only', isDefault: false }])).toBe('only');
    expect(chosenLocation('', [{ id: 'a', isDefault: false }, { id: 'b', isDefault: false }])).toBe('');
    expect(chosenLocation('', [])).toBe('');
    const sent = toOfferInput('ATSHEAV001', { ...form({ price: '30', listPrice: '45', qty: '300', handling: '2' }), shipFromId: chosenLocation('', places) });
    expect(sent).toMatchObject({ priceMinor: 3000, listPriceMinor: 4500, quantity: 300, handlingDays: 2, fulfilment: 'SELLER', shipFromLocationId: 'loc-1' });
  });

  it('says what is missing at the location and where to fix it', () => {
    expect(locationProblem('PICKUP_HOURS_MISSING')).toMatchObject({ message: 'sc.match.err.pickupHours', href: '/settings/pickup' });
    expect(locationProblem('PICKUP_NOT_ON')).toMatchObject({ message: 'sc.match.err.pickupNotOn', href: '/settings/pickup' });
    expect(locationProblem('FBA_LOCATION_NOT_ON')).toMatchObject({ message: 'sc.match.err.fbaNotOn', href: '/settings/locations' });
    expect(locationProblem('OFFER_EXISTS')).toBeUndefined();
    expect(EN_MESSAGES['sc.match.err.pickupHours']).toBe('Add pickup hours to this location.');
  });

  it('has no ship-from or seller-fulfilled wording left from the old modes in the English copy', () => {
    const stale = Object.entries(EN_MESSAGES).filter(([k, v]) => /ship-from|seller-fulfilled|ships from/i.test(v) && !k.startsWith('sc.tour.'));
    expect(stale.map(([k]) => k)).toEqual([]);
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

describe('what a category allows is the server’s answer, not a list in the screen', () => {
  it('asks for the condition only when the page’s category allows more than New', () => {
    expect(asksCondition({})).toBe(false);
    expect(asksCondition({ allowedConditions: [] })).toBe(false);
    expect(asksCondition({ allowedConditions: ['NEW'] })).toBe(false);
    expect(asksCondition({ allowedConditions: ['NEW', 'RENEWED', 'USED_GOOD'] })).toBe(true);
    expect(conditionsAllowed({})).toEqual(['NEW']);
    expect(conditionsAllowed({ allowedConditions: ['NEW', 'USED_GOOD'] })).toEqual(['NEW', 'USED_GOOD']);
  });

  it('carries the allowed conditions from the candidate to the price-and-stock form', () => {
    const c = { parentAtin: 'ATLAPTOP01', title: 'Laptop', variants: [{ atin: 'ATLAPTOPV1', options: {} }], kind: 'SIMILAR' as const, likely: false, canOffer: true, blockers: [], allowedConditions: ['NEW' as const, 'USED_GOOD' as const] };
    expect(candidateAsMatch(c, 'ATLAPTOPV1').allowedConditions).toEqual(['NEW', 'USED_GOOD']);
  });
});

describe('a different size', () => {
  const asked = (over: Partial<ChangeRequest> = {}): ChangeRequest => ({ id: 'r1', atin: 'ATSHEA0001', title: 'Raw Shea Butter', type: 'NEW_VARIANT', status: 'APPLIED', requestedAt: '2026-11-17T10:00:00Z',
    payload: { source: 'SELLER_SIZE', options: { Size: '16 oz' } }, resultAtin: 'ATVAR00001', ...over });

  it('reads the size a request asked for, and is ready to sell only once staff applied it and the variant exists', () => {
    expect(requestedSize(asked())).toBe('16 oz');
    expect(requestedSize(asked({ payload: null }))).toBeUndefined();
    expect(readyToSell(asked())).toBe(true);
    expect(readyToSell(asked({ status: 'PENDING', resultAtin: null }))).toBe(false);
    expect(readyToSell(asked({ resultAtin: null }))).toBe(false);
    expect(readyToSell(asked({ type: 'TITLE' }))).toBe(false);
  });

  it('opens the price-and-stock form on the new variant with its size, and asks nothing about the condition', () => {
    const m = changeAsMatch(asked());
    expect(m).toMatchObject({ atin: 'ATVAR00001', parentAtin: 'ATSHEA0001', title: 'Raw Shea Butter', variantOptions: { Size: '16 oz' }, canOffer: true });
    expect(m.variants).toEqual([{ atin: 'ATVAR00001', options: { Size: '16 oz' } }]);
    expect(asksCondition(m)).toBe(false);
  });

  it('says why a size was refused in the seller’s words, and sends them to add the product as new only when the page cannot take a size', () => {
    for (const code of ['SIZE_INVALID', 'SIZE_PAGE_UNKNOWN', 'SIZE_NOT_ADDABLE', 'SIZE_REQUEST_BLOCKED', 'RATE_LIMITED', 'CHOICE_NOT_SHOWN']) expect(sizeProblemKey(code), code).toBeTruthy();
    expect(sizeProblemKey('SOMETHING_ELSE')).toBeUndefined();
    expect(sizeNeedsNewProduct('SIZE_NOT_ADDABLE')).toBe(true);
    expect(sizeNeedsNewProduct('SIZE_PAGE_UNKNOWN')).toBe(true);
    expect(sizeNeedsNewProduct('SIZE_INVALID')).toBe(false);
  });

  it('has no SKU in what an existing product sends', () => {
    expect(toOfferInput('ATSHEAV001', { ...emptyOffer(), price: '9', qty: '1' })).not.toHaveProperty('sellerSku');
    expect(Object.keys(emptyOffer())).not.toContain('sku');
  });
});
