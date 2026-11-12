/* The mock BFF's dynamic home (docs/engineering/dynamic-home.md): a TypeScript model of the gateway's eligibility engine, used only by
   the MSW handlers, the Playwright mock BFF and the staff preview mock. The real gateway composes in Go; this mirrors its contract so the
   storefront and the staff console can be built and tested against 0 / 10 / 100 / 10,000-product catalogues.
   Rules (all staff-editable defaults): communities only when 3 or more have 8 or more eligible products and 2 or more sellers; departments
   with nothing in them are not linked; Services when any provider exists ("near you" with a ZIP, else "popular"); at zero products a
   text-only brand hero and a sell band; at most 1 sponsored module per 3 organic ones; a product appears once on the page.
   Variety: a visit seed picks WHICH eligible items fill each module and breaks ties between modules of one band. The same seed always
   gives the same page; no seed (crawlers) gives the deterministic default. */
import { type ProductSummary } from '../schemas/catalog';
import { type HeroSlide, type Home, type HomeBlock, type ServiceCard } from '../schemas/home';
import { type DeliveryLocation } from '../schemas/location';
import { type NavCommunity, type NavDepartment, type SiteNavigation } from '../schemas/site-nav';
import { HOME_MODULE_KEYS, type HomeDecision, type HomeModuleKey, type HomeModuleRule, type HomePreview, type HomePreviewQuery, type HomeReasonCode, type HomeSettings, type HomeSettingsInput } from '../staff-home';

/* ── Settings ─────────────────────────────────────────────── */

const rule = (key: HomeModuleKey, name: string, band: HomeModuleRule['band'], weight: number, over: Partial<HomeModuleRule> = {}): HomeModuleRule => ({
  key,
  name,
  enabled: true,
  minProducts: 0,
  minSellers: 0,
  minUnits: 0,
  unitMinProducts: 0,
  unitMinSellers: 0,
  maxAgeDays: 0,
  weight,
  band,
  labels: {},
  startsAt: null,
  endsAt: null,
  ...over
});

/** The defaults the owner chose; they equal DefaultHomeRules() in the gateway. */
export function defaultHomeSettings(): HomeSettings {
  return {
    version: 1,
    dynamic: 'on',
    organicPerSponsored: 3,
    minModules: 3,
    maxModules: 12,
    modules: [
      rule('hero', 'Hero', 'top', 100),
      rule('continue-shopping', 'Continue shopping', 'top', 90, { minProducts: 2 }),
      rule('recently-viewed', 'Recently viewed', 'top', 80, { minProducts: 1 }),
      rule('departments', 'Shop by department', 'upper', 85, { minUnits: 2, unitMinProducts: 1 }),
      rule('pre-order', 'Pre-order', 'upper', 75),
      rule('deals', 'Deals', 'upper', 70, { minProducts: 6, minSellers: 2 }),
      rule('services', 'Services', 'upper', 65, { minProducts: 1, minUnits: 3 }),
      rule('groceries', 'Groceries', 'upper', 60, { minProducts: 6 }),
      rule('new-arrivals', 'New arrivals', 'middle', 55, { minProducts: 8, minSellers: 1, maxAgeDays: 30 }),
      rule('communities', 'Shop by community', 'middle', 50, { minUnits: 3, unitMinProducts: 8, unitMinSellers: 2 }),
      rule('top-sellers', 'Top sellers', 'middle', 45, { minProducts: 8, minSellers: 2 }),
      rule('plus', 'AfroTransact Plus', 'lower', 30),
      rule('sell', 'Sell on AfroTransact', 'lower', 20)
    ],
    updatedAt: null,
    updatedByName: null,
    canEdit: true
  };
}

export function settingsInputOf(s: HomeSettings): HomeSettingsInput {
  const { version: _v, updatedAt: _u, updatedByName: _n, canEdit: _c, ...rest } = s;
  return rest;
}

/** Mock state shared by the storefront and staff handlers: the staff settings and which synthetic catalogue (if any) the mock home serves. */
export const homeMock: { settings: HomeSettings; catalogue: number | null } = { settings: defaultHomeSettings(), catalogue: null };

/** The catalogue size the mock BFF serves: set by a test, or MOCK_HOME_CATALOGUE for a dev server; null keeps the classic mock home. */
export function mockCatalogueSize(): number | null {
  if (homeMock.catalogue !== null) return homeMock.catalogue;
  const env = typeof process !== 'undefined' ? process.env.MOCK_HOME_CATALOGUE : undefined;
  return env !== undefined && env !== '' && !Number.isNaN(Number(env)) ? Number(env) : null;
}

export function resetHomeMock(): void {
  homeMock.settings = defaultHomeSettings();
  homeMock.catalogue = null;
}

/* ── Seeded randomness ────────────────────────────────────── */

/** The request header that carries the visit seed from the storefront to the gateway (read from the short-lived `at_hv` cookie). */
export const HOME_SEED_HEADER = 'x-home-seed';

/** The seed meaning "no variety": crawlers and anyone without a visit cookie get exactly this page. */
export const DEFAULT_SEED = 'default';

function hash(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619);
  return h >>> 0;
}

/** mulberry32: small, fast, and identical on every platform. */
export function rng(seed: string, salt: string): () => number {
  let a = hash(`${seed}|${salt}`);
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Picks `count` items: the first `count` in rank order for the default seed, else a seeded draw from the best `count * 4`. */
export function pick<T>(items: readonly T[], count: number, seed: string, salt: string): T[] {
  if (seed === DEFAULT_SEED || items.length <= count) return items.slice(0, count);
  const pool = items.slice(0, Math.max(count, count * 4));
  const r = rng(seed, salt);
  for (let i = pool.length - 1; i > 0; i--) {
    const j = Math.floor(r() * (i + 1));
    [pool[i], pool[j]] = [pool[j] as T, pool[i] as T];
  }
  return pool.slice(0, count);
}

/* ── Catalogues ───────────────────────────────────────────── */

export interface CatalogProduct extends ProductSummary {
  /** Days since the listing went live. */
  ageDays: number;
}
export interface Catalog {
  products: CatalogProduct[];
  services: ServiceCard[];
  preorderOpen: boolean;
}

const DEPARTMENTS: ReadonlyArray<[key: string, name: string]> = [
  ['groceries-food', 'Groceries & Food'],
  ['fashion-accessories', 'Fashion & Accessories'],
  ['beauty-personal-care', 'Beauty & Personal Care'],
  ['home-household', 'Home & Household'],
  ['electronics', 'Electronics'],
  ['baby-kids', 'Baby & Kids'],
  ['pets', 'Pets'],
  ['books-media', 'Books & Media'],
  ['health-wellness', 'Health & Wellness'],
  ['art-crafts', 'Art & Crafts'],
  ['party-events', 'Party & Events'],
  ['sports-outdoors', 'Sports & Outdoors'],
  ['automotive', 'Automotive']
];
const COMMUNITIES = ['Nigerian', 'Ghanaian', 'Ethiopian', 'Jamaican', 'Indian', 'Senegalese', 'Kenyan', 'Caribbean'];
const SERVICE_NAMES = ['Braiding', 'Catering', 'Photography', 'Tailoring', 'Hair styling', 'Event decor', 'Makeup', 'Tutoring'];
const SERVICE_CITIES = ['Houston', 'Atlanta', 'Dallas', 'Brooklyn', 'Chicago'];

export const SIMULATED_SIZES = [0, 10, 100, 10000] as const;
const cache = new Map<number, Catalog>();

/** A deterministic synthetic catalogue of `n` eligible products (in stock, real photo): the same `n` is always the same catalogue. */
export function fixtureCatalog(n: number): Catalog {
  const hit = cache.get(n);
  if (hit) return hit;
  const deptCount = n <= 10 ? 3 : DEPARTMENTS.length;
  const sellers = Math.min(400, Math.max(2, Math.ceil(n / 12)));
  const products: CatalogProduct[] = [];
  for (let i = 0; i < n; i++) {
    const atin = `AT${String(i + 1).padStart(8, '0')}`;
    const dept = DEPARTMENTS[i % deptCount] as [string, string];
    const rated = i % 3 === 0;
    const price = 500 + ((i * 37) % 4000);
    const deal = i % 7 === 0;
    products.push({
      atin,
      title: `${COMMUNITIES[i % COMMUNITIES.length]} ${dept[1].split(' ')[0]} ${i + 1}`,
      brand: `Brand ${(i * 3) % 50}`,
      community: COMMUNITIES[i % COMMUNITIES.length] as string,
      department: dept[1],
      unit: '1 pack',
      rating: rated ? 4 + (i % 10) / 10 : 0,
      reviewCount: rated ? 3 + (i % 50) : 0,
      imageUrl: `https://img.afrotransact.dev/products/${atin}.webp`,
      offerId: `off_${atin}`,
      priceMinor: price,
      listPriceMinor: deal ? Math.round(price * 1.25) : null,
      currency: 'USD',
      sellerName: `Seller ${(i * 7) % sellers}`,
      sellerKind: i % 97 === 0 ? 'PLATFORM' : 'SELLER',
      sellerCity: SERVICE_CITIES[i % SERVICE_CITIES.length] as string,
      fulfilment: 'SELLER',
      availableQty: 20,
      inStock: true,
      lowStock: false,
      stockStatus: 'IN_STOCK',
      delivery: { days: 2, label: 'In 2 days', prefix: 'Delivery by AfroTransact', free: false, feeMinor: 599, cutoff: null },
      offerCount: 1,
      minOfferPriceMinor: price,
      sponsor: null,
      ageDays: i % 40
    });
  }
  const providers = n === 0 ? 0 : n <= 10 ? 2 : n <= 100 ? 6 : 40;
  const services: ServiceCard[] = Array.from({ length: providers }, (_, i) => ({
    id: `svc_${i + 1}`,
    name: SERVICE_NAMES[i % SERVICE_NAMES.length] as string,
    provider: `Provider ${i + 1}`,
    city: SERVICE_CITIES[i % SERVICE_CITIES.length] as string,
    fromPriceMinor: 4000 + i * 500,
    currency: 'USD',
    // Every third service has no photo: it renders text only, never an icon.
    imageUrl: i % 3 === 2 ? null : `https://img.afrotransact.dev/services/svc_${i + 1}.webp`,
    href: `/services/svc_${i + 1}`
  }));
  const out: Catalog = { products, services, preorderOpen: n >= 100 };
  cache.set(n, out);
  return out;
}

/* ── Signals and eligibility ──────────────────────────────── */

interface Unit {
  name: string;
  key: string;
  products: CatalogProduct[];
  sellers: number;
}

const slug = (s: string) => s.toLowerCase().replace(/&/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const distinct = (items: readonly CatalogProduct[]) => new Set(items.map((p) => p.sellerName)).size;
const rank = (a: CatalogProduct, b: CatalogProduct) => b.reviewCount * b.rating - a.reviewCount * a.rating || a.ageDays - b.ageDays || a.atin.localeCompare(b.atin);

function group(products: readonly CatalogProduct[], by: (p: CatalogProduct) => string): Unit[] {
  const m = new Map<string, CatalogProduct[]>();
  for (const p of products) {
    // Case and alias variants (nigerian, Nigerian) are one unit.
    const k = by(p).trim().toLowerCase();
    if (!k) continue;
    m.set(k, [...(m.get(k) ?? []), p]);
  }
  return [...m.values()]
    .map((items) => ({ name: by(items[0] as CatalogProduct), key: slug(by(items[0] as CatalogProduct)), products: [...items].sort(rank), sellers: distinct(items) }))
    .sort((a, b) => b.products.length - a.products.length || a.name.localeCompare(b.name));
}

export interface Signals {
  departments: Unit[];
  communities: Unit[];
}
export function signalsOf(catalog: Catalog): Signals {
  return { departments: group(catalog.products, (p) => p.department), communities: group(catalog.products, (p) => p.community) };
}

export interface EvalContext {
  now: Date;
  signedIn: boolean;
  hasZip: boolean;
  /** Providers near the visitor's ZIP (city match), computed by the caller. */
  nearServices: ServiceCard[];
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

interface Verdict {
  shown: boolean;
  reason: HomeReasonCode;
  detail: string;
  qualifying: number;
  required: number;
  units?: string[];
}

function verdict(r: HomeModuleRule, catalog: Catalog, sig: Signals, ctx: EvalContext): Verdict {
  const v = (shown: boolean, reason: HomeReasonCode, detail: string, qualifying: number, required: number, units: string[] = []): Verdict => ({ shown, reason, detail, qualifying, required, units });
  const now = ctx.now.getTime();
  if (!r.enabled) return v(false, 'disabled', `${r.name} is switched off in Home settings.`, 0, 0);
  if (r.startsAt && Date.parse(r.startsAt) > now) return v(false, 'scheduled-later', `${r.name} starts on ${r.startsAt.slice(0, 10)}.`, 0, 0);
  if (r.endsAt && Date.parse(r.endsAt) <= now) return v(false, 'scheduled-ended', `${r.name} ended on ${r.endsAt.slice(0, 10)}.`, 0, 0);
  const products = catalog.products;
  switch (r.key) {
    case 'hero':
      return v(true, products.length === 0 || !heroSlides(catalog).length ? 'fallback' : 'ok', heroSlides(catalog).length ? 'An approved slide with a real photo is in its schedule.' : 'No approved slide with a real photo: the text-only brand hero is shown.', heroSlides(catalog).length, 1);
    case 'continue-shopping':
      if (!ctx.signedIn) return v(false, 'needs-sign-in', `${r.name} is for signed-in shoppers with past orders; this preview is a guest.`, 0, r.minProducts);
      return products.length >= r.minProducts ? v(true, 'ok', `${r.name} shows the shopper's bought products.`, products.length, r.minProducts) : v(false, 'no-signal', `${r.name} needs ${plural(r.minProducts, 'bought product', 'bought products')} still in stock.`, products.length, r.minProducts);
    case 'recently-viewed':
      return products.length >= r.minProducts ? v(true, 'ok', `${r.name} fills from the browser's own history; it takes no space until the shopper has viewed something.`, products.length, r.minProducts) : v(false, 'no-signal', `${r.name} needs at least ${r.minProducts} product in the catalogue.`, products.length, r.minProducts);
    case 'departments': {
      const q = sig.departments.filter((d) => d.products.length >= r.unitMinProducts);
      return q.length >= r.minUnits ? v(true, 'ok', `${plural(q.length, 'department has', 'departments have')} eligible products.`, q.length, r.minUnits, q.map((d) => d.name)) : v(false, 'not-enough-units', `${r.name} needs ${r.minUnits} departments with at least ${r.unitMinProducts} eligible product; ${q.length} qualify.`, q.length, r.minUnits, q.map((d) => d.name));
    }
    case 'communities': {
      const q = sig.communities.filter((c) => c.products.length >= r.unitMinProducts && c.sellers >= r.unitMinSellers);
      return q.length >= r.minUnits
        ? v(true, 'ok', `${q.length} communities have ${r.unitMinProducts} or more eligible products from ${r.unitMinSellers} or more sellers.`, q.length, r.minUnits, q.map((c) => c.name))
        : v(false, 'not-enough-units', `${r.name} needs ${r.minUnits} communities with ${r.unitMinProducts} eligible products and ${r.unitMinSellers} sellers each; ${q.length} qualify.`, q.length, r.minUnits, q.map((c) => c.name));
    }
    case 'deals': {
      const deals = products.filter((p) => p.listPriceMinor && p.listPriceMinor > p.priceMinor && ((p.listPriceMinor - p.priceMinor) * 100) / p.listPriceMinor >= 10);
      if (deals.length < r.minProducts) return v(false, 'not-enough-products', `${r.name} needs ${r.minProducts} products at least 10 percent off; ${deals.length} qualify.`, deals.length, r.minProducts);
      if (distinct(deals) < r.minSellers) return v(false, 'not-enough-sellers', `${r.name} needs ${r.minSellers} sellers; ${distinct(deals)} have a deal.`, distinct(deals), r.minSellers);
      return v(true, 'ok', `${deals.length} products are at least 10 percent off.`, deals.length, r.minProducts);
    }
    case 'groceries': {
      const g = products.filter((p) => p.department === 'Groceries & Food');
      return g.length >= r.minProducts ? v(true, 'ok', `${g.length} grocery products are eligible.`, g.length, r.minProducts) : v(false, 'not-enough-products', `${r.name} needs ${r.minProducts} eligible grocery products; ${g.length} qualify.`, g.length, r.minProducts);
    }
    case 'new-arrivals': {
      const fresh = products.filter((p) => !r.maxAgeDays || p.ageDays <= r.maxAgeDays);
      if (!products.length || !fresh.length) return v(false, 'stale', `${r.name} needs products listed in the last ${r.maxAgeDays} days; none qualify.`, 0, r.minProducts);
      if (fresh.length < r.minProducts) return v(false, 'not-enough-products', `${r.name} needs ${r.minProducts} recent products; ${fresh.length} qualify.`, fresh.length, r.minProducts);
      return distinct(fresh) >= r.minSellers ? v(true, 'ok', `${fresh.length} products were listed in the last ${r.maxAgeDays} days.`, fresh.length, r.minProducts) : v(false, 'not-enough-sellers', `${r.name} needs ${r.minSellers} seller.`, distinct(fresh), r.minSellers);
    }
    case 'top-sellers': {
      const rated = products.filter((p) => p.rating > 0 && p.reviewCount > 0);
      if (rated.length < r.minProducts) return v(false, 'not-enough-products', `${r.name} needs ${r.minProducts} products with a real rating; ${rated.length} qualify.`, rated.length, r.minProducts);
      return distinct(rated) >= r.minSellers ? v(true, 'ok', `${rated.length} products have a real rating.`, rated.length, r.minProducts) : v(false, 'not-enough-sellers', `${r.name} needs ${r.minSellers} sellers; ${distinct(rated)} have rated products.`, distinct(rated), r.minSellers);
    }
    case 'services': {
      if (ctx.nearServices.length >= r.minProducts && ctx.hasZip) return v(true, 'ok', `${plural(ctx.nearServices.length, 'provider serves', 'providers serve')} the visitor's ZIP: shown as Services near you.`, ctx.nearServices.length, r.minProducts);
      if (catalog.services.length >= r.minUnits) return v(true, 'ok', `${catalog.services.length} providers in the market: shown as Popular services${ctx.hasZip ? '' : ' (no ZIP)'}.`, catalog.services.length, r.minUnits);
      return v(false, 'no-provider', `${r.name} needs 1 provider near the ZIP or ${r.minUnits} in the market; ${catalog.services.length} exist.`, catalog.services.length, r.minUnits);
    }
    case 'pre-order':
      return catalog.preorderOpen ? v(true, 'ok', 'A pre-order window is open.', 1, 1) : v(false, 'no-open-window', `${r.name} needs an open pre-order window; none is open.`, 0, 1);
    case 'plus':
      return v(true, 'ok', 'AfroTransact Plus is on sale in this market.', 1, 1);
    case 'sell':
      return v(true, 'ok', 'Always shown to guests and sellers-to-be, low on the page.', 1, 1);
  }
}

/* ── Composition ──────────────────────────────────────────── */

function heroSlides(catalog: Catalog): HeroSlide[] {
  if (catalog.products.length < 100) return [];
  return [
    { id: 'hero_1', theme: 'ink', kicker: 'AfroTransact', title: 'Everything for the table', sub: 'Pantry staples and fresh produce from sellers near you.', cta: 'Shop groceries', href: '/s?dept=Groceries+%26+Food', label: 'AfroTransact', imageUrl: 'https://img.afrotransact.dev/products/AT00000001.webp' },
    { id: 'hero_2', theme: 'gold', kicker: 'AfroTransact', title: 'Find a stylist near you', sub: 'Braiding, tailoring and more from local providers.', cta: 'Browse services', href: '/services', label: 'AfroTransact', imageUrl: 'https://img.afrotransact.dev/products/AT00000002.webp' }
  ];
}

const BAND_ORDER = { top: 0, upper: 1, middle: 2, lower: 3 } as const;

export interface ComposeInput {
  catalog: Catalog;
  settings: HomeSettings;
  seed: string;
  now: Date;
  signedIn: boolean;
  loc: DeliveryLocation;
  /** True when the visitor chose a ZIP (a guest with none is a first visit). */
  hasZip: boolean;
  locale?: string;
}
export interface Composed {
  home: Home;
  decisions: HomeDecision[];
}

const nearOf = (services: ServiceCard[], loc: DeliveryLocation) => services.filter((s) => s.city === loc.city);

/** Pure: the same input is always the same page. */
export function composeDynamicHome(input: ComposeInput): Composed {
  const { catalog, settings, seed, now, loc } = input;
  const sig = signalsOf(catalog);
  const ctx: EvalContext = { now, signedIn: input.signedIn, hasZip: input.hasZip, nearServices: nearOf(catalog.services, loc) };
  const verdicts = new Map<HomeModuleKey, Verdict>();
  for (const r of settings.modules) verdicts.set(r.key, verdict(r, catalog, sig, ctx));

  // Order: band, then weight plus a small seeded jitter so neighbouring modules swap between visits (none for the default seed).
  const jitter = rng(seed, 'order');
  const shown = settings.modules
    .filter((r) => verdicts.get(r.key)?.shown)
    .map((r) => ({ r, k: BAND_ORDER[r.band] * 1000 - (r.weight + (seed === DEFAULT_SEED ? 0 : jitter() * 12)) }))
    .sort((a, b) => a.k - b.k)
    .map((x) => x.r);

  const used = new Set<string>();
  const claim = (items: CatalogProduct[], n: number, salt: string, min = 4): ProductSummary[] | null => {
    const free = items.filter((p) => !used.has(p.atin));
    const chosen = pick(free, n, seed, salt);
    if (chosen.length < min) return null;
    for (const p of chosen) used.add(p.atin);
    return chosen.map(strip);
  };
  const strip = ({ ageDays: _a, ...p }: CatalogProduct): ProductSummary => p;

  const blocks: HomeBlock[] = [];
  const dropped = new Map<HomeModuleKey, HomeReasonCode>();
  const modulePlace = new Map<HomeModuleKey, number>();
  const heroes = heroSlides(catalog);

  const rail = (key: HomeModuleKey, id: string, titleKey: string, title: string, href: string, items: ProductSummary[] | null, extra: Partial<HomeBlock> = {}): HomeBlock | null =>
    items ? { id, type: 'rail', sponsored: false, scope: 'shared', titleKey, title, rail: { id, title, href, items }, tracking: { module: key, position: 0, variant: 'control' }, ...extra } : null;

  for (const r of shown) {
    let b: HomeBlock | null = null;
    switch (r.key) {
      case 'hero':
        if (!heroes.length) {
          b = {
            id: 'brand-hero',
            type: 'brand-hero',
            sponsored: false,
            scope: 'shared',
            titleKey: 'home.brandHero.title',
            title: 'African and Caribbean food, fashion and services, from sellers near you',
            sub: catalog.products.length === 0 ? 'The marketplace is opening. Sellers and providers are joining now.' : undefined,
            cta: catalog.products.length === 0 ? 'Sell on AfroTransact' : 'Shop now',
            href: catalog.products.length === 0 ? '/sell' : '/s',
            tracking: { module: 'hero', position: 0, variant: 'control' }
          };
        }
        break;
      case 'departments': {
        const top = sig.departments.slice(0, 8);
        const cells = pick(top, 8, seed, 'dept').map((d) => ({
          key: d.key,
          label: d.name,
          href: `/s?dept=${encodeURIComponent(d.name)}`,
          // Photo: the best eligible product of the department; a visit seed picks among its top three.
          imageUrl: (pick(d.products.slice(0, 3), 1, seed, `dept-img-${d.key}`)[0] as CatalogProduct).imageUrl,
          count: d.products.length
        }));
        b = { id: 'department-tiles', type: 'department-tiles', sponsored: false, scope: 'shared', titleKey: 'home.departments.title', title: 'Shop by department', cells, tracking: { module: 'departments', position: 0, variant: 'control' } };
        break;
      }
      case 'communities': {
        const q = sig.communities.filter((c) => c.products.length >= r.unitMinProducts && c.sellers >= r.unitMinSellers);
        const cells = pick(q, 6, seed, 'comm').map((c) => ({
          key: c.key,
          label: c.name,
          href: `/communities/${c.key}`,
          imageUrl: (pick(c.products.slice(0, 3), 1, seed, `comm-img-${c.key}`)[0] as CatalogProduct).imageUrl,
          count: c.products.length
        }));
        b = { id: 'community-tiles', type: 'community-tiles', sponsored: false, scope: 'shared', titleKey: 'home.communities.title', title: 'Shop by community', cells, tracking: { module: 'communities', position: 0, variant: 'control' } };
        break;
      }
      case 'services': {
        const near = ctx.hasZip && ctx.nearServices.length >= r.minProducts;
        const list = near ? ctx.nearServices : catalog.services;
        b = {
          id: 'service-cards',
          type: 'service-cards',
          sponsored: false,
          scope: 'shared',
          titleKey: near ? 'home.services.near' : 'home.services.popular',
          titleParams: { city: loc.city },
          title: near ? `Services near ${loc.city}` : 'Popular services',
          href: '/services',
          services: pick(list, 6, seed, 'svc'),
          tracking: { module: 'services', position: 0, variant: 'control' }
        };
        break;
      }
      case 'deals': {
        const deals = catalog.products.filter((p) => p.listPriceMinor && p.listPriceMinor > p.priceMinor && ((p.listPriceMinor - p.priceMinor) * 100) / p.listPriceMinor >= 10);
        b = rail('deals', 'plc_deals', 'home.deals.title', 'Deals of the day', '/s?sort=rating', claim(deals, 12, 'deals', 6), { type: 'deals' });
        break;
      }
      case 'groceries':
        b = rail('groceries', 'rail_groceries', 'home.groceries.title', 'Groceries', '/s?dept=Groceries+%26+Food', claim(catalog.products.filter((p) => p.department === 'Groceries & Food'), 12, 'groc', 6));
        break;
      case 'new-arrivals':
        b = rail('new-arrivals', 'rail_new', 'home.newArrivals.title', 'New arrivals', '/s?sort=newest', claim([...catalog.products.filter((p) => !r.maxAgeDays || p.ageDays <= r.maxAgeDays)].sort((a, c) => a.ageDays - c.ageDays || a.atin.localeCompare(c.atin)), 12, 'new', 4));
        break;
      case 'top-sellers':
        b = rail('top-sellers', 'rail_top', 'home.topSellers.title', 'Top sellers', '/s?sort=rating', claim(catalog.products.filter((p) => p.rating > 0 && p.reviewCount > 0).sort(rank), 12, 'top', 4));
        break;
      case 'continue-shopping':
        b = rail('continue-shopping', 'based-on-orders', 'home.continue.title', 'Continue shopping', '/account/orders', claim(catalog.products.slice(0, 12), 6, 'cont', 2), { type: 'based-on-orders', scope: 'visitor' });
        break;
      case 'recently-viewed':
        b = { id: 'recently-viewed', type: 'recently-viewed', sponsored: false, scope: 'visitor', tracking: { module: 'recently-viewed', position: 0, variant: 'control' } };
        break;
      case 'pre-order':
        b = { id: 'plc_preorders', type: 'preorders', sponsored: false, scope: 'shared', tracking: { module: 'pre-order', position: 0, variant: 'control' } };
        break;
      case 'plus':
        b = { id: 'plc_membership', type: 'membership-promo', sponsored: false, scope: 'shared', titleKey: 'home.plus.title', title: 'AfroTransact Plus: free delivery and early access', sub: 'Members get free delivery on every order.', cta: 'Try Plus', href: '/membership', tone: 'sand', tracking: { module: 'plus', position: 0, variant: 'control' } };
        break;
      case 'sell':
        b = { id: 'sell-band', type: 'sell-band', sponsored: false, scope: 'shared', titleKey: 'home.sell.title', title: 'Sell on AfroTransact', sub: 'Reach shoppers who are looking for what you make.', cta: 'Start selling', href: '/sell', tone: 'ink', tracking: { module: 'sell', position: 0, variant: 'control' } };
        break;
    }
    if (b) blocks.push(b);
    else if (r.key !== 'hero') dropped.set(r.key, 'page-full');
  }

  // Cap: at most `maxModules`; sponsored modules never exceed 1 per `organicPerSponsored` organic ones.
  const withSponsored = insertSponsored(blocks, catalog, settings.organicPerSponsored, seed, claim);
  const final = withSponsored.slice(0, Math.max(1, settings.maxModules));
  final.forEach((b, i) => {
    if (b.tracking) b.tracking = { ...b.tracking, position: i + 1 };
  });
  for (const b of final) {
    const key = b.tracking?.module as HomeModuleKey | undefined;
    if (key && !modulePlace.has(key)) modulePlace.set(key, final.indexOf(b) + 1);
  }

  const decisions: HomeDecision[] = HOME_MODULE_KEYS.map((key) => {
    const r = settings.modules.find((m) => m.key === key) as HomeModuleRule;
    const vd = verdicts.get(key) as Verdict;
    const place = modulePlace.get(key) ?? (key === 'hero' && vd.shown ? 1 : null);
    const lost = vd.shown && place === null;
    return {
      module: key,
      label: r.name,
      shown: vd.shown && !lost,
      reason: lost ? (dropped.get(key) ?? 'page-full') : vd.reason,
      detail: lost ? `${r.name} qualified, but the products it needed were already used higher on the page or the page is full.` : vd.detail,
      metrics: { qualifying: vd.qualifying, required: vd.required },
      units: vd.units ?? [],
      pinned: false,
      position: place
    };
  });

  const home: Home = {
    announcements: [],
    hero: heroes,
    tiles: [],
    blocks: final,
    deliverTo: loc,
    version: 2,
    seed
  };
  return { home, decisions };
}

function insertSponsored(
  organic: HomeBlock[],
  catalog: Catalog,
  perSponsored: number,
  seed: string,
  claim: (items: CatalogProduct[], n: number, salt: string, min?: number) => ProductSummary[] | null
): HomeBlock[] {
  const allowed = Math.floor(organic.length / Math.max(1, perSponsored));
  if (allowed < 1 || catalog.products.length < 100) return organic;
  const items = claim(catalog.products.filter((p) => p.sellerKind === 'SELLER'), 6, 'sponsored', 2);
  if (!items) return organic;
  const sponsoredBlock: HomeBlock = {
    id: 'plc_sp_products',
    type: 'sponsored-products',
    sponsored: true,
    advertiser: 'Marketplace sellers',
    title: 'Sponsored products from sellers',
    href: '/s',
    searchId: `srch_${seed}`,
    rail: { id: 'plc_sp_products', title: 'Sponsored products from sellers', href: '/s', items: items.map((p, i) => ({ ...p, sponsor: { advertiser: p.sellerName, campaignId: 'cmp_mock', slot: i + 1, adToken: `tok-${p.atin}` } })) }
  };
  const at = Math.min(organic.length, perSponsored + 1);
  return [...organic.slice(0, at), sponsoredBlock, ...organic.slice(at)];
}

/* ── Navigation (departments, communities, services) ─────── */

/** The department bar, footer and routes: the same eligible-only counts as the home, same community threshold. */
export function navigationOf(catalog: Catalog, settings: HomeSettings): SiteNavigation {
  const sig = signalsOf(catalog);
  const comm = settings.modules.find((m) => m.key === 'communities') as HomeModuleRule;
  const qualifying = sig.communities.filter((c) => c.products.length >= comm.unitMinProducts && c.sellers >= comm.unitMinSellers);
  const communities: NavCommunity[] = comm.enabled && qualifying.length >= comm.minUnits ? qualifying.map((c) => ({ key: c.key, label: c.name, count: c.products.length, href: `/communities/${c.key}` })) : [];
  const departments: NavDepartment[] = sig.departments.map((d) => ({ key: d.key, name: d.name, count: d.products.length, href: `/s?dept=${encodeURIComponent(d.name)}` }));
  return {
    departments,
    communities,
    servicesAvailable: catalog.services.length > 0,
    brandAvailable: catalog.products.some((p) => p.sellerKind === 'PLATFORM'),
    dealsAvailable: verdict(settings.modules.find((m) => m.key === 'deals') as HomeModuleRule, catalog, sig, { now: new Date(), signedIn: false, hasZip: false, nearServices: [] }).shown
  };
}

/** Staff preview: the same composition for a chosen catalogue, ZIP, sign-in state, locale and date. */
export function previewHome(q: HomePreviewQuery, input: Omit<ComposeInput, 'seed' | 'now' | 'signedIn' | 'hasZip'> & { seed?: string; now: Date }): HomePreview {
  const asOf = q.asOf ? new Date(`${q.asOf}T00:00:00Z`) : input.now;
  const seed = q.seed?.trim() || DEFAULT_SEED;
  const out = composeDynamicHome({ ...input, seed, now: asOf, signedIn: q.signedIn ?? false, hasZip: !!q.zip?.trim() });
  const catalog = input.catalog;
  return {
    home: out.home,
    decisions: out.decisions,
    seed,
    asOf: asOf.toISOString(),
    signals: {
      products: catalog.products.length,
      departments: signalsOf(catalog).departments.length,
      communities: signalsOf(catalog).communities.length,
      sellers: distinct(catalog.products),
      providers: catalog.services.length
    }
  };
}
