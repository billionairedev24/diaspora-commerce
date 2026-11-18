import { type CatalogMatch, type ChangeRequest, type MatchCandidate, OFFER_CONDITIONS, type OfferCondition, type OfferFulfilment, type OfferOnlyInput } from '@afrotransact/api-client';

import { type MessageKey } from '@/i18n/t';

import { priceMinor } from '../form-model';

/* Pure rules of "Add a product" (S382): when the search and the barcode lookup run, why a seller may not sell a matched page, and the
   offer-only form's checks. The server checks everything again (catalog and offers); these keep the answers fast and in the seller's words. */

/** The name is searched from this many letters (catalog says the same: tooShort). */
export const MIN_SEARCH_LETTERS = 3;
/** The search follows what was typed after this pause, so every keystroke is not a request. */
export const SEARCH_DEBOUNCE_MS = 400;

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

/** What the seller typed in the search box, tidied (one space between words); null while it is too short to search. */
export function searchTextOf(raw: string): string | null {
  const text = raw.trim().replace(/\s+/g, ' ');
  return text.replace(/\s/g, '').length >= MIN_SEARCH_LETTERS ? text.slice(0, 200) : null;
}

/** "Size: Large · Colour: Red": the options of one child, in the seller's words (never an id). */
export const optionsText = (options: Record<string, string> | null | undefined): string =>
  Object.entries(options ?? {}).filter(([, v]) => v).map(([k, v]) => `${k}: ${v}`).join(' · ');

/** The size the seller is asked to confirm with one tap: the page's size in words, else the chosen child's options; null when the page states none. */
export function sizeToConfirm(c: MatchCandidate, atin: string): string | null {
  if (c.sizeLabel) return c.sizeLabel;
  return optionsText(c.variants.find((v) => v.atin === atin)?.options) || null;
}

/** A found page as the price-and-stock form and the reports read it (the same shape the barcode shortcut answers with). */
export function candidateAsMatch(c: MatchCandidate, atin: string): CatalogMatch {
  const child = c.variants.find((v) => v.atin === atin);
  return { result: 'MATCH', atin, parentAtin: c.parentAtin, title: c.title, brand: c.brand ?? null, image: c.image ?? null, category: c.category ?? null, variantOptions: child?.options ?? {},
    variants: c.variants, sellerCount: c.sellerCount ?? null, canOffer: c.canOffer, blockers: c.blockers, canCreate: false, createBlockers: [], allowedConditions: c.allowedConditions };
}

/** The conditions the page's category allows, as catalog's listing rules say: NEW alone when it says nothing (the condition is then not asked and the server uses New). */
export const conditionsAllowed = (match: { allowedConditions?: OfferCondition[] | undefined }): OfferCondition[] =>
  match.allowedConditions?.length ? match.allowedConditions : ['NEW'];

/** The condition field is shown only where used or renewed items make sense for the category. */
export const asksCondition = (match: { allowedConditions?: OfferCondition[] | undefined }): boolean => conditionsAllowed(match).length > 1;

/** A variant request the seller can still act on: staff applied it and the variant exists, so price and stock are next. */
export const readyToSell = (r: ChangeRequest): boolean => r.type === 'NEW_VARIANT' && r.status === 'APPLIED' && Boolean(r.resultAtin);

/** The size a variant request asked for, in the seller's words ("1 kg"); undefined when the request names none. */
export function requestedSize(r: ChangeRequest): string | undefined {
  const options = (r.payload as { options?: Record<string, unknown> } | null | undefined)?.options;
  const first = options ? Object.values(options)[0] : undefined;
  return typeof first === 'string' && first ? first : undefined;
}

/** The page a request is about, as the price-and-stock form reads it: only its title and the variant that now exists. */
export function changeAsMatch(r: ChangeRequest): CatalogMatch {
  const size = requestedSize(r);
  const options: Record<string, string> = size ? { Size: size } : {};
  const atin = r.resultAtin ?? '';
  return { result: 'MATCH', atin, parentAtin: r.atin, title: r.title ?? '', brand: null, image: null, category: null, variantOptions: options, variants: [{ atin, options }], sellerCount: null, canOffer: true,
    blockers: [], canCreate: false, createBlockers: [] };
}

/** Why a size request was refused, in the seller's words (the server's problem codes). */
const SIZE_PROBLEMS: Record<string, MessageKey> = {
  SIZE_INVALID: 'sc.match.size.err.invalid',
  SIZE_PAGE_UNKNOWN: 'sc.match.size.err.pageUnknown',
  SIZE_NOT_ADDABLE: 'sc.match.size.err.notAddable',
  SIZE_REQUEST_BLOCKED: 'sc.match.size.err.blocked',
  RATE_LIMITED: 'sc.match.size.err.limit',
  CHOICE_NOT_SHOWN: 'sc.match.size.err.notShown'
};
export const sizeProblemKey = (code: string | undefined): MessageKey | undefined => (code ? SIZE_PROBLEMS[code] : undefined);
/** The refusals after which the right next step is to add the product as new. */
export const sizeNeedsNewProduct = (code: string | undefined): boolean => code === 'SIZE_NOT_ADDABLE' || code === 'SIZE_PAGE_UNKNOWN';

const BLOCKERS: Record<string, MessageKey> = {
  'catalog.page-not-live': 'sc.match.blocker.pageNotLive',
  'offers.selling-approval-required': 'sc.match.blocker.approval',
  'catalog.brand-unavailable': 'sc.match.blocker.brand',
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

/** What is missing at the chosen location, by the gateway's problem code: the words, and where it is fixed (Pickup settings, or the locations list for Fulfilled by AfroTransact). */
const LOCATION_PROBLEMS: Record<string, { message: MessageKey; fix: MessageKey; href: string }> = {
  PICKUP_NOT_ON: { message: 'sc.match.err.pickupNotOn', fix: 'sc.match.fix.pickup', href: '/settings/pickup' },
  PICKUP_HOURS_MISSING: { message: 'sc.match.err.pickupHours', fix: 'sc.match.fix.pickupHours', href: '/settings/pickup' },
  FBA_LOCATION_NOT_ON: { message: 'sc.match.err.fbaNotOn', fix: 'sc.match.fix.fba', href: '/settings/locations' },
  OFFER_LOCATION_UNKNOWN: { message: 'sc.match.err.locationUnknown', fix: 'sc.match.fix.locations', href: '/settings/locations' }
};
export const locationProblem = (code: string | undefined): { message: MessageKey; fix: MessageKey; href: string } | undefined => (code ? LOCATION_PROBLEMS[code] : undefined);

/** The location the form sends: the one chosen, else the seller's default (shown selected, so it is also what is sent), else the only one. */
export function chosenLocation(chosen: string, locations: { id: string; isDefault: boolean }[]): string {
  if (chosen && locations.some((l) => l.id === chosen)) return chosen;
  return (locations.find((l) => l.isDefault) ?? (locations.length === 1 ? locations[0] : undefined))?.id ?? '';
}

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
  pkg: { length: string; width: string; height: string; weight: string };
}

export const emptyOffer = (): OfferForm => ({ price: '', listPrice: '', shipping: '', qty: '', condition: 'NEW', note: '', fulfilment: 'SELLER', handling: '2', shipFromId: '',
  pkg: { length: '', width: '', height: '', weight: '' } });

export const OFFER_FIELD = {
  price: 'of-price', listPrice: 'of-list-price', shipping: 'of-shipping', qty: 'of-qty', condition: 'of-condition', note: 'of-note', fulfilment: 'of-fulfilment', handling: 'of-handling',
  shipFrom: 'of-ship-from', pkg: 'of-pkg'
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
  if (f.fulfilment === 'SELLER' && !f.shipFromId) p[OFFER_FIELD.shipFrom] = 'sc.match.err.pickupLocation';
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
  if (f.fulfilment === 'AFROTRANSACT') {
    input.packaging = { lengthIn: Number(f.pkg.length), widthIn: Number(f.pkg.width), heightIn: Number(f.pkg.height), weightLb: Number(f.pkg.weight) };
  }
  return input;
}

/** The server's field names (offers, BFF) mapped to the form's field ids, so a refusal lands on the field it is about. A name the form does not have is not put on the price: it is undefined (the form shows it on its own). */
export const serverOfferField = (field: string): string | undefined =>
  ({ priceMinor: OFFER_FIELD.price, listPriceMinor: OFFER_FIELD.listPrice, shippingMinor: OFFER_FIELD.shipping, quantity: OFFER_FIELD.qty, condition: OFFER_FIELD.condition,
    conditionNote: OFFER_FIELD.note, handlingDays: OFFER_FIELD.handling, shipFromLocationId: OFFER_FIELD.shipFrom, shipFrom: OFFER_FIELD.shipFrom, 'shipFrom.locationId': OFFER_FIELD.shipFrom,
    packaging: OFFER_FIELD.pkg, fulfilment: OFFER_FIELD.fulfilment } as Record<string, string>)[field];

export { OFFER_CONDITIONS };
