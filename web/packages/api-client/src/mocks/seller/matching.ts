/* Catalog matching, offers on an existing page, change requests, image verdicts and Improve your image tasks in the mock seller
   BFF (docs/engineering/bulk-upload-and-images.md, sections 3 and 6). Mirrors catalog's POST /v1/catalog/matches and offers'
   POST /v1/offers for a handful of rules: the barcode is cleaned and checked, a page created by one organisation is found by
   another, an offer never carries page content, a page that is not live cannot be sold, a gated brand needs approval, and an
   offer is TAX_HELD while the seller's own tax decision is missing. State lives on the mock database, per organisation. */
import {
  type CatalogMatch,
  type ChangeRequest,
  type ChangeRequestInput,
  type ImageCheck,
  type ImageTask,
  type MatchRequest,
  type MatchVariant,
  OFFER_CONDITIONS,
  type OfferOnlyInput,
  type OfferOnlyResult,
  type ProductImageSlot
} from '../../seller-matching';
import { MOCK_FASHION_ORG, MOCK_FOOD_ORG } from './departments';

export class MatchingMockError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    detail: string,
    readonly errors?: Record<string, string>
  ) {
    super(detail);
  }
}

export const CONTENT_LOCKED = 'That detail belongs to the product page. Suggest a change instead.';
export const TAX_HELD_MESSAGE = 'We are checking the tax details for this item.';

interface MockChild {
  atin: string;
  gtin14: string | null;
  options: Record<string, string>;
}
export interface MockPage {
  parentAtin: string;
  title: string;
  brand: string;
  mpn: string | null;
  category: string;
  imageUrl: string;
  ownerOrg: string;
  status: 'LIVE' | 'IN_REVIEW';
  /** The brand needs a selling approval the organisation does not hold. */
  gated: boolean;
  /** A seller's own tax decision is not on file for this item: an offer on it is TAX_HELD. */
  taxHeld: boolean;
  children: MockChild[];
}
interface MockOffer {
  id: string;
  atin: string;
  orgId: string;
  condition: string;
  status: 'ACTIVE' | 'TAX_HELD';
}
export interface MockMatchingState {
  pages: MockPage[];
  offers: MockOffer[];
  requests: (ChangeRequest & { orgId: string })[];
  /** Variants other organisations already sell (the seller count of a card). */
  seq: number;
}

const DIGITS = /\D/g;

/** The check digit of a GTIN body (every digit but the last). */
export function checkDigit(body: string): number {
  let sum = 0;
  for (let i = 0; i < body.length; i++) {
    const d = Number(body[body.length - 1 - i]);
    sum += i % 2 === 0 ? d * 3 : d;
  }
  return (10 - (sum % 10)) % 10;
}
export const gtinWith = (body: string): string => `${body}${checkDigit(body)}`;
const checkOk = (digits: string): boolean => checkDigit(digits.slice(0, -1)) === Number(digits.slice(-1));
const gtin14Of = (digits: string): string => digits.padStart(14, '0');

/** Store-use and coupon prefixes are not product barcodes (GS1 restricted circulation). */
const restricted = (digits: string): boolean => /^(02|04|2\d|98[1-4]|99)/.test(digits);

const SHEA = gtinWith('012345678905'.slice(0, 11));
export const MOCK_GTIN = {
  /** A live page with other sellers. */
  live: SHEA,
  /** A live page whose offer waits for the seller's tax decision. */
  taxHeld: gtinWith('590123412345'),
  /** A live page of a gated brand: needs a selling approval. */
  gated: gtinWith('019283746550'),
  /** A page that is not live yet. */
  pending: gtinWith('070000000012'),
  /** A page with two sizes: the second child. */
  sizeA: gtinWith('600123456780'),
  sizeB: gtinWith('600123456797'),
  /** The seeded Ofada rice page (the form's existing GTIN example). */
  rice: '4006381333931'
} as const;

function seedPages(): MockPage[] {
  return [
    { parentAtin: 'ATRICE0001', title: 'Fine Ofada Rice, 5 lb', brand: 'Mama Ngozi', mpn: 'OF-5', category: 'Groceries > Rice and grains', imageUrl: 'https://img.afrotransact.dev/seed-rice.webp',
      ownerOrg: MOCK_FOOD_ORG, status: 'LIVE', gated: false, taxHeld: false, children: [{ atin: 'ATCAT00042', gtin14: gtin14Of(MOCK_GTIN.rice), options: { 'Net weight': '5 lb' } }] },
    { parentAtin: 'ATSHEA0001', title: 'Raw Shea Butter, 8 oz', brand: 'Kente Naturals', mpn: 'SB-8', category: 'Beauty > Skin care > Body butter', imageUrl: 'https://img.afrotransact.dev/seed-shea.webp',
      ownerOrg: MOCK_FOOD_ORG, status: 'LIVE', gated: false, taxHeld: false, children: [{ atin: 'ATSHEAV001', gtin14: gtin14Of(MOCK_GTIN.live), options: { Size: '8 oz' } }] },
    { parentAtin: 'ATBSOAP001', title: 'African Black Soap, 8 oz', brand: 'Accra Naturals', mpn: 'BS-8', category: 'Beauty > Bath and body > Soap', imageUrl: 'https://img.afrotransact.dev/seed-soap.webp',
      ownerOrg: MOCK_FOOD_ORG, status: 'LIVE', gated: false, taxHeld: true, children: [{ atin: 'ATBSOAPV01', gtin14: gtin14Of(MOCK_GTIN.taxHeld), options: { Size: '8 oz' } }] },
    { parentAtin: 'ATNIKE0001', title: 'Air Zoom Runner', brand: 'Nike', mpn: 'AZ-100', category: 'Fashion > Shoes > Sneakers', imageUrl: 'https://img.afrotransact.dev/seed-shoe.webp',
      ownerOrg: MOCK_FASHION_ORG, status: 'LIVE', gated: true, taxHeld: false, children: [{ atin: 'ATNIKEV001', gtin14: gtin14Of(MOCK_GTIN.gated), options: { Size: '10' } }] },
    { parentAtin: 'ATPENDNG01', title: 'Ankara Fabric, 6 yards', brand: 'Adire Atelier', mpn: null, category: 'Fashion > Fabric', imageUrl: 'https://img.afrotransact.dev/seed-fabric.webp',
      ownerOrg: MOCK_FASHION_ORG, status: 'IN_REVIEW', gated: false, taxHeld: false, children: [{ atin: 'ATPENDNGV1', gtin14: gtin14Of(MOCK_GTIN.pending), options: {} }] },
    { parentAtin: 'ATKAFTAN01', title: 'Linen Kaftan', brand: 'Adire Atelier', mpn: 'KF-1', category: 'Fashion > Women > Tops', imageUrl: 'https://img.afrotransact.dev/seed-kaftan.webp',
      ownerOrg: MOCK_FASHION_ORG, status: 'LIVE', gated: false, taxHeld: false,
      children: [{ atin: 'ATKAFTANS1', gtin14: gtin14Of(MOCK_GTIN.sizeA), options: { Size: 'Small' } }, { atin: 'ATKAFTANL1', gtin14: gtin14Of(MOCK_GTIN.sizeB), options: { Size: 'Large' } }] }
  ];
}

/** Other organisations' offers on the seeded pages (the number of sellers on a card). */
function seedOffers(): MockOffer[] {
  return [
    { id: 'offer-seed-1', atin: 'ATSHEAV001', orgId: MOCK_FOOD_ORG, condition: 'NEW', status: 'ACTIVE' },
    { id: 'offer-seed-2', atin: 'ATSHEAV001', orgId: 'org-someone-else', condition: 'NEW', status: 'ACTIVE' },
    { id: 'offer-seed-3', atin: 'ATCAT00042', orgId: MOCK_FOOD_ORG, condition: 'NEW', status: 'ACTIVE' }
  ];
}

export function freshMatching(): MockMatchingState {
  return { pages: seedPages(), offers: seedOffers(), requests: [], seq: 0 };
}

/** What the mock database holds for matching; created on first use so older fixtures keep working. */
export interface MatchingHost {
  matching?: MockMatchingState;
}
const stateOf = (db: MatchingHost): MockMatchingState => (db.matching ??= freshMatching());

const normalise = (s: string): string => s.toUpperCase().replace(/[^A-Z0-9]/g, '');

function findChild(st: MockMatchingState, atin: string): { page: MockPage; child: MockChild } | undefined {
  for (const page of st.pages) {
    const child = page.children.find((c) => c.atin === atin);
    if (child) return { page, child };
  }
  return undefined;
}

const sellerCount = (st: MockMatchingState, atin: string): number => st.offers.filter((o) => o.atin === atin).length;

/** The barcode step: MATCH, NO_MATCH, INVALID or RESTRICTED, as catalog answers it. */
export function matchCatalog(db: MatchingHost, orgId: string, req: MatchRequest): CatalogMatch {
  const st = stateOf(db);
  const gtin = (req.gtin ?? '').trim();
  const brand = (req.brand ?? '').trim();
  const mpn = (req.mpn ?? '').trim();
  if (!gtin && (!brand || !mpn)) throw new MatchingMockError(422, 'VALIDATION_FAILED', 'Enter the barcode, or the brand and the part number', { gtin: 'Enter the barcode, or the brand and the part number' });
  const none = { variants: [] as MatchVariant[], canOffer: false, blockers: [], canCreate: false, createBlockers: [] };
  let found: { page: MockPage; child: MockChild | null; on: 'GTIN' | 'BRAND_MPN' } | undefined;
  let digits: string | null = null;
  if (gtin) {
    digits = gtin.replace(/[\s-]/g, '').replace(DIGITS, '');
    if (!/^(\d{8}|\d{12,14})$/.test(digits)) {
      return { result: 'INVALID', digits, ...none, message: 'Barcodes have 8, 12, 13 or 14 digits.' };
    }
    if (!checkOk(digits)) {
      const fixed = gtinWith(digits.slice(0, -1));
      return { result: 'INVALID', digits, ...none, suggestion: fixed, message: `That barcode does not look right. Did you mean ${fixed}?` };
    }
    if (restricted(digits)) return { result: 'RESTRICTED', digits, ...none, message: 'This number is for store use or coupons, not a product barcode.' };
    const key = gtin14Of(digits);
    for (const page of st.pages) {
      const child = page.children.find((c) => c.gtin14 === key);
      if (child) found = { page, child, on: 'GTIN' };
    }
  } else {
    const page = st.pages.find((p) => p.mpn && normalise(p.brand) === normalise(brand) && normalise(p.mpn) === normalise(mpn));
    if (page) found = { page, child: page.children.length === 1 ? (page.children[0] ?? null) : null, on: 'BRAND_MPN' };
  }
  if (!found) {
    return { result: 'NO_MATCH', digits, ...none, canCreate: true };
  }
  const { page, child, on } = found;
  const blockers = [];
  if (page.status !== 'LIVE') blockers.push({ code: 'catalog.page-not-live', message: 'This product page is not live yet. Try again once it is approved.' });
  if (page.gated) blockers.push({ code: 'offers.selling-approval-required', message: 'You need approval to sell this brand. Apply under Listings.' });
  const open = st.requests.some((r) => r.type === 'WRONG_MATCH' && r.status === 'PENDING' && r.atin === (child?.atin ?? page.parentAtin) && r.orgId === orgId);
  const own = child ? st.offers.some((o) => o.atin === child.atin && o.orgId === orgId) : false;
  if (own) blockers.push({ code: 'offers.offer-exists', message: 'You already sell this item. Change your offer under Listings.' });
  return {
    result: 'MATCH', matchedOn: on, digits, atin: child?.atin ?? null, parentAtin: page.parentAtin, productStatus: page.status, title: page.title, brand: page.brand,
    image: { url: page.imageUrl, width: 1600, height: 1600 }, category: page.category, variantOptions: child?.options ?? {},
    variants: page.children.map((c) => ({ atin: c.atin, options: c.options })), sellerCount: child ? sellerCount(st, child.atin) : null,
    canOffer: blockers.length === 0, blockers, canCreate: false,
    createBlockers: open
      ? [{ code: 'catalog.wrong-match-open', message: 'We are checking this barcode. We will tell you within 2 business days.' }]
      : [{ code: 'catalog.gtin-exists', message: 'A page for this barcode already exists. Sell it instead of creating a new one.' }]
  };
}

/** Registers the page a seller creates, so another seller finds it by its barcode (first creator stays the owner). */
export function registerPage(db: MatchingHost, orgId: string, p: { title: string; brand: string; gtins: { gtin: string; options: Record<string, string> }[]; imageUrl: string | null; category: string }): void {
  const st = stateOf(db);
  const children: MockChild[] = [];
  p.gtins.forEach((g, i) => {
    const digits = g.gtin.replace(DIGITS, '');
    if (!/^(\d{8}|\d{12,14})$/.test(digits) || !checkOk(digits)) return;
    const key = gtin14Of(digits);
    if (st.pages.some((x) => x.children.some((c) => c.gtin14 === key))) return;
    st.seq += 1;
    children.push({ atin: `ATNEW${String(st.seq).padStart(3, '0')}V${i}`.slice(0, 10), gtin14: key, options: g.options });
  });
  if (!children.length) return;
  st.pages.push({ parentAtin: `ATNEW${String(st.seq).padStart(3, '0')}`.slice(0, 10).padEnd(10, '0'), title: p.title, brand: p.brand, mpn: null, category: p.category, imageUrl: p.imageUrl ?? '', ownerOrg: orgId,
    status: 'IN_REVIEW', gated: false, taxHeld: false, children });
}

/** Staff approve a page (tests and demos): from then on others may sell it. */
export function approveMockPage(db: MatchingHost, gtin: string): boolean {
  const key = gtin14Of(gtin.replace(DIGITS, ''));
  const page = stateOf(db).pages.find((p) => p.children.some((c) => c.gtin14 === key));
  if (!page) return false;
  page.status = 'LIVE';
  return true;
}

const PAGE_CONTENT = ['title', 'description', 'bullets', 'attributes', 'attrs', 'brand', 'category', 'categoryId', 'categoryPath', 'productType', 'productTypeCode', 'gtin', 'gtinExempt',
  'variationTheme', 'variants', 'options', 'images', 'image', 'taxFacts', 'facts', 'keywords', 'sizeChartId', 'mpn'];

/** Offers terms only: the same answers as offers' POST /v1/offers. */
export function createOffer(db: MatchingHost, orgId: string, raw: Record<string, unknown>): OfferOnlyResult {
  const st = stateOf(db);
  const locked: Record<string, string> = {};
  Object.keys(raw).forEach((k) => {
    if (PAGE_CONTENT.includes(k)) locked[k] = CONTENT_LOCKED;
  });
  if (Object.keys(locked).length) throw new MatchingMockError(422, 'PAGE_CONTENT_LOCKED', CONTENT_LOCKED, locked);
  const input = raw as unknown as OfferOnlyInput;
  const errors: Record<string, string> = {};
  if (!Number.isInteger(input.priceMinor) || input.priceMinor < 1) errors.priceMinor = 'Enter a price above zero';
  if (input.listPriceMinor !== undefined && input.listPriceMinor < input.priceMinor) errors.listPriceMinor = 'The list price can’t be lower than the price';
  const condition = input.condition ?? 'NEW';
  if (!(OFFER_CONDITIONS as readonly string[]).includes(condition)) errors.condition = 'Choose a condition from the list';
  else if (condition !== 'NEW' && !(input.conditionNote ?? '').trim()) errors.conditionNote = 'Describe the condition';
  if (input.fulfilment === 'SELLER' && input.quantity === undefined) errors.quantity = 'Enter how many you have';
  if (input.fulfilment === 'AFROTRANSACT_DELIVERY' && !input.shipFromLocationId) errors.shipFromLocationId = 'Choose the store AfroTransact picks up from';
  if (input.packaging && input.fulfilment !== 'AFROTRANSACT') errors.packaging = 'The package is only for Fulfilled by AfroTransact';
  if (Object.keys(errors).length) throw new MatchingMockError(422, 'VALIDATION_FAILED', 'One or more fields are invalid.', errors);
  const hit = findChild(st, input.atin);
  if (!hit || hit.page.status !== 'LIVE') throw new MatchingMockError(404, 'NOT_FOUND', 'This product page is not live yet.');
  if (hit.page.gated) throw new MatchingMockError(422, 'SELLING_APPROVAL_REQUIRED', 'You need approval to sell this brand. Apply under Listings.');
  if (st.offers.some((o) => o.atin === input.atin && o.orgId === orgId && o.condition === condition)) {
    throw new MatchingMockError(409, 'OFFER_EXISTS', 'You already sell this item in that condition. Change your offer under Listings.');
  }
  st.seq += 1;
  const status = hit.page.taxHeld ? 'TAX_HELD' : 'ACTIVE';
  const offer: MockOffer = { id: `offer-${st.seq}`, atin: input.atin, orgId, condition, status };
  st.offers.push(offer);
  return { offerId: offer.id, atin: input.atin, sellerSku: input.sellerSku?.trim() || `${input.atin}-${condition.replace(/_/g, '')}`, status,
    heldReason: status === 'TAX_HELD' ? 'TAX_DECISION_MISSING' : null, message: status === 'TAX_HELD' ? TAX_HELD_MESSAGE : 'Your offer is live.' };
}

/** Suggest a change, wrong product, duplicate. */
export function fileChange(db: MatchingHost, orgId: string, atin: string, input: ChangeRequestInput, now: Date): ChangeRequest {
  const st = stateOf(db);
  const hit = findChild(st, atin) ?? (st.pages.find((p) => p.parentAtin === atin) ? { page: st.pages.find((p) => p.parentAtin === atin) as MockPage, child: null } : undefined);
  if (!hit) throw new MatchingMockError(404, 'NOT_FOUND', 'We couldn’t find that.');
  const needsOffer = input.type !== 'WRONG_MATCH' && input.type !== 'DUPLICATE_PAGE';
  const sells = st.offers.some((o) => o.orgId === orgId && hit.page.children.some((c) => c.atin === o.atin));
  if (needsOffer && !sells) throw new MatchingMockError(403, 'NOT_YOUR_OFFER', 'You can suggest a change once you sell this item.');
  const payload = input.payload ?? {};
  const text = (k: string) => (typeof payload[k] === 'string' ? (payload[k] as string).trim() : '');
  const errors: Record<string, string> = {};
  if (['TITLE', 'DESCRIPTION', 'BRAND'].includes(input.type) && !text('value')) errors['payload.value'] = 'Enter the new text';
  if (input.type === 'DUPLICATE_PAGE' && !/^AT[0-9A-Z]{8}$/.test(text('duplicateOfAtin').toUpperCase())) errors['payload.duplicateOfAtin'] = 'Enter the product number of the other page';
  if (Object.keys(errors).length) throw new MatchingMockError(422, 'VALIDATION_FAILED', 'One or more fields are invalid.', errors);
  const key = hit.child?.atin ?? hit.page.parentAtin;
  if (st.requests.some((r) => r.orgId === orgId && r.atin === key && r.type === input.type && (r.field ?? '') === (input.field ?? '') && r.status === 'PENDING')) {
    throw new MatchingMockError(409, 'CHANGE_REQUEST_EXISTS', 'You already sent this. We will tell you when we have looked at it.');
  }
  st.seq += 1;
  const req = { id: `00000000-0000-4000-8000-${String(st.seq).padStart(12, '0')}`, atin: key, title: hit.page.title, type: input.type, field: input.field ?? null, payload: input.payload ?? null,
    note: input.note ?? null, status: 'PENDING' as const, requestedAt: now.toISOString(), slaDueAt: new Date(now.getTime() + 2 * 86_400_000).toISOString(), decidedAt: null, reason: null, resolution: null, orgId };
  st.requests.unshift(req);
  return stripOrg(req);
}

const stripOrg = ({ orgId: _o, ...r }: ChangeRequest & { orgId: string }): ChangeRequest => r;

export const changeRequestsOf = (db: MatchingHost, orgId: string): ChangeRequest[] => stateOf(db).requests.filter((r) => r.orgId === orgId).map(stripOrg);

export function withdrawChange(db: MatchingHost, orgId: string, id: string): ChangeRequest {
  const r = stateOf(db).requests.find((x) => x.id === id && x.orgId === orgId);
  if (!r) throw new MatchingMockError(404, 'NOT_FOUND', 'We couldn’t find that.');
  if (r.status !== 'PENDING') throw new MatchingMockError(409, 'CHANGE_REQUEST_DECIDED', 'This request was already decided.');
  r.status = 'WITHDRAWN';
  return stripOrg(r);
}

/** Staff decide a request (tests and demos). */
export function decideMockChange(db: MatchingHost, id: string, applied: boolean, reason: string, now: Date): void {
  const r = stateOf(db).requests.find((x) => x.id === id);
  if (!r) return;
  r.status = applied ? 'APPLIED' : 'REJECTED';
  r.reason = reason;
  r.decidedAt = now.toISOString();
}

/** Improve your image tasks of an organisation's live listings (seeded from the first two live listings it has). */
export function imageTasksOf(listings: { productId: string; atin: string | null; title: string; status: string }[], now: Date): ImageTask[] {
  const live = listings.filter((l) => l.status === 'LIVE');
  const out: ImageTask[] = [];
  const fail = live[0];
  const improve = live[1];
  if (fail) {
    const since = new Date(now.getTime() - 12 * 86_400_000);
    const after = new Date(since.getTime() + 60 * 86_400_000);
    out.push({ productId: fail.productId, atin: fail.atin, title: fail.title, state: 'FAIL', hidden: false, failingSince: since.toISOString(), hideAfter: after.toISOString(), daysLeft: 48,
      reasons: [{ code: 'NOT_WHITE', level: 'REJECT', message: 'The main photo needs a plain white background.' }, { code: 'MAIN_TOO_SMALL', level: 'REJECT', message: 'This photo is too small.' }] });
  }
  if (improve) out.push({ productId: improve.productId, atin: improve.atin, title: improve.title, state: 'IMPROVE', hidden: false, reasons: [{ code: 'FILL_OK_NEAR', level: 'WARN', message: 'The product could fill more of the photo.' }] });
  return out;
}

/* ── Image rules (media, S388): the verdict of an uploaded file, read from its name so tests and demos can pick one ── */

export interface MockVerdict {
  width: number;
  height: number;
  verdict: 'PASS' | 'WARN' | 'HOLD' | 'REJECT';
  checks: ImageCheck[];
}
const check = (code: string, level: ImageCheck['level'], message: string, params: Record<string, unknown> = {}): ImageCheck => ({ code, level, params, message });

/** `small`, `white`, `underfill`, `fill`, `border`, `blur`, `wide`, `low`, `held`, `dup` in the file name pick the finding; anything else passes. */
export function verdictForFile(fileName: string, slot: ProductImageSlot): MockVerdict {
  const n = fileName.toLowerCase();
  const main = slot === 'MAIN';
  const checks: ImageCheck[] = [];
  let width = 1600;
  let height = 1600;
  if (n.includes('small')) {
    width = 640;
    height = 480;
    checks.push(main ? check('MAIN_TOO_SMALL', 'REJECT', `This photo is too small. Yours is ${width} by ${height}.`, { w: width, h: height, min: 1000 }) : check('TOO_SMALL', 'REJECT', 'This photo is too small.', { w: width, h: height, min: 500 }));
  } else if (n.includes('low') && !main) {
    width = 800;
    height = 800;
    checks.push(check('LOW_RES', 'WARN', 'This photo is under 1000 pixels.', { px: 1000 }));
  }
  if (main && n.includes('white')) checks.push(check('NOT_WHITE', 'REJECT', 'The main photo needs a plain white background.'));
  if (main && n.includes('underfill')) checks.push(check('FILL_LOW', 'REJECT', 'The product is too small in the photo.', { fill: 85, actual: 52 }));
  else if (main && n.includes('fill')) checks.push(check('FILL_OK_NEAR', 'WARN', 'The product could fill more of the photo.', { fill: 85, actual: 80 }));
  if (main && n.includes('border')) checks.push(check('BORDER', 'REJECT', 'Remove the border or frame around the photo.'));
  if (n.includes('blur')) checks.push(check('BLUR', 'WARN', 'This photo looks blurry.'));
  if (n.includes('wide')) checks.push(check('ASPECT', 'WARN', 'This photo is very wide or very tall.'));
  if (n.includes('held')) checks.push(check('HELD_TEXT', 'HOLD', 'We are checking this photo for text, logos or watermarks.'));
  if (n.includes('dup')) checks.push(check('HELD_DUPLICATE', 'HOLD', 'We are checking this photo because it looks like one already used on AfroTransact.'));
  const rank = { INFO: 0, WARN: 1, HOLD: 2, REJECT: 3 } as const;
  const worst = checks.reduce<number>((a, c) => Math.max(a, rank[c.level]), 0);
  return { width, height, verdict: (['PASS', 'WARN', 'HOLD', 'REJECT'] as const)[worst] ?? 'PASS', checks };
}
