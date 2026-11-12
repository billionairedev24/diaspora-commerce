import { type MatchRequest, OFFER_CONDITIONS, type OfferCondition, type OfferFulfilment, type OfferOnlyInput } from '@afrotransact/api-client';

import { type MessageKey } from '@/i18n/t';

import { priceMinor } from '../form-model';

/* Pure rules of "Add a product" (S382): when the barcode lookup runs, what is asked, why a seller may not sell a matched page, and the
   offer-only form's checks. The server checks everything again (catalog and offers); these keep the answers fast and in the seller's words. */

export type FindMode = 'barcode' | 'brand' | 'none';

/** The lookup runs on its own from this many digits (a UPC has 12, an EAN 13, a GTIN-14 14). Shorter codes (EAN-8, ISBN-10) use the button. */
export const AUTO_LOOKUP_DIGITS = 12;
export const MAX_BARCODE_DIGITS = 14;

/** The barcode without spaces and dashes. */
export const digitsOf = (code: string): string => code.replace(/[\s-]/g, '');

/** True when the barcode can be looked up: 12 to 14 digits on their own, or 8 or more characters when the seller asked (EAN-8, ISBN-10). */
export function barcodeReady(code: string, asked: boolean): boolean {
  const d = digitsOf(code);
  if (/^\d{12,14}$/.test(d)) return true;
  return asked && /^(\d{8,11}|\d{9}[\dXx])$/.test(d);
}

export interface FindFields {
  code: string;
  brand: string;
  mpn: string;
}

/** What to ask catalog for the chosen way of finding the product (null: nothing to look up yet). */
export function requestOf(mode: FindMode, f: FindFields, asked: boolean): MatchRequest | null {
  if (mode === 'barcode') return barcodeReady(f.code, asked) ? { gtin: digitsOf(f.code) } : null;
  if (mode === 'brand') return f.brand.trim() && f.mpn.trim() ? { brand: f.brand.trim(), mpn: f.mpn.trim() } : null;
  return null;
}

const BLOCKERS: Record<string, MessageKey> = {
  'catalog.page-not-live': 'sc.match.blocker.pageNotLive',
  'offers.selling-approval-required': 'sc.match.blocker.approval',
  'catalog.brand-unavailable': 'sc.match.blocker.brand',
  'catalog.department-not-allowed': 'sc.match.blocker.department',
  'offers.offer-exists': 'sc.match.blocker.offerExists',
  'offers.listing-limit-reached': 'sc.match.blocker.limit',
  'catalog.wrong-match-open': 'sc.match.blocker.wrongOpen',
  'catalog.gtin-exists': 'sc.match.blocker.exists'
};
/** The seller's-words message for a blocker code; undefined for a code we have no words for (the server's English message is shown). */
export const blockerKey = (code: string): MessageKey | undefined => BLOCKERS[code];

/** Problem codes the BFF answers an offer with, and the message for each. */
const OFFER_PROBLEMS: Record<string, MessageKey> = {
  OFFER_EXISTS: 'sc.match.err.offerExists',
  SELLING_APPROVAL_REQUIRED: 'sc.match.err.approval',
  LISTING_LIMIT_REACHED: 'sc.match.err.limit',
  BRAND_UNAVAILABLE: 'sc.match.err.brand',
  NOT_FOUND: 'sc.match.err.notLive',
  PAGE_CONTENT_LOCKED: 'sc.match.err.locked'
};
export const offerProblemKey = (code: string | undefined): MessageKey | undefined => (code ? OFFER_PROBLEMS[code] : undefined);

/* ── The offer-only form ── */

export interface OfferForm {
  price: string;
  listPrice: string;
  shipping: string;
  qty: string;
  condition: OfferCondition;
  note: string;
  fulfilment: OfferFulfilment;
  handling: string;
  shipFromId: string;
  sku: string;
  pkg: { length: string; width: string; height: string; weight: string };
}

export const emptyOffer = (): OfferForm => ({ price: '', listPrice: '', shipping: '', qty: '', condition: 'NEW', note: '', fulfilment: 'SELLER', handling: '2', shipFromId: '', sku: '',
  pkg: { length: '', width: '', height: '', weight: '' } });

export const OFFER_FIELD = {
  price: 'of-price', listPrice: 'of-list-price', shipping: 'of-shipping', qty: 'of-qty', condition: 'of-condition', note: 'of-note', fulfilment: 'of-fulfilment', handling: 'of-handling',
  shipFrom: 'of-ship-from', sku: 'of-sku', pkg: 'of-pkg'
} as const;

const positive = (v: string): number | null => {
  const n = Number(v.trim());
  return v.trim() !== '' && Number.isFinite(n) && n > 0 ? n : null;
};
const whole = (v: string): number | null => (/^\d{1,6}$/.test(v.trim()) ? Number(v.trim()) : null);

/** Problems by field id, as message keys (empty: the offer can be sent). */
export function offerProblems(f: OfferForm): Record<string, MessageKey> {
  const p: Record<string, MessageKey> = {};
  const price = priceMinor(f.price);
  if (price === null) p[OFFER_FIELD.price] = 'sc.match.err.price';
  const list = f.listPrice.trim() ? priceMinor(f.listPrice) : undefined;
  if (list === null) p[OFFER_FIELD.listPrice] = 'sc.match.err.price';
  else if (list !== undefined && price !== null && list < price) p[OFFER_FIELD.listPrice] = 'sc.match.err.listPrice';
  if (f.shipping.trim() && !/^\d+(\.\d{1,2})?$/.test(f.shipping.replace(/[$,\s]/g, ''))) p[OFFER_FIELD.shipping] = 'sc.match.err.shipping';
  const qty = whole(f.qty);
  if (f.fulfilment === 'SELLER' && (qty === null || qty > 100_000)) p[OFFER_FIELD.qty] = 'sc.match.err.qty';
  if (f.condition !== 'NEW' && !f.note.trim()) p[OFFER_FIELD.note] = 'sc.match.err.note';
  if (f.fulfilment === 'AFROTRANSACT_DELIVERY' && !f.shipFromId) p[OFFER_FIELD.shipFrom] = 'sc.match.err.shipFrom';
  const days = whole(f.handling);
  if (days === null || days > 30) p[OFFER_FIELD.handling] = 'sc.match.err.handling';
  if (f.fulfilment === 'AFROTRANSACT') {
    const { length, width, height, weight } = f.pkg;
    if (![length, width, height, weight].every((v) => positive(v) !== null)) p[OFFER_FIELD.pkg] = 'sc.match.err.package';
  }
  return p;
}

/** The request for offers (call when offerProblems is empty). */
export function toOfferInput(atin: string, f: OfferForm): OfferOnlyInput {
  const price = priceMinor(f.price) ?? 0;
  const input: OfferOnlyInput = { atin, priceMinor: price, fulfilment: f.fulfilment, condition: f.condition, handlingDays: whole(f.handling) ?? 2 };
  const list = priceMinor(f.listPrice);
  if (list !== null) input.listPriceMinor = list;
  const ship = f.shipping.trim() ? Math.round(Number(f.shipping.replace(/[$,\s]/g, '')) * 100) : null;
  if (ship !== null && Number.isFinite(ship)) input.shippingMinor = ship;
  if (f.fulfilment === 'SELLER') input.quantity = whole(f.qty) ?? 0;
  if (f.condition !== 'NEW') input.conditionNote = f.note.trim();
  if (f.shipFromId && f.fulfilment !== 'AFROTRANSACT') input.shipFromLocationId = f.shipFromId;
  if (f.sku.trim()) input.sellerSku = f.sku.trim();
  if (f.fulfilment === 'AFROTRANSACT') {
    input.packaging = { lengthIn: Number(f.pkg.length), widthIn: Number(f.pkg.width), heightIn: Number(f.pkg.height), weightLb: Number(f.pkg.weight) };
  }
  return input;
}

/** The server's field names (offers, BFF) mapped to the form's field ids, so a refusal lands on the field it is about. */
export const serverOfferField = (field: string): string =>
  ({ priceMinor: OFFER_FIELD.price, listPriceMinor: OFFER_FIELD.listPrice, shippingMinor: OFFER_FIELD.shipping, quantity: OFFER_FIELD.qty, condition: OFFER_FIELD.condition,
    conditionNote: OFFER_FIELD.note, handlingDays: OFFER_FIELD.handling, shipFromLocationId: OFFER_FIELD.shipFrom, sellerSku: OFFER_FIELD.sku, packaging: OFFER_FIELD.pkg,
    fulfilment: OFFER_FIELD.fulfilment })[field] ?? OFFER_FIELD.price;

export { OFFER_CONDITIONS };
