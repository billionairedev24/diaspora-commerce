/* Mock BFF for the seller storefront (/b/{slug}): GET /v1/storefronts/{slug}[/products] and the demo photos. MOCK DATA ONLY.
   The same builder serves the Seller Center preview (seller/handlers.ts), so the preview and the public page cannot differ.
   Published storefronts live in memory; when MOCK_STOREFRONT_STATE_FILE is set (the Playwright journey runs the Seller Center
   and the storefront as two processes) they are also written to that file so a publish in one is visible in the other. */
import { http, HttpResponse } from 'msw';

import { type ProductSummary } from '../schemas/catalog';
import { defaultStoreConfig, type StoreConfig, type StoreKind, type Storefront, type StorefrontProducts, type StorefrontProvider, type StorefrontQuery } from '../schemas/storefront';
import { getDb } from './db';
import { atinOf, PLATFORM_SELLER, PRODUCTS } from './fixtures/catalog';
import { type DemoShape,LASERFEET_PRODUCTS, LASERFEET_PROFILE } from './fixtures/laserfeet';
import { route } from './http-kit';
import { MockProblem, summarize } from './service';
import { PROVIDER_AREAS, PROVIDERS, SERVICES } from './shopper-state';
import { mockPhotoSvg } from './storefront-photo';

const PHOTO_PATH = '/api/bff/v1/mock-photos/';
export const MOCK_PHOTO_PREFIXES = [PHOTO_PATH, 'https://img.afrotransact.dev/'] as const;
const STATE_ENV = 'MOCK_STOREFRONT_STATE_FILE';

interface NodeFs {
  readFileSync(path: string, enc: 'utf8'): string;
  writeFileSync(path: string, data: string): void;
}
/** Node's fs when there is one (no static import: these mocks must also bundle for a browser). */
const nodeFs = (): NodeFs | undefined => (globalThis as { process?: { getBuiltinModule?: (id: string) => unknown } }).process?.getBuiltinModule?.('node:fs') as NodeFs | undefined;

export interface PublishedStore {
  kind?: StoreKind;
  /** Provider pages: the mock booking provider whose services, hours and area the page shows. */
  providerKey?: string;
  slug: string;
  name: string;
  logoUrl: string | null;
  description: string | null;
  contactEmail: string | null;
  sinceYear: number;
  config: StoreConfig;
  publishedAt: string;
}

/* ── published stores (memory, optionally a shared file) ── */

const KEY = '__AFROTRANSACT_MOCK_STOREFRONTS__';
type Holder = { [KEY]?: Record<string, PublishedStore> };

function readShared(): Record<string, PublishedStore> {
  const file = process.env[STATE_ENV];
  const fs = nodeFs();
  if (file && fs) {
    try {
      return JSON.parse(fs.readFileSync(file, 'utf8')) as Record<string, PublishedStore>;
    } catch {
      return {};
    }
  }
  const g = globalThis as Holder;
  return (g[KEY] ??= {});
}

function writeShared(next: Record<string, PublishedStore>): void {
  const file = process.env[STATE_ENV];
  const fs = nodeFs();
  if (file && fs) fs.writeFileSync(file, JSON.stringify(next));
  else (globalThis as Holder)[KEY] = next;
}

export function publishMockStore(s: PublishedStore): void {
  writeShared({ ...readShared(), [s.slug]: s });
}

export function unpublishMockStore(slug: string): void {
  const next = { ...readShared() };
  delete next[slug];
  writeShared(next);
}

/** Laserfeet is always published in the mock stack: the demo of /b/laserfeet. */
function laserfeetConfig(): StoreConfig {
  const c = defaultStoreConfig();
  c.theme = { preset: 'warm-market', accent: null };
  c.hero = { style: 'split', tagline: 'Soft, comfortable feet, every day.', bannerUrl: `${PHOTO_PATH}laserfeet-banner.jpg`, focalX: 50, focalY: 50 };
  c.grid = { columns: 3 };
  c.about = {
    heading: 'Why we make this',
    body: 'Demo store. Laserfeet is a mock seller used to build and test this page.\n\nIts text, photos, prices and ratings are made up for the mock stack and never appear in production.',
    photoUrl: `${PHOTO_PATH}laserfeet-about.jpg`
  };
  c.policies = { shipping: 'Orders are packed within two business days.', returns: 'Unopened items can be returned within 30 days.' };
  c.contact = { showEmail: true, phone: '', hours: 'Monday to Friday, 9 am to 5 pm' };
  c.pickup = { show: true };
  c.featured = ['AT0000LF01', 'AT0000LF06', 'AT0000LF11', 'AT0000LF04'];
  c.social = [{ network: 'instagram', url: 'https://www.instagram.com/laserfeet' }];
  return c;
}

/** A provider's demo page (the mock stack's provider with real-looking services, hours and area; MOCK DATA ONLY). */
const DEMO_PROVIDER_SLUG = 'braids-by-ama';
const DEMO_PROVIDER_KEY = 'org_braids_ama';
export const providerKeyForOrg = (orgId: string): string => (orgId === 'org-ama-braiding' ? DEMO_PROVIDER_KEY : orgId);

function providerDemoConfig(): StoreConfig {
  const c = defaultStoreConfig('PROVIDER');
  c.theme = { preset: 'heritage', accent: null };
  c.hero = { style: 'colour', tagline: 'Protective styles that last.', bannerUrl: null, focalX: 50, focalY: 50 };
  c.about = { heading: 'About the studio', body: 'Demo provider. Braids by Ama is a mock provider used to build and test this page.\n\nIts services, prices and times are made up for the mock stack and never appear in production.', photoUrl: null };
  return c;
}

export function publishedMockStore(slug: string): PublishedStore | null {
  const shared = readShared()[slug];
  if (shared) return shared;
  if (slug === DEMO_PROVIDER_SLUG) {
    return { kind: 'PROVIDER', providerKey: DEMO_PROVIDER_KEY, slug, name: PROVIDERS[DEMO_PROVIDER_KEY]!.displayName, logoUrl: null, description: PROVIDERS[DEMO_PROVIDER_KEY]!.tagline, contactEmail: null,
      sinceYear: 2024, config: providerDemoConfig(), publishedAt: '2026-11-01T10:00:00.000Z' };
  }
  if (slug === LASERFEET_PROFILE.slug) {
    return { slug, name: LASERFEET_PROFILE.name, logoUrl: `${PHOTO_PATH}laserfeet-logo.jpg`, description: LASERFEET_PROFILE.description, contactEmail: LASERFEET_PROFILE.contactEmail,
      sinceYear: LASERFEET_PROFILE.sinceYear, config: laserfeetConfig(), publishedAt: '2026-11-01T10:00:00.000Z' };
  }
  return null;
}

/* ── demo products ── */

const demoAtin = (id: string) => atinOf(id);

/** Registers the Laserfeet demo products in the mock database (unlisted: reachable by address, cart and checkout, but not in marketplace search). */
export function ensureDemoStore(): void {
  const db = getDb();
  for (const d of LASERFEET_PRODUCTS) {
    const atin = demoAtin(d.id);
    if (db.products.has(atin)) continue;
    const fixture = { id: d.id, name: d.name, brand: d.brand, comm: 'Everyday', cat: d.department, rating: d.rating, reviews: d.reviews, unit: d.unit, bullets: [], unlisted: true,
      offers: [{ seller: LASERFEET_PROFILE.name, city: 'Houston, TX', price: d.priceMinor / 100, ...(d.listMinor ? { list: d.listMinor / 100 } : {}), ff: 'S' as const, stock: d.stock, handling: 1 }] };
    db.products.set(atin, fixture);
    const offerId = `${atin}-laserfeet`;
    const offer = { offerId, atin, productId: d.id, sellerId: 'laserfeet', sellerName: LASERFEET_PROFILE.name, city: 'Houston, TX', priceMinor: d.priceMinor, listPriceMinor: d.listMinor ?? null,
      fulfilment: 'SELLER' as const, stock: d.stock, handling: 1 };
    db.offers.set(offerId, offer);
    db.offersByAtin.set(atin, [offer]);
  }
}

const shapeOf = new Map<string, DemoShape>(LASERFEET_PRODUCTS.map((d) => [demoAtin(d.id), d.shape]));
const hasPhoto = new Map<string, boolean>(LASERFEET_PRODUCTS.map((d) => [demoAtin(d.id), d.photo]));

function photoFor(atin: string): string | null {
  return hasPhoto.get(atin) === false ? null : `${PHOTO_PATH}${atin}.jpg`;
}

/** Every product the demo seller has, as the shopper's cards (photo rule and stock applied by the caller). */
function catalogOf(slug: string, now: Date): ProductSummary[] {
  ensureDemoStore();
  const metro = 'Houston, TX';
  const fixtures = slug.startsWith(LASERFEET_PROFILE.slug) ? LASERFEET_PRODUCTS.map((d) => getDb().products.get(demoAtin(d.id))!) : PRODUCTS.filter((p) => p.cat === 'Groceries' && !p.unlisted && !p.variantOf).slice(0, 12);
  return fixtures.map((f) => {
    const s = summarize(f, metro, now);
    return { ...s, imageUrl: photoFor(s.atin), sellerName: slug.startsWith(LASERFEET_PROFILE.slug) ? LASERFEET_PROFILE.name : s.sellerName === PLATFORM_SELLER ? 'Ade’s Kitchen' : s.sellerName };
  });
}

const BUCKETS = [
  { value: 'u10', label: 'Under $10', test: (m: number) => m <= 999 },
  { value: '10-25', label: '$10 to $25', test: (m: number) => m >= 1000 && m <= 2500 },
  { value: '25-50', label: '$25 to $50', test: (m: number) => m >= 2501 && m <= 5000 },
  { value: 'o50', label: '$50 & above', test: (m: number) => m >= 5001 }
] as const;

const sellable = (p: ProductSummary) => p.inStock && p.imageUrl !== null && !/placeholder|no-image|\.svg|^data:/i.test(p.imageUrl);

/** The All products grid: only sellable products, then the shopper's search, category, price, sort and page. */
export function storeProducts(slug: string, q: StorefrontQuery, now: Date): StorefrontProducts {
  const live = catalogOf(slug, now).filter(sellable);
  const categories = [...live.reduce((m, p) => m.set(p.department, (m.get(p.department) ?? 0) + 1), new Map<string, number>())].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count);
  const needle = (q.q ?? '').trim().toLowerCase();
  let list = live.filter((p) => !q.onSale || (p.listPriceMinor !== null && p.listPriceMinor > p.priceMinor)).filter((p) => (!needle || `${p.title} ${p.brand} ${p.department}`.toLowerCase().includes(needle)) && (!q.category || p.department === q.category));
  const prices = BUCKETS.map((b) => ({ value: b.value, label: b.label, count: list.filter((p) => b.test(p.priceMinor)).length })).filter((b) => b.count > 0);
  const bucket = BUCKETS.find((b) => b.value === q.price);
  if (bucket) list = list.filter((p) => bucket.test(p.priceMinor));
  const by: Record<string, (a: ProductSummary, b: ProductSummary) => number> = {
    price_asc: (a, b) => a.priceMinor - b.priceMinor,
    price_desc: (a, b) => b.priceMinor - a.priceMinor,
    rating: (a, b) => b.rating - a.rating || b.reviewCount - a.reviewCount,
    newest: (a, b) => (a.atin < b.atin ? 1 : -1),
    relevance: (a, b) => b.reviewCount - a.reviewCount || (a.atin < b.atin ? -1 : 1)
  };
  list = [...list].sort(by[q.sort ?? 'relevance'] ?? by.relevance!);
  const size = Math.min(Math.max(q.size ?? 24, 1), 48);
  const page = Math.max(q.page ?? 1, 1);
  return { hits: list.slice((page - 1) * size, page * size), total: list.length, page, size, pageCount: Math.max(1, Math.ceil(list.length / size)), categories, prices };
}

const reviewsOf = (all: ProductSummary[]) => {
  const rated = all.filter((p) => p.reviewCount > 0 && p.rating > 0);
  const count = rated.reduce((n, p) => n + p.reviewCount, 0);
  return count === 0 ? null : { average: Math.round((rated.reduce((n, p) => n + p.rating * p.reviewCount, 0) / count) * 10) / 10, count };
};

export interface StorefrontSource {
  kind?: StoreKind;
  providerKey?: string;
  slug: string;
  name: string;
  logoUrl: string | null;
  description: string | null;
  contactEmail: string | null;
  sinceYear: number;
  config: StoreConfig;
  preview: boolean;
}

/** The whole storefront from a source: the same rules as the BFF (sellable only, reviews only when real, contact only when shown). */
/** A provider's bookable data from the mock booking directory: services in person only, hours Monday to Saturday (what the mock slots follow), the service area. */
function providerBlock(key: string): StorefrontProvider | null {
  const meta = PROVIDERS[key];
  const services = SERVICES.filter((s) => s.orgId === key);
  if (!meta || services.length === 0) return null;
  const area = PROVIDER_AREAS[key];
  const days = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const;
  return {
    orgId: key, timeZone: 'America/Chicago', location: meta.location, instantBooking: services[0]!.instantBooking, policyLabel: services[0]!.policy.label, policyText: services[0]!.policy.text,
    services: services.map((s) => ({ id: s.id, name: s.name, description: s.description, category: s.category, durationMinutes: s.durationMinutes, priceMinor: s.priceMinor, depositMinor: s.depositMinor,
      currency: s.currency, modes: s.modes, imageUrl: null })),
    hours: days.map((day) => ({ day, ranges: day === 'SUNDAY' ? [] : [{ start: '09:00', end: '18:00' }] })),
    area: area ? { baseCity: area.city, baseState: area.state, radiusMiles: area.radiusMiles, servedCities: [], extraAreas: area.extra.map((e) => e.split(':').slice(1).join(', ')) } : null,
    team: services[0]!.team
  };
}

export function buildStorefront(src: StorefrontSource, now: Date): Storefront {
  if (src.kind === 'PROVIDER') {
    const key = src.providerKey ?? DEMO_PROVIDER_KEY;
    const provider = providerBlock(key);
    const meta = PROVIDERS[key];
    const empty: StorefrontProducts = { hits: [], total: 0, page: 1, size: 24, pageCount: 1, categories: [], prices: [] };
    return { kind: 'PROVIDER', provider, slug: src.slug, name: src.name, logoUrl: src.logoUrl, description: src.description, config: src.config, preview: src.preview,
      indexable: !src.preview && !!provider && provider.services.length > 0, verified: true, sellingSinceYear: src.sinceYear, contactEmail: src.config.contact.showEmail ? src.contactEmail : null,
      pickup: null, categories: [], featured: [], reviews: meta && meta.reviews > 0 && meta.rating > 0 ? { average: meta.rating, count: meta.reviews } : null, products: empty };
  }
  const first = storeProducts(src.slug, { page: 1, size: 24 }, now);
  const all = catalogOf(src.slug, now).filter(sellable);
  const featured = src.config.featured.map((a) => all.find((p) => p.atin === a)).filter((p): p is ProductSummary => !!p);
  return {
    kind: 'SELLER', provider: null,
    slug: src.slug, name: src.name, logoUrl: src.logoUrl, description: src.description, config: src.config, preview: src.preview, indexable: !src.preview && first.hits.length > 0,
    verified: true, sellingSinceYear: src.sinceYear, contactEmail: src.config.contact.showEmail ? src.contactEmail : null,
    pickup: src.config.pickup.show && src.slug.startsWith(LASERFEET_PROFILE.slug) ? { zip: '77002' } : null, categories: first.categories, featured, reviews: reviewsOf(all), products: first
  };
}

export function searchStoreProducts(slug: string, text: string, now: Date): ProductSummary[] {
  return catalogOf(slug, now).filter(sellable).filter((p) => !text.trim() || `${p.title} ${p.brand}`.toLowerCase().includes(text.trim().toLowerCase())).slice(0, 20);
}

/* ── SVG stand-ins for the photographs ── */

function bannerSvg(seed: string, tall: boolean): string {
  const h = (Array.from(seed).reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7) % 40) + 18;
  const w = tall ? 900 : 1600;
  const ht = tall ? 700 : 760;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${ht}" width="${w}" height="${ht}"><defs>
<linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="hsl(${h} 62% 80%)"/><stop offset="1" stop-color="hsl(${h + 24} 55% 62%)"/></linearGradient>
<filter id="b"><feGaussianBlur stdDeviation="40"/></filter></defs>
<rect width="${w}" height="${ht}" fill="url(#g)"/>
<circle cx="${w * 0.72}" cy="${ht * 0.38}" r="${ht * 0.34}" fill="hsl(${h + 8} 70% 92%)" opacity=".75" filter="url(#b)"/>
<circle cx="${w * 0.9}" cy="${ht * 0.92}" r="${ht * 0.3}" fill="hsl(${h + 30} 50% 44%)" opacity=".55" filter="url(#b)"/>
<circle cx="${w * 0.42}" cy="${ht * 0.96}" r="${ht * 0.26}" fill="hsl(${h - 6} 66% 70%)" opacity=".6" filter="url(#b)"/>
</svg>`;
}

const svg = (xml: string) => new HttpResponse(xml, { headers: { 'Content-Type': 'image/svg+xml', 'Cache-Control': 'public, max-age=3600' } });

export const storefrontHandlers = [
  http.get('*/v1/mock-photos/:name', ({ params }) => {
    const name = String(params.name ?? '').replace(/\.(jpg|webp)$/, '');
    if (name.endsWith('-banner')) return svg(bannerSvg(name, false));
    if (name.endsWith('-about')) return svg(bannerSvg(name, true));
    if (name.endsWith('-logo')) return svg(mockPhotoSvg(name, 'jar', name.replace('-logo', '')));
    const product = getDb().products.get(name);
    return svg(mockPhotoSvg(name, shapeOf.get(name) ?? 'jar', product?.name ?? name));
  }),
  http.get(
    '*/v1/storefronts',
    route(() => {
      const slugs = [...new Set(['laserfeet', DEMO_PROVIDER_SLUG, ...Object.keys(readShared())])];
      return HttpResponse.json({ items: slugs.map((slug) => ({ kind: publishedMockStore(slug)?.kind ?? 'SELLER', slug, publishedAt: publishedMockStore(slug)?.publishedAt ?? '2026-11-01T10:00:00.000Z' })) });
    })
  ),
  http.get(
    '*/v1/storefronts/:slug/products',
    route((ctx, p) => {
      if (!publishedMockStore(p.slug ?? '')) throw new MockProblem(404, 'NOT_FOUND', 'Store not found', 'There is no store at this address.');
      const u = new URL(ctx.request.url).searchParams;
      const bucket = u.get('price');
      if (bucket && !BUCKETS.some((b) => b.value === bucket)) throw new MockProblem(400, 'INVALID_QUERY', 'Invalid query', 'Unknown price range.');
      const sort = u.get('sort') as StorefrontQuery['sort'];
      if (publishedMockStore(p.slug ?? '')?.kind === 'PROVIDER') return HttpResponse.json({ hits: [], total: 0, page: 1, size: 24, pageCount: 1, categories: [], prices: [] });
      return HttpResponse.json(storeProducts(p.slug ?? '', { q: u.get('q') ?? '', category: u.get('category') ?? '', price: bucket ?? '', sort: sort ?? 'relevance', onSale: u.get('onSale') === 'true', page: Number(u.get('page') ?? 1), size: Number(u.get('size') ?? 24) }, ctx.now));
    })
  ),
  http.get(
    '*/v1/storefronts/:slug',
    route((ctx, p) => {
      const s = publishedMockStore((p.slug ?? '').toLowerCase());
      if (!s) throw new MockProblem(404, 'NOT_FOUND', 'Store not found', 'There is no store at this address.');
      return HttpResponse.json(buildStorefront({ ...(s.kind ? { kind: s.kind } : {}), ...(s.providerKey ? { providerKey: s.providerKey } : {}), slug: s.slug, name: s.name, logoUrl: s.logoUrl, description: s.description, contactEmail: s.contactEmail, sinceYear: s.sinceYear, config: s.config, preview: false }, ctx.now));
    })
  )
];
