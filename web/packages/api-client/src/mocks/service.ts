/* Mock BFF domain logic: product summaries, product detail with buy box, search with facets,
   typeahead, cart composition (one package per seller) and stock-capped cart mutations.
   The rules mirror what the real services enforce so the storefront behaves the same against both. */
import { type Cart, type CartPackage, MAX_LINE_QUANTITY } from '../schemas/cart';
import { LOW_STOCK_THRESHOLD, type Offer, type ProductDetail, type ProductSummary, type StockStatus } from '../schemas/catalog';
import { type FacetBucket } from '../schemas/common';
import { type Home } from '../schemas/home';
import { type DeliveryLocation } from '../schemas/location';
import { PRICE_BUCKETS, type SearchPage, type SearchQuery, type Suggestions } from '../schemas/search';
import { getDb, type MockOffer, type MockSession, nextId } from './db';
import { deliveryOptions, deliveryPromise, etaDays, FREE_SHIPPING_THRESHOLD_MINOR, locationForZip, metroOf, nearestFc, SELLER_SHIPPING_MINOR } from './delivery';
import { atinOf,DEPARTMENTS, type FixtureProduct, PLATFORM_SELLER } from './fixtures/catalog';
import { PLACEMENTS, SPONSORED_SEARCH } from './fixtures/content';
import { completePhrases, matchScore } from './matching';
import { fixturePreorderEligible, withPreorderPackage } from './preorders';
import { PROVIDERS, SERVICES } from './shopper-state';

export class MockProblem extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly title: string,
    detail: string,
    readonly extra: Record<string, unknown> = {}
  ) {
    super(detail);
  }
}

const CURRENCY = 'USD';

/** Stable product thumb for mocks (matches seller listing placeholders). */
export function mockProductImageUrl(atin: string): string {
  return `https://img.afrotransact.dev/products/${atin}.webp`;
}

/** Catalog product-type codes used by tax-returns' category-by-product-type mapping. */
const GROCERY_PRODUCT_TYPES = new Set(['fresh-produce', 'pantry', 'spices', 'grains', 'frozen-food', 'meat-seafood']);

export function fixtureProductType(p: FixtureProduct): string {
  if (p.cat !== 'Groceries') {
    const byDept: Record<string, string> = {
      Fashion: 'dress',
      Beauty: 'beauty',
      Books: 'book',
      Party: 'apparel',
      Health: 'supplements',
      'Home & Art': 'home-decor'
    };
    return byDept[p.cat] ?? 'general';
  }
  const n = p.name.toLowerCase();
  if (/spice|seasoning|berbere|xawaash|jerk|epis|shito|jollof/.test(n)) return 'spices';
  if (/rice|flour|teff|grain|ofada|basmati/.test(n)) return 'grains';
  return 'pantry';
}

export function isGroceryProductType(productType: string | undefined): boolean {
  return productType !== undefined && GROCERY_PRODUCT_TYPES.has(productType);
}

export function stockStatus(qty: number): StockStatus {
  return qty <= 0 ? 'OUT_OF_STOCK' : qty <= LOW_STOCK_THRESHOLD ? 'LOW_STOCK' : 'IN_STOCK';
}

export function sessionLocation(s: MockSession): DeliveryLocation {
  return locationForZip(s.zip) ?? { zip: s.zip, city: 'Houston', state: 'TX', label: `Houston ${s.zip}`, serviceable: true };
}

/** Cheapest in-stock offer, counting seller shipping like the prototype; falls back to the first offer. */
export function buyBox(offers: MockOffer[]): MockOffer {
  const ranked = offers
    .filter((o) => o.stock > 0)
    .sort((a, b) => a.priceMinor + (a.fulfilment === 'AFROTRANSACT' ? 0 : SELLER_SHIPPING_MINOR) - (b.priceMinor + (b.fulfilment === 'AFROTRANSACT' ? 0 : SELLER_SHIPPING_MINOR)));
  const first = ranked[0] ?? offers[0];
  if (!first) throw new Error('Product without offers');
  return first;
}

function toOffer(o: MockOffer, metro: string, now: Date): Offer {
  return {
    offerId: o.offerId,
    seller: { id: o.sellerId, name: o.sellerName, kind: o.sellerName === PLATFORM_SELLER ? 'PLATFORM' : 'SELLER', city: o.city },
    priceMinor: o.priceMinor,
    listPriceMinor: o.listPriceMinor,
    currency: CURRENCY,
    fulfilment: o.fulfilment,
    availableQty: Math.max(0, o.stock),
    stockStatus: stockStatus(o.stock),
    delivery: deliveryPromise({ fulfilment: o.fulfilment, city: o.city }, metro, now)
  };
}

export function summarize(p: FixtureProduct, metro: string, now: Date, sponsor: string | null = null): ProductSummary {
  const db = getDb();
  const atin = atinOf(p.id);
  const offers = db.offersByAtin.get(atin) ?? [];
  const bb = buyBox(offers);
  const qty = Math.max(0, bb.stock);
  return {
    atin,
    title: p.name,
    brand: p.brand,
    community: p.comm,
    department: p.cat,
    unit: p.unit,
    rating: p.rating,
    reviewCount: p.reviews,
    imageUrl: mockProductImageUrl(atin),
    offerId: bb.offerId,
    priceMinor: bb.priceMinor,
    listPriceMinor: bb.listPriceMinor,
    currency: CURRENCY,
    sellerName: bb.sellerName,
    sellerKind: bb.sellerName === PLATFORM_SELLER ? 'PLATFORM' : 'SELLER',
    sellerCity: bb.city,
    fulfilment: bb.fulfilment,
    availableQty: qty,
    inStock: qty > 0,
    lowStock: qty > 0 && qty <= LOW_STOCK_THRESHOLD,
    stockStatus: stockStatus(qty),
    delivery: deliveryPromise({ fulfilment: bb.fulfilment, city: bb.city }, metro, now),
    offerCount: offers.length,
    minOfferPriceMinor: Math.min(...offers.map((o) => o.priceMinor)),
    preorderEligible: fixturePreorderEligible(p),
    sponsor: sponsor ? { advertiser: sponsor } : null
  };
}

/* ── Product detail ─────────────────────────────────────────────── */

export function productDetail(atin: string, metro: string, now: Date): ProductDetail {
  const db = getDb();
  const p = db.products.get(atin);
  if (!p) throw new MockProblem(404, 'PRODUCT_NOT_FOUND', 'Product not found', `No product with ATIN ${atin}.`);
  const offers = db.offersByAtin.get(atin) ?? [];
  const bb = buyBox(offers);
  const purchasable = bb.stock > 0;
  const baseId = p.variantOf ?? p.id;
  const family = [...db.products.values()].filter((x) => x.id === baseId || x.variantOf === baseId);
  const isAT = bb.fulfilment === 'AFROTRANSACT' || bb.sellerName === PLATFORM_SELLER;
  return {
    product: {
      atin,
      title: p.name,
      brand: p.brand,
      community: p.comm,
      department: p.cat,
      unit: p.unit,
      origin: p.origin,
      bullets: p.bullets,
      rating: p.rating,
      reviewCount: p.reviews,
      images: Array.from({ length: 5 }, (_, i) => ({ url: null, alt: `${p.name} — image ${i + 1} of 5` })),
      preorderEligible: fixturePreorderEligible(p),
      variants:
        family.length > 1
          ? family.map((x) => {
              const xa = atinOf(x.id);
              const xbb = buyBox(db.offersByAtin.get(xa) ?? []);
              return { atin: xa, label: x.variantLabel ?? 'Standard', selected: xa === atin, available: xbb.stock > 0, priceMinor: xbb.priceMinor };
            })
          : []
    },
    buyBox: purchasable ? toOffer(bb, metro, now) : null,
    otherOffers: offers.filter((o) => o !== bb).map((o) => toOffer(o, metro, now)),
    shipsFrom: bb.fulfilment === 'AFROTRANSACT' ? `AfroTransact (${nearestFc(metro)})` : `${bb.sellerName}, ${bb.city}`,
    soldBy: bb.sellerName,
    returnsPolicy: isAT ? 'Returnable within 30 days' : p.cat === 'Groceries' ? 'Non-returnable food item — damaged-item refunds only' : 'Seller return policy, 14 days',
    unitPriceLabel: `$${(bb.priceMinor / 100).toFixed(2)} / ${p.unit}`
  };
}

/* ── Search ─────────────────────────────────────────────────────── */

type Dim = 'department' | 'community' | 'fulfilment' | 'sellerKind' | 'rating' | 'price' | 'delivery' | 'q';

interface Candidate { p: FixtureProduct; s: ProductSummary; score: number }

function passes(c: Candidate, q: SearchQuery, skip: Dim | null): boolean {
  const s = c.s;
  if (skip !== 'q' && q.q.trim() && c.score <= 0) return false;
  if (skip !== 'department' && q.department && s.department !== q.department) return false;
  if (skip !== 'community' && q.community && s.community !== q.community) return false;
  if (skip !== 'fulfilment' && q.fulfilment && s.fulfilment !== q.fulfilment) return false;
  if (skip !== 'sellerKind' && q.sellerKind && s.sellerKind !== q.sellerKind) return false;
  if (skip !== 'rating' && q.minRating !== null && s.rating < q.minRating) return false;
  if (skip !== 'delivery' && q.maxDays !== null && s.delivery.days > q.maxDays) return false;
  if (skip !== 'price' && q.price) {
    const b = PRICE_BUCKETS.find((x) => x.id === q.price);
    if (b && ((b.min !== null && s.priceMinor < b.min) || (b.max !== null && s.priceMinor > b.max))) return false;
  }
  return true;
}

function bucketCounts(cands: Candidate[], q: SearchQuery, dim: Dim, values: Array<{ value: string; label: string; test: (c: Candidate) => boolean }>): FacetBucket[] {
  const base = cands.filter((c) => passes(c, q, dim));
  return values.map((v) => ({ value: v.value, label: v.label, count: base.filter(v.test).length }));
}

export function search(q: SearchQuery, loc: DeliveryLocation, now: Date): SearchPage {
  const started = Date.now();
  const db = getDb();
  const metro = metroOf(loc);
  const cands: Candidate[] = [...db.products.values()].map((p) => ({ p, s: summarize(p, metro, now), score: q.q.trim() ? matchScore(p, q.q) : 1 }));
  let list = cands.filter((c) => passes(c, q, null));
  const sorters: Record<SearchQuery['sort'], ((a: Candidate, b: Candidate) => number) | null> = {
    relevance: q.q.trim() ? (a, b) => b.score - a.score : null,
    price_asc: (a, b) => a.s.priceMinor - b.s.priceMinor,
    price_desc: (a, b) => b.s.priceMinor - a.s.priceMinor,
    rating: (a, b) => b.s.rating - a.s.rating,
    fastest: (a, b) => a.s.delivery.days - b.s.delivery.days
  };
  const sorter = sorters[q.sort];
  if (sorter) list = [...list].sort(sorter);
  // Sponsored products that match the query and filters are promoted to the top (and labelled).
  let hits = list.map((c) => c.s);
  if (q.sort === 'relevance' && hits.length) {
    const sponsored = SPONSORED_SEARCH.map((x) => ({ atin: atinOf(x.id), advertiser: x.advertiser })).filter((x) => hits.some((h) => h.atin === x.atin));
    hits = [
      ...sponsored.map((x, i) => ({ ...(hits.find((h) => h.atin === x.atin) as ProductSummary), sponsor: mockSponsor(x.advertiser, i + 1, q.q) })),
      ...hits.filter((h) => !sponsored.some((x) => x.atin === h.atin))
    ];
  }
  const total = hits.length;
  const pageCount = Math.ceil(total / q.size);
  const page = Math.min(q.page, Math.max(0, pageCount - 1));
  const communities = [...new Set(cands.map((c) => c.s.community))].sort();
  const facets = {
    department: bucketCounts(cands, q, 'department', DEPARTMENTS.map((d) => ({ value: d, label: d, test: (c: Candidate) => c.s.department === d }))),
    community: bucketCounts(cands, q, 'community', communities.map((d) => ({ value: d, label: d, test: (c: Candidate) => c.s.community === d }))),
    fulfilment: bucketCounts(cands, q, 'fulfilment', [
      { value: 'AFROTRANSACT', label: 'Fulfilled by AfroTransact', test: (c) => c.s.fulfilment === 'AFROTRANSACT' },
      { value: 'SELLER', label: 'Ships from seller', test: (c) => c.s.fulfilment === 'SELLER' },
      { value: 'AFROTRANSACT_DELIVERY', label: 'AfroTransact Delivery from local stores', test: (c) => c.s.fulfilment === 'AFROTRANSACT_DELIVERY' }
    ]),
    sellerKind: bucketCounts(cands, q, 'sellerKind', [
      { value: 'PLATFORM', label: 'AfroTransact', test: (c) => c.s.sellerKind === 'PLATFORM' },
      { value: 'SELLER', label: 'Independent sellers', test: (c) => c.s.sellerKind === 'SELLER' }
    ]),
    rating: bucketCounts(cands, q, 'rating', [
      { value: '4.5', label: '4.5 & up', test: (c) => c.s.rating >= 4.5 },
      { value: '4', label: '4 & up', test: (c) => c.s.rating >= 4 }
    ]),
    price: bucketCounts(cands, q, 'price', PRICE_BUCKETS.map((b) => ({ value: b.id, label: b.label, test: (c: Candidate) => (b.min === null || c.s.priceMinor >= b.min) && (b.max === null || c.s.priceMinor <= b.max) }))),
    delivery: bucketCounts(cands, q, 'delivery', [{ value: '1', label: 'Get it by tomorrow', test: (c) => c.s.delivery.days <= 1 }])
  };
  return {
    hits: hits.slice(page * q.size, page * q.size + q.size),
    total,
    page,
    size: q.size,
    pageCount,
    facets,
    deliverToLabel: loc.label,
    tookMs: Date.now() - started,
    services: page === 0 ? matchingServices(q.q) : []
  };
}

/** Services whose name, description, category or provider contain every typed word (first page of a text search). */
function matchingServices(text: string): SearchPage['services'] {
  const words = text.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return [];
  return SERVICES.filter((s) => {
    const hay = `${s.name} ${s.description} ${s.category} ${s.providerName}`.toLowerCase();
    return words.every((w) => hay.includes(w));
  })
    .slice(0, 4)
    .map((s) => ({ serviceId: s.id, orgId: s.orgId, name: s.name, providerName: s.providerName, category: s.category, durationMinutes: s.durationMinutes,
      priceFromMinor: s.priceMinor, currency: s.currency, location: PROVIDERS[s.orgId]?.location ?? '' }));
}

export function suggest(query: string, loc: DeliveryLocation, now: Date): Suggestions {
  const db = getDb();
  const metro = metroOf(loc);
  const q = query.trim();
  if (!q) return { query: q, phrases: [], products: [] };
  const terms = completePhrases(q);
  const ranked = [...db.products.values()]
    .map((p) => ({ p, sc: matchScore(p, q) }))
    .filter((x) => x.sc > 0)
    .sort((a, b) => b.sc - a.sc)
    .slice(0, 5);
  const topDept = ranked[0]?.p.cat ?? null;
  const phrases = [...terms.slice(0, 1).map((t) => ({ text: t, department: topDept })), ...terms.map((t) => ({ text: t, department: null }))];
  return {
    query: q,
    phrases: phrases.length || ranked.length ? phrases : [{ text: q, department: null }],
    products: ranked.map((x) => summarize(x.p, metro, now))
  };
}

/* ── Home placements ────────────────────────────────────────────── */

export function home(loc: DeliveryLocation, now: Date): Home {
  const db = getDb();
  const metro = metroOf(loc);
  const all = [...db.products.values()].map((p) => summarize(p, metro, now));
  const rails = PLACEMENTS.rails.map((r) => {
    let items: ProductSummary[] = [];
    if (r.source.kind === 'fulfilled-fastest') items = all.filter((x) => x.fulfilment === 'AFROTRANSACT').sort((a, b) => a.delivery.days - b.delivery.days).slice(0, r.source.limit);
    else if (r.source.kind === 'department-bestsellers') {
      const dept = r.source.department;
      items = all.filter((x) => x.department === dept).sort((a, b) => b.reviewCount - a.reviewCount).slice(0, r.source.limit);
    } else {
      const ids = r.source.ids;
      items = ids.map((id) => all.find((x) => x.atin === atinOf(id))).filter((x): x is ProductSummary => !!x);
    }
    return { id: r.id, title: r.title.replace('{city}', loc.city), href: r.href, items };
  });
  return { announcements: PLACEMENTS.announcements, hero: PLACEMENTS.hero, tiles: PLACEMENTS.tiles, banners: PLACEMENTS.banners, rails, deliverTo: loc };
}

/* ── Cart ───────────────────────────────────────────────────────── */

function offerOrThrow(offerId: string): MockOffer {
  const o = getDb().offers.get(offerId);
  if (!o) throw new MockProblem(404, 'OFFER_NOT_FOUND', 'Offer not found', 'This offer is no longer available.');
  return o;
}

const maxFor = (o: MockOffer) => Math.max(0, Math.min(o.stock, MAX_LINE_QUANTITY));

function stockLimit(o: MockOffer, inCart: number, requested: number): MockProblem {
  const title = getDb().products.get(o.atin)?.name ?? 'this item';
  if (o.stock <= 0) return new MockProblem(409, 'OUT_OF_STOCK', 'Currently unavailable', `${title} is currently unavailable.`, { available: 0, inCart, requested });
  return new MockProblem(
    409,
    'STOCK_LIMIT',
    'Not enough stock',
    inCart > 0 ? `Only ${o.stock} available — ${inCart} already in your cart.` : `Only ${o.stock} available.`,
    { available: o.stock, inCart, requested, maxQuantity: maxFor(o) }
  );
}

export function addToCart(s: MockSession, offerId: string, quantity: number): void {
  const o = offerOrThrow(offerId);
  const line = s.lines.find((l) => l.offerId === offerId);
  const inCart = line?.quantity ?? 0;
  if (inCart + quantity > maxFor(o)) throw stockLimit(o, inCart, inCart + quantity);
  if (line) line.quantity += quantity;
  else s.lines.push({ lineId: nextId('ln'), offerId, quantity, selected: true });
}

function lineOrThrow(s: MockSession, lineId: string) {
  const line = s.lines.find((l) => l.lineId === lineId);
  if (!line) throw new MockProblem(404, 'LINE_NOT_FOUND', 'Cart line not found', 'That item is no longer in your cart.');
  return line;
}

export function updateLine(s: MockSession, lineId: string, patch: { quantity?: number; selected?: boolean }): void {
  const line = lineOrThrow(s, lineId);
  if (patch.quantity !== undefined) {
    const o = offerOrThrow(line.offerId);
    if (patch.quantity > maxFor(o)) throw stockLimit(o, line.quantity, patch.quantity);
    line.quantity = patch.quantity;
  }
  if (patch.selected !== undefined) line.selected = patch.selected;
}

export function removeLine(s: MockSession, lineId: string): void {
  lineOrThrow(s, lineId);
  s.lines = s.lines.filter((l) => l.lineId !== lineId);
}

export function saveForLater(s: MockSession, lineId: string): void {
  const line = lineOrThrow(s, lineId);
  s.lines = s.lines.filter((l) => l !== line);
  if (!s.saved.some((x) => x.offerId === line.offerId)) s.saved.push({ savedId: nextId('sv'), offerId: line.offerId });
}

export function moveToCart(s: MockSession, savedId: string): void {
  const item = s.saved.find((x) => x.savedId === savedId);
  if (!item) throw new MockProblem(404, 'SAVED_NOT_FOUND', 'Saved item not found', 'That item is no longer saved.');
  addToCart(s, item.offerId, 1);
  s.saved = s.saved.filter((x) => x !== item);
}

export function removeSaved(s: MockSession, savedId: string): void {
  if (!s.saved.some((x) => x.savedId === savedId)) throw new MockProblem(404, 'SAVED_NOT_FOUND', 'Saved item not found', 'That item is no longer saved.');
  s.saved = s.saved.filter((x) => x.savedId !== savedId);
}

export function buildCart(s: MockSession, now: Date): Cart {
  const db = getDb();
  const loc = sessionLocation(s);
  const metro = metroOf(loc);
  // Drop lines whose offer vanished; clamp quantities to current stock (stock can move between visits).
  s.lines = s.lines.filter((l) => db.offers.has(l.offerId));
  const groups = new Map<string, { offer: MockOffer; line: (typeof s.lines)[number] }[]>();
  for (const line of s.lines) {
    const offer = db.offers.get(line.offerId) as MockOffer;
    const key = `${offer.sellerId}:${offer.fulfilment}`;
    const g = groups.get(key) ?? [];
    g.push({ offer, line });
    groups.set(key, g);
  }
  const packages: CartPackage[] = [...groups.entries()].map(([key, items]) => {
    const first = items[0] as { offer: MockOffer };
    const o = first.offer;
    const isAT = o.fulfilment === 'AFROTRANSACT';
    const local = o.fulfilment === 'AFROTRANSACT_DELIVERY';
    const lines = items.map(({ offer, line }) => {
      const p = db.products.get(offer.atin) as FixtureProduct;
      return {
        lineId: line.lineId,
        atin: offer.atin,
        offerId: offer.offerId,
        title: p.name,
        unit: p.unit,
        department: p.cat,
        productType: fixtureProductType(p),
        imageUrl: mockProductImageUrl(offer.atin),
        priceMinor: offer.priceMinor,
        lineTotalMinor: offer.priceMinor * line.quantity,
        quantity: line.quantity,
        maxQuantity: maxFor(offer),
        availableQty: Math.max(0, offer.stock),
        stockStatus: stockStatus(offer.stock),
        selected: line.selected,
        preorderEligible: fixturePreorderEligible(p)
      };
    });
    const selected = lines.filter((l) => l.selected);
    const subtotalMinor = selected.reduce((a, l) => a + l.lineTotalMinor, 0);
    const slowest = Math.max(...items.map(({ offer }) => etaDays({ fulfilment: offer.fulfilment, city: offer.city }, metro)));
    const express = Math.max(...items.map(({ offer }) => etaDays({ fulfilment: offer.fulfilment, city: offer.city }, metro, true)));
    const promise = deliveryPromise({ fulfilment: o.fulfilment, city: o.city }, metro, now);
    const options = deliveryOptions({ from: { fulfilment: o.fulfilment, city: o.city }, sellerName: o.sellerName, slowestDays: slowest, subtotalMinor, expressDays: express }, metro, now);
    return {
      packageId: key,
      seller: { id: o.sellerId, name: o.sellerName, kind: o.sellerName === PLATFORM_SELLER ? 'PLATFORM' : 'SELLER', city: o.city },
      fulfilment: o.fulfilment,
      heading: isAT ? `Shipped by AfroTransact from ${nearestFc(metro)}` : local ? `AfroTransact Delivery from ${o.city}` : `Shipped by ${o.sellerName} from ${o.city}`,
      shipsFrom: isAT ? nearestFc(metro) : o.city,
      delivery: { ...promise, days: slowest, label: options[0]?.eta ?? promise.label },
      deliveryOptions: options,
      lines,
      itemCount: lines.reduce((a, l) => a + l.quantity, 0),
      subtotalMinor,
      freeShippingNudge: !isAT && !local && subtotalMinor > 0 && subtotalMinor < FREE_SHIPPING_THRESHOLD_MINOR ? { remainingMinor: FREE_SHIPPING_THRESHOLD_MINOR - subtotalMinor, thresholdMinor: FREE_SHIPPING_THRESHOLD_MINOR } : null
    };
  });
  const allLines = packages.flatMap((p) => p.lines);
  const sel = allLines.filter((l) => l.selected);
  // Lines eligible for the place's live pre-order window become one pre-order package (S273).
  return withPreorderPackage({
    cartId: `cart_${s.sid.slice(0, 12)}`,
    currency: CURRENCY,
    packages,
    saved: s.saved
      .map((x) => {
        const offer = db.offers.get(x.offerId);
        const p = offer ? db.products.get(offer.atin) : undefined;
        return offer && p ? { savedId: x.savedId, atin: offer.atin, offerId: offer.offerId, title: p.name, imageUrl: null, priceMinor: offer.priceMinor, available: offer.stock > 0 } : null;
      })
      .filter((x): x is NonNullable<typeof x> => !!x),
    itemCount: allLines.reduce((a, l) => a + l.quantity, 0),
    selectedCount: sel.reduce((a, l) => a + l.quantity, 0),
    subtotalMinor: sel.reduce((a, l) => a + l.lineTotalMinor, 0),
    allSelected: allLines.length > 0 && allLines.every((l) => l.selected),
    sellerCount: new Set(packages.map((p) => p.seller.id)).size
  }, s, now);
}

/** Mock search ad (S240): label, slot, click token and the reason it was shown (never bids). */
function mockSponsor(advertiser: string, slot: number, q: string): NonNullable<ProductSummary['sponsor']> {
  const matched = q.trim().toLowerCase() || 'groceries';
  return {
    advertiser,
    campaignId: `cmp-${slot}`,
    slot,
    adToken: `mock-ad.${slot}.${encodeURIComponent(matched)}`,
    reason: {
      targeting: 'KEYWORD',
      matched,
      qualityScore: 0.8,
      auctionRank: slot,
      competitors: 2,
      eligibility: ['In stock', 'Holds the buy box', 'Matches your filters'],
      summary: `Shown because you searched for “${matched}” and ${advertiser} sells it in stock.`
    }
  };
}
