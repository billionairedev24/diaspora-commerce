/* The mock BFF's dynamic home (docs/engineering/dynamic-home.md): a TypeScript model of the gateway's eligibility engine, used only by
   the MSW handlers, the Playwright mock BFF and the staff preview mock. The real gateway composes in Go; this mirrors its contract
   (contracts/openapi/gateway-bff.yaml `getHome`, gateway-bff-staff.yaml `previewStaffHome`) so the storefront and the staff console
   can be built and tested against 0 / 10 / 100 / 10,000-product catalogues.
   Defaults (all staff-editable): communities only when 3 or more have 8 or more eligible products and 2 or more sellers; departments
   with nothing in them are not linked; Services when any provider exists ("near you" with a ZIP, else "popular"); at zero products a
   text-only brand hero and a sell band; at most 1 sponsored module per 3 organic ones; a product appears once on the page.
   Variety: the seed is `visitor + visit window`. It picks WHICH eligible items fill each module (quality-weighted shuffle of a pool
   larger than the module shows, items of earlier loads down-weighted, at most `sellerShare` items of one seller) and rotates modules
   inside the discovery band. The same seed always gives the same page; no seed (crawlers, no visitor id) gives the deterministic default. */
import { CRAWLER_HEADER, HOME_SEEN_HEADER, HOME_VARIANT_HEADER,VISIT_WINDOW_HEADER, type VisitHeaders, VISITOR_HEADER } from '../home-visit';
import { type ProductSummary } from '../schemas/catalog';
import { type HeroSlide, type Home, type HomeBlock, type HomeCell, type HomeModuleDecision, type ServiceCard } from '../schemas/home';
import { type DeliveryLocation } from '../schemas/location';
import { type LocaleLabels, type SiteCommunity, type SiteDepartment, type SiteNavigation, type SiteSitemap } from '../schemas/site-nav';
import { type HomeBand, type HomeCaps, type HomeModuleRule, type HomePreview, type HomePreviewQuery, type HomeSettings } from '../staff-home';

/* ── Settings ─────────────────────────────────────────────── */

export const MODULE_KEYS = [
  'hero',
  'continue-shopping',
  'recently-viewed',
  'department-tiles',
  'preorder',
  'deals',
  'services-near-you',
  'groceries-rail',
  'new-arrivals',
  'communities',
  'top-sellers',
  'plus'
] as const;
export type ModuleKey = (typeof MODULE_KEYS)[number];

export const MODULE_NAMES: Record<ModuleKey, string> = {
  hero: 'Hero campaigns',
  'continue-shopping': 'Continue shopping',
  'recently-viewed': 'Recently viewed',
  'department-tiles': 'Shop by department',
  preorder: 'Pre-order',
  deals: 'Deals',
  'services-near-you': 'Services',
  'groceries-rail': 'Groceries',
  'new-arrivals': 'New arrivals',
  communities: 'Shop by community',
  'top-sellers': 'Top sellers',
  plus: 'AfroTransact Plus'
};

const rule = (band: HomeBand, weight: number, over: Partial<HomeModuleRule> = {}): HomeModuleRule => ({
  enabled: true,
  minProducts: 0,
  minSellers: 0,
  minUnits: 0,
  unitMinProducts: 0,
  unitMinSellers: 0,
  maxAgeDays: 0,
  weight,
  band,
  ...over
});

export const DEFAULT_CAPS: HomeCaps = {
  maxModules: 12,
  minModules: 3,
  sellerShare: 2,
  sponsoredPerOrganic: 3,
  poolMultiplier: 3,
  noveltyLoads: 3,
  noveltyPenalty: 0.6,
  visitWindowMinutes: 30,
  hysteresis: 2,
  dealMinPercent: 10,
  navBarSize: 7
};

/** The defaults the owner chose; they equal DefaultHomeRules() in the gateway. */
export function defaultHomeSettings(): HomeSettings {
  return {
    version: 0,
    dynamic: null,
    effectiveDynamic: 'on',
    modules: {
      hero: rule('hero', 100),
      'continue-shopping': rule('personal', 90, { minProducts: 2, show: 6 }),
      'recently-viewed': rule('personal', 80, { minProducts: 1 }),
      'department-tiles': rule('discovery', 85, { minUnits: 2, unitMinProducts: 1, show: 8 }),
      preorder: rule('discovery', 75),
      deals: rule('discovery', 70, { minProducts: 6, minSellers: 2, show: 12 }),
      'services-near-you': rule('discovery', 65, { minProducts: 1, show: 6 }),
      'groceries-rail': rule('discovery', 60, { minProducts: 6, show: 12 }),
      'new-arrivals': rule('discovery', 55, { minProducts: 8, minSellers: 1, maxAgeDays: 30, show: 12 }),
      communities: rule('discovery', 50, { minUnits: 3, unitMinProducts: 8, unitMinSellers: 2, show: 6 }),
      'top-sellers': rule('discovery', 45, { minProducts: 8, minSellers: 2, show: 12 }),
      plus: rule('promo', 30)
    },
    moduleNames: { ...MODULE_NAMES },
    caps: { ...DEFAULT_CAPS },
    departmentLabels: {},
    updatedAt: null,
    updatedBy: null
  };
}

/** Mock state shared by the storefront and staff handlers: the staff settings and which synthetic catalogue (if any) the mock home serves. */
export const homeMock: { settings: HomeSettings; catalogue: number | null; versions: Array<{ version: number; reason: string; changedBy: string; changedAt: string; revertOf: number | null; changes: Array<{ field: string; from: unknown; to: unknown }>; snapshot: HomeSettings }> } = {
  settings: defaultHomeSettings(),
  catalogue: null,
  versions: []
};

/** The catalogue size the mock BFF serves: set by a test, or MOCK_HOME_CATALOGUE for a dev server; null keeps the classic mock home. */
export function mockCatalogueSize(): number | null {
  if (homeMock.catalogue !== null) return homeMock.catalogue;
  const env = typeof process !== 'undefined' ? process.env.MOCK_HOME_CATALOGUE : undefined;
  return env !== undefined && env !== '' && !Number.isNaN(Number(env)) ? Number(env) : null;
}

/** A browser test picks its own catalogue per context with this cookie (the mock BFF is one process for every parallel test). */
export const MOCK_CATALOGUE_COOKIE = 'at_mock_catalogue';

/** The catalogue a request sees: its own cookie, else the process-wide setting (a unit test or MOCK_HOME_CATALOGUE), else null (the classic mock home). */
export function catalogueOf(headers: Headers): number | null {
  const m = new RegExp(`(?:^|;\\s*)${MOCK_CATALOGUE_COOKIE}=(\\d+)`).exec(headers.get('cookie') ?? '');
  return m ? Number(m[1]) : mockCatalogueSize();
}

export function resetHomeMock(): void {
  homeMock.settings = defaultHomeSettings();
  homeMock.catalogue = null;
  homeMock.versions = [];
}

/* ── Seeded randomness ────────────────────────────────────── */

/** The seed meaning "no variety": crawlers and anyone without a visitor id get exactly this page. */
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

/** The short key the page reports for an item (`visit.seen`); not the product id. */
export const seenKey = (id: string): string => hash(`seen|${id}`).toString(36).slice(0, 5);

export interface DrawOptions<T> {
  seed: string;
  salt: string;
  /** Ranked best first. */
  items: readonly T[];
  count: number;
  idOf: (t: T) => string;
  sellerOf?: (t: T) => string;
  seen?: ReadonlySet<string>;
  caps: Pick<HomeCaps, 'poolMultiplier' | 'noveltyPenalty' | 'sellerShare'>;
}

/**
 * Picks `count` items. The default seed takes the best in rank order. Any other seed draws from the best `count * poolMultiplier`
 * by a seeded, quality-weighted shuffle (Efraimidis-Spirakis): a better rank is likelier, an item shown on an earlier load keeps
 * only `1 - noveltyPenalty` of its chance, and one seller supplies at most `sellerShare` items.
 */
export function draw<T>(o: DrawOptions<T>): T[] {
  const { seed, items, count, caps } = o;
  const share = Math.max(1, caps.sellerShare);
  const out: T[] = [];
  const perSeller = new Map<string, number>();
  const take = (t: T): boolean => {
    const seller = o.sellerOf?.(t);
    if (seller !== undefined && (perSeller.get(seller) ?? 0) >= share) return false;
    if (seller !== undefined) perSeller.set(seller, (perSeller.get(seller) ?? 0) + 1);
    out.push(t);
    return true;
  };
  if (seed === DEFAULT_SEED) {
    for (const t of items) {
      if (out.length >= count) break;
      take(t);
    }
    return out;
  }
  const pool = items.slice(0, Math.max(count, count * Math.max(1, caps.poolMultiplier)));
  const r = rng(seed, o.salt);
  const ranked = pool
    .map((t, i) => {
      const novelty = o.seen?.has(seenKey(o.idOf(t))) ? Math.max(0.01, 1 - caps.noveltyPenalty) : 1;
      const w = (1 / (1 + i * 0.08)) * novelty;
      return { t, k: Math.pow(r(), 1 / w) };
    })
    .sort((a, b) => b.k - a.k);
  for (const { t } of ranked) {
    if (out.length >= count) break;
    take(t);
  }
  // Too few after the seller share: fill from the rest in rank order rather than show a short row.
  for (const t of items) {
    if (out.length >= count) break;
    if (!out.includes(t)) take(t);
  }
  return out;
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
    title: SERVICE_NAMES[i % SERVICE_NAMES.length] as string,
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

/** Short display labels over the catalog's full names (staff can override them in Home settings). */
const SHORT_LABELS: Record<string, string> = {
  'groceries-food': 'Groceries',
  'fashion-accessories': 'Fashion',
  'beauty-personal-care': 'Beauty',
  'home-household': 'Home',
  'health-wellness': 'Health'
};

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
  caps: HomeCaps;
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

interface Verdict {
  shown: boolean;
  reason: string;
  detail: string;
  have: number;
  need: number;
  units?: string[];
}

const isDeal = (p: CatalogProduct, minPercent: number) => !!p.listPriceMinor && p.listPriceMinor > p.priceMinor && ((p.listPriceMinor - p.priceMinor) * 100) / p.listPriceMinor >= minPercent;

/** The rule table: shown or hidden, with a code and a sentence in staff words that names the module. */
export function verdict(key: ModuleKey, r: HomeModuleRule, name: string, catalog: Catalog, sig: Signals, ctx: EvalContext): Verdict {
  const v = (shown: boolean, reason: string, detail: string, have: number, need: number, units: string[] = []): Verdict => ({ shown, reason, detail, have, need, units });
  if (!r.enabled) return v(false, 'disabled-by-staff', `${name} is switched off in Home settings.`, 0, 0);
  const products = catalog.products;
  switch (key) {
    case 'hero':
      return heroSlides(catalog).length
        ? v(true, 'shown', 'An approved slide with a real photo is scheduled today.', heroSlides(catalog).length, 1)
        : v(true, 'shown', 'No approved slide with a real photo is scheduled: the text-only brand hero is shown instead.', 0, 1);
    case 'continue-shopping':
      if (!ctx.signedIn) return v(false, 'no-shopper-signal', `${name} needs a signed-in shopper with past orders; this view is a guest.`, 0, r.minProducts);
      return products.length >= r.minProducts ? v(true, 'shown', `${name} is built from the products the shopper bought.`, products.length, r.minProducts) : v(false, 'no-shopper-signal', `${name} needs ${plural(r.minProducts, 'bought product', 'bought products')} still in stock.`, products.length, r.minProducts);
    case 'recently-viewed':
      return products.length >= r.minProducts ? v(true, 'shown', `${name} fills from the browser's own history and takes no room until the shopper has viewed something.`, products.length, r.minProducts) : v(false, 'nothing-to-show', `${name} has no eligible product to remember.`, products.length, r.minProducts);
    case 'department-tiles': {
      const q = sig.departments.filter((d) => d.products.length >= Math.max(1, r.unitMinProducts));
      return q.length >= r.minUnits ? v(true, 'shown', `${plural(q.length, 'department has', 'departments have')} eligible products.`, q.length, r.minUnits, q.map((d) => d.name)) : v(false, 'not-enough-departments', `${name} needs ${r.minUnits} departments with at least ${Math.max(1, r.unitMinProducts)} eligible product; ${q.length} qualify.`, q.length, r.minUnits, q.map((d) => d.name));
    }
    case 'communities': {
      const q = sig.communities.filter((c) => c.products.length >= r.unitMinProducts && c.sellers >= r.unitMinSellers);
      return q.length >= r.minUnits
        ? v(true, 'shown', `${q.length} communities have ${r.unitMinProducts} or more eligible products from ${r.unitMinSellers} or more sellers.`, q.length, r.minUnits, q.map((c) => c.name))
        : v(false, 'not-enough-products', `${name} needs ${r.minUnits} communities with ${r.unitMinProducts} eligible products and ${r.unitMinSellers} sellers each; ${q.length} qualify.`, q.length, r.minUnits, q.map((c) => c.name));
    }
    case 'deals': {
      const deals = products.filter((p) => isDeal(p, ctx.caps.dealMinPercent));
      if (deals.length < r.minProducts) return v(false, 'not-enough-products', `${name} needs ${r.minProducts} products at least ${ctx.caps.dealMinPercent} percent off; ${deals.length} qualify.`, deals.length, r.minProducts);
      if (distinct(deals) < r.minSellers) return v(false, 'not-enough-sellers', `${name} needs products from ${r.minSellers} sellers; ${distinct(deals)} have a deal.`, distinct(deals), r.minSellers);
      return v(true, 'shown', `${deals.length} products are at least ${ctx.caps.dealMinPercent} percent off.`, deals.length, r.minProducts);
    }
    case 'groceries-rail': {
      const g = products.filter((p) => p.department === 'Groceries & Food');
      return g.length >= r.minProducts ? v(true, 'shown', `${g.length} grocery products are eligible.`, g.length, r.minProducts) : v(false, 'not-enough-products', `${name} needs ${r.minProducts} eligible grocery products; ${g.length} qualify.`, g.length, r.minProducts);
    }
    case 'new-arrivals': {
      const fresh = products.filter((p) => !r.maxAgeDays || p.ageDays <= r.maxAgeDays);
      if (products.length > 0 && fresh.length === 0) return v(false, 'not-fresh-enough', `The newest product is older than ${r.maxAgeDays} days.`, 0, r.minProducts);
      if (fresh.length < r.minProducts) return v(false, 'not-enough-products', `${name} needs ${r.minProducts} recent products; ${fresh.length} qualify.`, fresh.length, r.minProducts);
      return distinct(fresh) >= r.minSellers ? v(true, 'shown', `${fresh.length} products were listed in the last ${r.maxAgeDays || 'any number of'} days.`, fresh.length, r.minProducts) : v(false, 'not-enough-sellers', `${name} needs ${r.minSellers} seller.`, distinct(fresh), r.minSellers);
    }
    case 'top-sellers': {
      const rated = products.filter((p) => p.rating > 0 && p.reviewCount > 0);
      if (rated.length < r.minProducts) return v(false, 'not-enough-products', `${name} needs ${r.minProducts} products with a real rating; ${rated.length} qualify.`, rated.length, r.minProducts);
      return distinct(rated) >= r.minSellers ? v(true, 'shown', `${rated.length} products have a real rating.`, rated.length, r.minProducts) : v(false, 'not-enough-sellers', `${name} needs products from ${r.minSellers} sellers; ${distinct(rated)} have rated products.`, distinct(rated), r.minSellers);
    }
    case 'services-near-you': {
      const need = Math.max(1, r.minProducts);
      if (ctx.hasZip && ctx.nearServices.length >= need) return v(true, 'shown', `${plural(ctx.nearServices.length, 'provider serves', 'providers serve')} the visitor's ZIP: shown as Services near you.`, ctx.nearServices.length, need);
      if (catalog.services.length >= need) return v(true, 'shown', `${plural(catalog.services.length, 'provider exists', 'providers exist')} in the market: shown as Popular services${ctx.hasZip ? ' (none near this ZIP)' : ' (no ZIP)'}.`, catalog.services.length, need);
      return v(false, 'nothing-to-show', `${name} needs ${plural(need, 'provider', 'providers')} in the market; ${catalog.services.length} exist.`, catalog.services.length, need);
    }
    case 'preorder':
      return catalog.preorderOpen ? v(true, 'shown', 'A pre-order window is open.', 1, 1) : v(false, 'nothing-to-show', `${name} needs an open pre-order window; none is open.`, 0, 1);
    case 'plus':
      return v(true, 'shown', 'AfroTransact Plus is on sale in this market.', 1, 1);
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

/** Staff-edited season windows (S424): inside the window the listed modules gain weight. Mock data; the gateway reads them from content. */
export const SEASONS = [
  { name: 'Black Friday', startsAt: '2026-11-20T00:00:00Z', endsAt: '2026-12-02T00:00:00Z', boosted: ['deals'] as ModuleKey[], boost: 40 },
  { name: 'Detty December', startsAt: '2026-12-10T00:00:00Z', endsAt: '2027-01-06T00:00:00Z', boosted: ['services-near-you', 'groceries-rail'] as ModuleKey[], boost: 35 }
];
const seasonAt = (now: Date) => SEASONS.find((s) => Date.parse(s.startsAt) <= now.getTime() && now.getTime() < Date.parse(s.endsAt)) ?? null;

const BAND_ORDER: Record<HomeBand, number> = { hero: 0, personal: 1, discovery: 2, promo: 3, brand: 4 };

export interface ComposeInput {
  catalog: Catalog;
  settings: HomeSettings;
  /** `visitor|window`, or DEFAULT_SEED. */
  seed: string;
  /** Short keys of items shown on earlier loads. */
  seen?: ReadonlySet<string>;
  now: Date;
  signedIn: boolean;
  loc: DeliveryLocation;
  /** True when the visitor chose a ZIP (a guest with none is a first visit). */
  hasZip: boolean;
  variant?: string;
}
export interface Composed {
  home: Home;
  decisions: HomeModuleDecision[];
}

const nearOf = (services: ServiceCard[], loc: DeliveryLocation) => services.filter((s) => s.city === loc.city);

/** Seed of a visit: crawlers and visitors without a cookie get the deterministic default. */
export function seedOfVisit(v: VisitHeaders): string {
  if (v.crawler || !v.visitor) return DEFAULT_SEED;
  return `${v.visitor}|${v.window ?? ''}`;
}

/** Reads the visit headers (case-insensitive) off a request. */
export function visitOfHeaders(h: Headers): VisitHeaders {
  const seen = (h.get(HOME_SEEN_HEADER) ?? '').split(',').map((k) => k.trim()).filter(Boolean);
  return { visitor: h.get(VISITOR_HEADER) ?? undefined, window: h.get(VISIT_WINDOW_HEADER) ?? undefined, seen, variant: h.get(HOME_VARIANT_HEADER) ?? undefined, crawler: h.get(CRAWLER_HEADER) === '1' };
}

const departmentLabel = (key: string, name: string, settings: HomeSettings): { label: string; labels: LocaleLabels } => {
  const staff = settings.departmentLabels[key];
  const en = staff?.en || SHORT_LABELS[key] || name;
  return { label: en, labels: { ...staff, en } };
};

/** Pure: the same input is always the same page. */
export function composeDynamicHome(input: ComposeInput): Composed {
  const { catalog, settings, seed, now, loc } = input;
  const caps = settings.caps;
  const sig = signalsOf(catalog);
  const seen = input.seen ?? new Set<string>();
  const season = seasonAt(now);
  const ctx: EvalContext = { now, signedIn: input.signedIn, hasZip: input.hasZip, nearServices: nearOf(catalog.services, loc), caps };
  const variant = input.variant || 'control';
  const verdicts = new Map<ModuleKey, Verdict>();
  const keys = MODULE_KEYS.filter((k) => settings.modules[k] !== undefined);
  for (const k of keys) verdicts.set(k, verdict(k, settings.modules[k] as HomeModuleRule, settings.moduleNames[k] ?? MODULE_NAMES[k], catalog, sig, ctx));

  // Order: band, then weight (plus the season boost and, inside the discovery band, a seeded rotation; none for the default seed).
  const jitter = rng(seed, 'order');
  const ordered = keys
    .filter((k) => verdicts.get(k)?.shown)
    .map((k) => {
      const r = settings.modules[k] as HomeModuleRule;
      const band = r.band ?? 'discovery';
      const rotate = seed !== DEFAULT_SEED && band === 'discovery' ? jitter() * 24 : 0;
      const boost = season?.boosted.includes(k) ? season.boost : 0;
      return { k, r, score: BAND_ORDER[band] * 1000 - (r.weight + boost + rotate) };
    })
    .sort((a, b) => a.score - b.score);

  const used = new Set<string>();
  const strip = ({ ageDays: _a, ...p }: CatalogProduct): ProductSummary => p;
  const claim = (items: CatalogProduct[], n: number, salt: string, min = 4): ProductSummary[] | null => {
    const free = items.filter((p) => !used.has(p.atin));
    const chosen = draw({ seed, salt, items: free, count: n, idOf: (p) => p.atin, sellerOf: (p) => p.sellerName, seen, caps });
    if (chosen.length < Math.min(min, n)) return null;
    for (const p of chosen) used.add(p.atin);
    return chosen.map(strip);
  };
  const track = (module: string): HomeBlock['tracking'] => ({ module, position: 0, variant });

  const blocks: Array<HomeBlock & { key: ModuleKey | 'sell' | 'sponsored' | 'department-rail' }> = [];
  const dropped = new Map<ModuleKey, string>();
  const heroes = heroSlides(catalog);
  const show = (k: ModuleKey, fallback: number) => (settings.modules[k] as HomeModuleRule).show ?? fallback;

  const rail = (key: ModuleKey, id: string, titleKey: string, href: string, items: ProductSummary[] | null, extra: Partial<HomeBlock> = {}): HomeBlock | null =>
    items ? { id, type: 'rail', sponsored: false, scope: 'shared', titleKey, module: id.replace(/^rail_/, ''), rail: { id, title: '', href, items }, tracking: track(key), ...extra } : null;

  for (const { k, r } of ordered) {
    let b: HomeBlock | null = null;
    switch (k) {
      case 'hero':
        if (!heroes.length) {
          b = { id: 'brand-hero', type: 'brand-hero', sponsored: false, scope: 'shared', module: 'brand-hero', titleKey: 'home.brandHero.title', subKey: 'home.brandHero.sub', ctaKey: 'home.brandHero.cta', href: '/sell', textOnly: true, imageUrl: null, tracking: track('hero') };
        }
        break;
      case 'department-tiles': {
        const picked = draw({ seed, salt: 'dept', items: sig.departments.slice(0, 12), count: show(k, 8), idOf: (d) => d.key, caps: { ...caps, poolMultiplier: 1 } });
        const cells: HomeCell[] = picked.map((d) => {
          const { label, labels } = departmentLabel(d.key, d.name, settings);
          return {
            key: d.key,
            label,
            labels,
            labelKey: `dept.${d.key}`,
            href: `/s?dept=${encodeURIComponent(d.name)}`,
            // Photo: the best eligible product of the department; a visit seed picks among its top three.
            imageUrl: draw({ seed, salt: `dept-img-${d.key}`, items: d.products.slice(0, 3), count: 1, idOf: (p) => p.atin, caps: { ...caps, poolMultiplier: 1 } })[0]?.imageUrl ?? null,
            count: d.products.length
          };
        });
        b = { id: 'department-tiles', type: 'department-tiles', sponsored: false, scope: 'shared', module: 'department-tiles', titleKey: 'home.departments.title', cells, tracking: track(k) };
        break;
      }
      case 'communities': {
        const q = sig.communities.filter((c) => c.products.length >= r.unitMinProducts && c.sellers >= r.unitMinSellers);
        const picked = draw({ seed, salt: 'comm', items: q, count: show(k, 6), idOf: (c) => c.key, caps: { ...caps, poolMultiplier: 2 } });
        const cells: HomeCell[] = picked.map((c) => ({
          key: c.key,
          label: c.name,
          labels: { en: c.name },
          href: `/communities/${c.key}`,
          imageUrl: draw({ seed, salt: `comm-img-${c.key}`, items: c.products.slice(0, 3), count: 1, idOf: (p) => p.atin, caps: { ...caps, poolMultiplier: 1 } })[0]?.imageUrl ?? null,
          count: c.products.length
        }));
        b = { id: 'community-tiles', type: 'community-tiles', sponsored: false, scope: 'shared', module: 'communities', titleKey: 'home.communities.title', cells, tracking: track(k) };
        break;
      }
      case 'services-near-you': {
        const near = ctx.hasZip && ctx.nearServices.length >= Math.max(1, r.minProducts);
        const list = near ? ctx.nearServices : catalog.services;
        const picked = draw({ seed, salt: 'svc', items: list, count: show(k, 6), idOf: (s) => s.id, seen, caps: { ...caps, poolMultiplier: 2, sellerShare: 2 } });
        b = {
          id: 'service-cards',
          type: 'service-cards',
          sponsored: false,
          scope: 'shared',
          module: 'services-near-you',
          titleKey: near ? 'home.services.nearYou.title' : 'home.services.popular.title',
          titleParams: { city: loc.city },
          ctaKey: 'home.services.cta',
          href: '/services',
          services: picked,
          tracking: track(k)
        };
        break;
      }
      case 'deals':
        b = rail(k, 'plc_deals', 'home.deals.title', '/s?sort=price_asc', claim(catalog.products.filter((p) => isDeal(p, caps.dealMinPercent)), show(k, 12), 'deals', 6), { type: 'deals', module: 'deals' });
        break;
      case 'groceries-rail':
        b = rail(k, 'rail_groceries-rail', 'home.groceries.title', '/s?dept=Groceries+%26+Food', claim(catalog.products.filter((p) => p.department === 'Groceries & Food'), show(k, 12), 'groc', 6));
        break;
      case 'new-arrivals':
        b = rail(k, 'rail_new-arrivals', 'home.newArrivals.title', '/s?sort=newest', claim([...catalog.products.filter((p) => !r.maxAgeDays || p.ageDays <= r.maxAgeDays)].sort((a, c) => a.ageDays - c.ageDays || a.atin.localeCompare(c.atin)), show(k, 12), 'new', 4));
        break;
      case 'top-sellers':
        b = rail(k, 'rail_top-sellers', 'home.topSellers.title', '/s?sort=rating', claim(catalog.products.filter((p) => p.rating > 0 && p.reviewCount > 0).sort(rank), show(k, 12), 'top', 4));
        break;
      case 'continue-shopping':
        b = rail(k, 'based-on-orders', 'home.continue.title', '/account/orders', claim(catalog.products.slice(0, 12), show(k, 6), 'cont', 2), { type: 'based-on-orders', scope: 'visitor', module: 'continue-shopping' });
        break;
      case 'recently-viewed':
        b = { id: 'recently-viewed', type: 'recently-viewed', sponsored: false, scope: 'visitor', module: 'recently-viewed', titleKey: 'home.recent.title', tracking: track(k) };
        break;
      case 'preorder':
        b = { id: 'plc_preorders', type: 'preorders', sponsored: false, scope: 'shared', module: 'preorder', titleKey: 'home.preorder.title', tracking: track(k) };
        break;
      case 'plus':
        b = { id: 'plc_membership', type: 'membership-promo', sponsored: false, scope: 'shared', module: 'plus', titleKey: 'home.plus.title', bodyKey: 'home.plus.body', ctaKey: 'home.plus.cta', href: '/membership', tone: 'sand', tracking: track(k) };
        break;
    }
    if (b) blocks.push({ ...b, key: k });
    else if (k !== 'hero') dropped.set(k, 'nothing-to-show');
  }

  // Generated "Top in {department}" rails for the biggest departments of a big catalogue: never adjacent to each other.
  if (catalog.products.length >= 1000) {
    const biggest = sig.departments.slice(0, 2);
    biggest.forEach((d, i) => {
      const items = claim(d.products, 12, `topin-${d.key}`, 6);
      if (!items) return;
      const { label } = departmentLabel(d.key, d.name, settings);
      const at = Math.min(blocks.length, 3 + i * 3);
      blocks.splice(at, 0, { id: `rail_topin_${d.key}`, type: 'rail', sponsored: false, scope: 'shared', module: 'department-rail', titleKey: 'home.topIn.title', titleParams: { department: label }, rail: { id: `rail_topin_${d.key}`, title: '', href: `/s?dept=${encodeURIComponent(d.name)}`, items }, tracking: track('department-tiles'), key: 'department-rail' });
    });
  }

  // The sell band closes the page (it is the only thing besides the hero on an empty marketplace).
  const sell: HomeBlock & { key: 'sell' } = { id: 'sell-band', type: 'sell-band', sponsored: false, scope: 'shared', module: 'sell-band', titleKey: 'home.sellBand.title', bodyKey: 'home.sellBand.body', ctaKey: 'home.sellBand.cta', href: '/sell', tone: 'ink', tracking: track('sell-band'), key: 'sell' };

  // Sponsored: never more than 1 per `sponsoredPerOrganic` organic modules; it never fills a thin page.
  const withSponsored = insertSponsored(blocks, catalog, caps.sponsoredPerOrganic, seed, claim);
  // Cap: at most `maxModules` (the sell band is reserved a place); trimmed modules are reported as page-full.
  const room = Math.max(1, caps.maxModules - 1);
  const kept = withSponsored.slice(0, room);
  for (const b of withSponsored.slice(room)) if (b.key !== 'sponsored' && b.key !== 'department-rail' && b.key !== 'sell') dropped.set(b.key, 'page-full');
  const final: HomeBlock[] = [...kept, sell].map(({ key: _k, ...b }) => b);
  final.forEach((b, i) => {
    if (b.tracking) b.tracking = { ...b.tracking, position: i + 1 };
  });
  const placeOf = new Map<string, number>();
  [...kept, sell].forEach((b, i) => {
    if (b.tracking && !placeOf.has(b.tracking.module)) placeOf.set(b.tracking.module, i + 1);
  });

  const decisions: HomeModuleDecision[] = keys.map((k) => {
    const r = settings.modules[k] as HomeModuleRule;
    const vd = verdicts.get(k) as Verdict;
    const name = settings.moduleNames[k] ?? MODULE_NAMES[k];
    const place = placeOf.get(k) ?? null;
    const lost = vd.shown && place === null;
    const reason = lost ? (dropped.get(k) ?? 'nothing-to-show') : vd.reason;
    return {
      module: k,
      label: name,
      shown: vd.shown && !lost,
      order: place ?? undefined,
      reason,
      detail: lost ? (reason === 'page-full' ? `${name} qualified, but the page already holds ${caps.maxModules} modules.` : `${name} qualified, but the products it needed were already used higher on the page.`) : vd.detail,
      have: vd.have,
      need: vd.need,
      units: vd.units ?? [],
      band: r.band
    };
  });

  const shownIds = new Set<string>();
  for (const b of final) for (const p of [...(b.rail?.items ?? []), ...(b.products ?? [])]) shownIds.add(p.atin);
  for (const b of final) for (const s of b.services ?? []) shownIds.add(s.id);
  const home: Home = {
    announcements: [],
    hero: heroes,
    tiles: [],
    blocks: final,
    deliverTo: loc,
    version: 2,
    generatedAt: now.toISOString(),
    locale: 'en',
    variant,
    visit: { window: seed === DEFAULT_SEED ? '' : seed.split('|')[1] ?? '', seen: [...shownIds].map(seenKey).slice(0, 60), deterministic: seed === DEFAULT_SEED }
  };
  return { home, decisions };
}

function insertSponsored<B extends HomeBlock & { key: string }>(
  organic: B[],
  catalog: Catalog,
  perSponsored: number,
  seed: string,
  claim: (items: CatalogProduct[], n: number, salt: string, min?: number) => ProductSummary[] | null
): Array<B | (HomeBlock & { key: 'sponsored' })> {
  const allowed = Math.floor(organic.length / Math.max(1, perSponsored));
  if (allowed < 1 || catalog.products.length < 100) return organic;
  const items = claim(catalog.products.filter((p) => p.sellerKind === 'SELLER'), 6, 'sponsored', 2);
  if (!items) return organic;
  const sponsoredBlock: HomeBlock & { key: 'sponsored' } = {
    id: 'plc_sp_products',
    type: 'sponsored-products',
    sponsored: true,
    advertiser: 'Marketplace sellers',
    title: 'Sponsored products from sellers',
    href: '/s',
    searchId: `srch_${seed}`,
    rail: { id: 'plc_sp_products', title: 'Sponsored products from sellers', href: '/s', items: items.map((p, i) => ({ ...p, sponsor: { advertiser: p.sellerName, campaignId: 'cmp_mock', slot: i + 1, adToken: `tok-${p.atin}` } })) },
    key: 'sponsored'
  };
  const at = Math.min(organic.length, perSponsored + 1);
  return [...organic.slice(0, at), sponsoredBlock, ...organic.slice(at)];
}

/* ── Navigation (departments, communities, services) ─────── */

function communityRule(settings: HomeSettings): HomeModuleRule {
  return settings.modules.communities ?? { enabled: true, minProducts: 0, minSellers: 0, minUnits: 3, unitMinProducts: 8, unitMinSellers: 2, maxAgeDays: 0, weight: 50 };
}

/** Communities above the staff thresholds: the same rule as the home module, so the footer, the routes and the home cannot disagree. */
export function visibleCommunities(catalog: Catalog, settings: HomeSettings): SiteCommunity[] {
  const comm = communityRule(settings);
  const qualifying = signalsOf(catalog).communities.filter((c) => c.products.length >= comm.unitMinProducts && c.sellers >= comm.unitMinSellers);
  if (!comm.enabled || qualifying.length < comm.minUnits) return [];
  return qualifying.map((c) => ({ key: c.key, label: c.name, labels: { en: c.name }, count: c.products.length, sellers: c.sellers, href: `/communities/${c.key}`, imageUrl: c.products[0]?.imageUrl ?? null, imageSource: 'product' as const }));
}

/** The department bar, footer and routes: the same eligible-only counts as the home, same community threshold. */
export function navigationOf(catalog: Catalog, settings: HomeSettings, now: Date = new Date()): SiteNavigation {
  const sig = signalsOf(catalog);
  const all: SiteDepartment[] = sig.departments
    .filter((d) => d.products.length > 0)
    .map((d) => {
      const { label, labels } = departmentLabel(d.key, d.name, settings);
      return { key: d.key, name: d.name, label, labels, labelKey: `dept.${d.key}`, count: d.products.length, sellers: d.sellers, href: `/s?dept=${encodeURIComponent(d.name)}`, imageUrl: d.products[0]?.imageUrl ?? null };
    });
  const dealsRule = settings.modules.deals;
  const dealsShown = dealsRule ? verdict('deals', dealsRule, 'Deals', catalog, sig, { now, signedIn: false, hasZip: false, nearServices: [], caps: settings.caps }).shown : false;
  return {
    bar: all.slice(0, settings.caps.navBarSize),
    all,
    communities: visibleCommunities(catalog, settings),
    services: { available: catalog.services.length > 0, providers: catalog.services.length, href: '/services' },
    deals: { available: dealsShown },
    brand: { available: catalog.products.some((p) => p.sellerKind === 'PLATFORM') },
    generatedAt: now.toISOString()
  };
}

/** GET /v1/site/departments: every catalog department with its eligible count; `includeEmpty` (the staff picker) adds the empty ones without a link. */
export function departmentsOf(catalog: Catalog, settings: HomeSettings, includeEmpty: boolean): SiteDepartment[] {
  const counted = new Map(signalsOf(catalog).departments.map((d) => [d.key, d]));
  const all: SiteDepartment[] = DEPARTMENTS.map(([key, name]) => {
    const u = counted.get(key);
    const { label, labels } = departmentLabel(key, name, settings);
    return { key, name, label, labels, labelKey: `dept.${key}`, count: u?.products.length ?? 0, sellers: u?.sellers ?? 0, ...(u ? { href: `/s?dept=${encodeURIComponent(name)}` } : {}), imageUrl: u?.products[0]?.imageUrl ?? null };
  });
  return all.filter((d) => includeEmpty || d.count > 0).sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));
}

/** `/communities/{slug}`: the canonical community, or null when it is hidden or unknown (a 404, never a zero-result page). */
export function resolveCommunity(catalog: Catalog, settings: HomeSettings, slugIn: string): { community: SiteCommunity; canonical: boolean } | null {
  const want = slugIn.trim().toLowerCase();
  const found = visibleCommunities(catalog, settings).find((c) => c.key === want || slug(c.label) === want);
  return found ? { community: found, canonical: slugIn === found.key } : null;
}

export function sitemapOf(catalog: Catalog, settings: HomeSettings, now: Date = new Date()): SiteSitemap {
  const nav = navigationOf(catalog, settings, now);
  const lastmod = now.toISOString();
  return { departments: nav.all.map((d) => ({ href: d.href ?? '/s', count: d.count, lastmod })), communities: nav.communities.map((c) => ({ href: c.href, count: c.count, lastmod })) };
}

/** Staff preview: the same composition for a chosen catalogue, ZIP, sign-in state, locale and date. */
export function previewHome(q: HomePreviewQuery, input: { catalog: Catalog; catalogName: string; settings: HomeSettings; loc: DeliveryLocation; now: Date }): HomePreview {
  const asOf = q.asOf ? new Date(/^\d{4}-\d{2}-\d{2}$/.test(q.asOf) ? `${q.asOf}T00:00:00Z` : q.asOf) : input.now;
  const seed = q.deterministic ? DEFAULT_SEED : q.seed?.trim() || `preview-${hash(`${asOf.toISOString().slice(0, 10)}|${q.zip ?? ''}|${input.catalogName}`).toString(36)}`;
  const out = composeDynamicHome({ catalog: input.catalog, settings: input.settings, seed, now: asOf, signedIn: q.signedIn ?? false, hasZip: !!q.zip?.trim(), loc: input.loc, variant: q.variant });
  const season = seasonAt(asOf);
  return {
    asOf: asOf.toISOString(),
    locale: q.locale ?? 'en',
    catalog: input.catalogName,
    seed,
    deliverTo: { zip: input.loc.zip, city: input.loc.city, state: input.loc.state },
    blocks: out.home.blocks,
    decisions: out.decisions,
    season: season ? { name: season.name, startsAt: season.startsAt, endsAt: season.endsAt, boosted: season.boosted.map((k) => MODULE_NAMES[k]) } : null
  };
}
