/* Mock delivery-runs pre-orders for the storefront (S273), ported from design/at-preorder.js (SEED, DEF, areaOn,
   resolve, openList). Dates are seeded relative to the day the mock state is created, keeping the design's shape on
   "today" (Oct 10): Lubbock #7 open, Amarillo #4 closed (delivering), El Paso #3 upcoming, Midland–Odessa #2 a draft,
   Greater Toronto #1 upcoming in CAD, Lubbock #6 delivered. Laredo is a coverage area that is switched off.
   The dev-only preview (session.preorderPreview) forces a place, a stage, the master switch or the fallback so
   API_MOCKS=1 shows every storefront state. */
import {
  type CartPreorder,
  type PreorderFallback,
  type PreorderFulfilment,
  type PreorderListItem,
  type PreorderOpenList,
  type PreorderPaymentMethod,
  type PreorderPlace,
  type PreorderPlacements,
  type PreorderResolution,
  type PreorderStage,
  type PreorderWindow
} from '../preorders';
import { type Cart, type CartPackage, type DeliveryOption } from '../schemas/cart';
import { type CheckoutPackage, type PickupPoint } from '../schemas/shopper';
import { getDb, type MockSession } from './db';
import { DEFAULT_ZIP, locationForZip } from './delivery';
import { type FixtureProduct } from './fixtures/catalog';

const DAY = 86_400_000;
const HOUR = 3_600_000;
export const PREORDER_SUPPORT_PHONE = '+1 (833) 555-0199';
const ALL_METHODS: PreorderPaymentMethod[] = ['CARD', 'WALLET', 'ACH'];

/** Every window as delivery-runs stores it (status is derived from the dates). */
export interface MockPreorderWindow {
  id: string;
  code: string;
  name: string;
  targets: string[];
  opensAt: number;
  closesAt: number;
  /** YYYY-MM-DD */
  deliversOn: string;
  timeZone: string;
  ribbon: string;
  headline: string;
  categories: string[];
  door: boolean;
  pickup: boolean;
  doorFeeMinor: number;
  pickupFeeMinor: number;
  currency: string;
  pickupWindow: string;
  allowedMethods: PreorderPaymentMethod[];
  showCount: boolean;
  active: boolean;
  draft: boolean;
  seedN: number;
}

export interface MockCoverageArea { key: string; on: boolean }

export interface PreorderMockState {
  windows: MockPreorderWindow[];
  settings: { enabled: boolean; fallback: PreorderFallback; placements: PreorderPlacements; areas: MockCoverageArea[] };
  /** Paid pre-orders placed through the mock checkout, per window. */
  placed: Map<string, number>;
  waitlist: { email: string; key: string }[];
}

/** Dev/mocks only: what the "Preview pre-order city" control overrides. */
export interface PreorderPreview {
  place?: PreorderPlace;
  stage?: PreorderStage;
  enabled?: boolean;
  fallback?: PreorderFallback;
}

type Status = 'draft' | 'paused' | 'upcoming' | 'open' | 'closed' | 'delivered';

const isoDate = (t: number) => new Date(t).toISOString().slice(0, 10);
/** Noon UTC of a calendar date (safe to format in any US/Canadian zone). */
const dateMs = (d: string) => new Date(`${d}T12:00:00Z`).getTime();

function seed(now: number): PreorderMockState {
  const today = Math.floor(now / DAY) * DAY;
  // 09:00 and 23:59 Central (UTC-5 in October) expressed in UTC.
  const opens = (d: number) => today + d * DAY + 14 * HOUR;
  const closes = (d: number) => today + (d + 1) * DAY + 4 * HOUR + 59 * 60_000;
  const on = (d: number) => isoDate(today + d * DAY);
  const usd = { doorFeeMinor: 799, pickupFeeMinor: 0, currency: 'USD', pickupWindow: '10am–2pm', allowedMethods: ALL_METHODS, timeZone: 'America/Chicago' };
  const W = (w: Omit<MockPreorderWindow, keyof typeof usd> & Partial<typeof usd>): MockPreorderWindow => ({ ...usd, ...w });
  const windows: MockPreorderWindow[] = [
    W({ id: 'lbk-7', code: 'LBK-7', name: 'Lubbock Pre-order #7', targets: ['US/TX/Lubbock'], opensAt: opens(-4), closesAt: closes(12), deliversOn: on(15), ribbon: 'Lubbock pre-order is open',
      headline: 'Fresh egusi, garri and palm oil, delivered to Lubbock on Sunday.', categories: ['Pantry', 'Spices', 'Snacks', 'Oils'], door: true, pickup: true, showCount: true, active: true, draft: false, seedN: 142 }),
    W({ id: 'ama-4', code: 'AMA-4', name: 'Amarillo Pre-order #4', targets: ['US/TX/Amarillo'], opensAt: opens(-14), closesAt: closes(-1), deliversOn: on(2), ribbon: 'Amarillo pre-order',
      headline: 'Amarillo deliveries go out Monday.', categories: ['Pantry', 'Spices'], door: true, pickup: false, showCount: false, active: true, draft: false, seedN: 96 }),
    W({ id: 'elp-3', code: 'ELP-3', name: 'El Paso Pre-order #3', targets: ['US/TX/El Paso'], opensAt: opens(5), closesAt: closes(20), deliversOn: on(24), ribbon: 'El Paso pre-order opens soon',
      headline: 'El Paso, your next pre-order opens Thursday.', categories: ['Pantry', 'Spices', 'Snacks'], door: true, pickup: true, showCount: true, active: true, draft: false, seedN: 0 }),
    W({ id: 'mid-2', code: 'MID-2', name: 'Midland–Odessa Pre-order #2', targets: ['US/TX/Midland', 'US/TX/Odessa'], opensAt: opens(17), closesAt: closes(28), deliversOn: on(32), ribbon: 'Midland–Odessa pre-order',
      headline: 'Midland and Odessa, one pre-order for both cities.', categories: ['Pantry'], door: true, pickup: false, showCount: true, active: true, draft: true, seedN: 0 }),
    W({ id: 'tor-1', code: 'TOR-1', name: 'Greater Toronto Pre-order #1', targets: ['CA/ON/Toronto', 'CA/ON/Mississauga', 'CA/ON/Brampton'], opensAt: opens(23) - 1 * HOUR, closesAt: closes(35) - 1 * HOUR, deliversOn: on(39),
      ribbon: 'Toronto pre-order', headline: 'First AfroTransact pre-order for the Greater Toronto Area.', categories: ['Pantry', 'Spices'], door: true, pickup: true, showCount: true, active: true, draft: false, seedN: 0,
      doorFeeMinor: 999, currency: 'CAD', timeZone: 'America/Toronto' }),
    W({ id: 'lbk-6', code: 'LBK-6', name: 'Lubbock Pre-order #6', targets: ['US/TX/Lubbock'], opensAt: opens(-32), closesAt: closes(-16), deliversOn: on(-13), ribbon: 'Lubbock pre-order', headline: 'Lubbock #6',
      categories: ['Pantry'], door: true, pickup: true, showCount: true, active: true, draft: false, seedN: 178 })
  ];
  const areas = ['US/TX/Lubbock', 'US/TX/Amarillo', 'US/TX/El Paso', 'US/TX/Midland', 'US/TX/Odessa', 'CA/ON/Toronto', 'CA/ON/Mississauga', 'CA/ON/Brampton'].map((key) => ({ key, on: true }));
  areas.push({ key: 'US/TX/Laredo', on: false });
  return {
    windows,
    settings: { enabled: true, fallback: 'SHIP', placements: { ribbon: true, hero: true, badge: true, pdp: true, cart: true, family: true }, areas },
    placed: new Map(),
    waitlist: []
  };
}

export function preorderState(now = Date.now()): PreorderMockState {
  const db = getDb();
  return (db.preorders ??= seed(now));
}

function status(w: MockPreorderWindow, now: number): Status {
  if (w.draft) return 'draft';
  if (!w.active) return 'paused';
  if (now < w.opensAt) return 'upcoming';
  if (now <= w.closesAt) return 'open';
  if (now < dateMs(w.deliversOn) + DAY / 2) return 'closed';
  return 'delivered';
}

const keyParts = (k: string) => {
  const [country = '', region = '', city] = k.split('/');
  return { country, region, city };
};

function covers(w: MockPreorderWindow, p: PreorderPlace): boolean {
  return w.targets.some((k) => {
    const t = keyParts(k);
    return t.country === p.country && t.region === p.region && (!t.city || t.city.toLowerCase() === p.city.toLowerCase());
  });
}

/** A city row overrides its region row; a region row covers every city in it (at-preorder.js#areaOn). */
export function areaOn(areas: MockCoverageArea[], p: PreorderPlace): boolean {
  const rows = areas.map((a) => ({ ...keyParts(a.key), on: a.on }));
  const city = rows.find((a) => a.country === p.country && a.region === p.region && a.city && a.city.toLowerCase() === p.city.toLowerCase());
  if (city) return city.on;
  const region = rows.find((a) => a.country === p.country && a.region === p.region && !a.city);
  return !!region?.on;
}

const COUNTRY_NAMES: Record<string, string> = { US: 'United States', CA: 'Canada' };
const areaLabel = (k: string) => {
  const { country, region, city } = keyParts(k);
  const suffix = country !== 'US' ? ` · ${COUNTRY_NAMES[country] ?? country}` : '';
  return city ? `${city}, ${region}${suffix}` : `All of ${region}${suffix}`;
};
export const locName = (targets: string[]) => {
  const names = targets.map(areaLabel);
  if (!names.length) return 'No area chosen';
  return names.length <= 3 ? names.join(' · ') : `${names.slice(0, 2).join(' · ')} + ${names.length - 2} more`;
};

/** The shopper's place: the preview override, else the deliver-to ZIP (US). */
export function placeOf(s: MockSession): PreorderPlace {
  if (s.preorderPreview?.place) return s.preorderPreview.place;
  const loc = locationForZip(s.zip) ?? locationForZip(DEFAULT_ZIP);
  return { country: 'US', region: loc?.state ?? 'TX', city: loc?.city ?? 'Houston' };
}

/** Moves a window's dates so it is in the forced stage (the prototype's "Stage" preview). */
function forceStage(w: MockPreorderWindow, stage: PreorderStage, now: number): MockPreorderWindow {
  if (stage === 'OPEN') return { ...w, opensAt: now - 4 * DAY, closesAt: now + 3.18 * DAY, deliversOn: isoDate(now + 6 * DAY) };
  if (stage === 'UPCOMING') return { ...w, opensAt: now + 4.3 * DAY, closesAt: now + 15 * DAY, deliversOn: isoDate(now + 18 * DAY) };
  return { ...w, opensAt: now - 14 * DAY, closesAt: now - 1 * DAY, deliversOn: isoDate(now + 2.4 * DAY) };
}

const STAGE: Record<string, PreorderStage> = { open: 'OPEN', upcoming: 'UPCOMING', closed: 'CLOSED' };

/** The window (if any) for a place and the derived stage, honouring the preview's forced stage. */
function windowFor(place: PreorderPlace, preview: PreorderPreview | undefined, now: number): { win: MockPreorderWindow | null; stage: PreorderStage | null; covered: boolean } {
  const st = preorderState(now);
  const cw = st.windows.filter((w) => !w.draft && covers(w, place));
  if (preview?.stage) {
    const base = cw.filter((w) => w.active && status(w, now) !== 'delivered').sort((a, b) => b.opensAt - a.opensAt)[0] ?? cw[0];
    return base ? { win: forceStage(base, preview.stage, now), stage: preview.stage, covered: true } : { win: null, stage: null, covered: false };
  }
  for (const want of ['open', 'upcoming', 'closed'] as const) {
    const f = cw.find((w) => status(w, now) === want);
    if (f) return { win: f, stage: STAGE[want] ?? null, covered: true };
  }
  return { win: null, stage: null, covered: cw.length > 0 };
}

function windowView(w: MockPreorderWindow, stage: PreorderStage, now: number): PreorderWindow {
  const st = preorderState(now);
  const count = w.showCount && stage !== 'UPCOMING' ? Math.round(w.seedN * 0.96) + (st.placed.get(w.id) ?? 0) : null;
  return {
    id: w.id, code: w.code, name: w.name, ribbon: w.ribbon, headline: w.headline, categories: w.categories, departments: ['Groceries'],
    opensAt: new Date(w.opensAt).toISOString(), closesAt: new Date(w.closesAt).toISOString(), deliversOn: w.deliversOn, timeZone: w.timeZone,
    door: w.door, pickup: w.pickup, doorFeeMinor: w.doorFeeMinor, pickupFeeMinor: w.pickupFeeMinor, currency: w.currency, pickupWindow: w.pickupWindow, allowedMethods: w.allowedMethods,
    showCount: w.showCount, count, creatives: { hero: null, pdp: null }
  };
}

/** GET /v1/preorders/resolve (delivery-runs PreorderResolver). */
export function resolvePreorder(s: MockSession, explicit: PreorderPlace | null, now = Date.now()): PreorderResolution {
  const st = preorderState(now);
  const preview = explicit ? { ...s.preorderPreview, place: explicit } : s.preorderPreview;
  const place = explicit ?? placeOf(s);
  const { win, stage, covered } = windowFor(place, preview, now);
  const enabled = preview?.enabled ?? st.settings.enabled;
  const on = !!win && enabled && win.active && areaOn(st.settings.areas, place);
  const target = !win || !stage ? null : stage === 'OPEN' ? win.closesAt : stage === 'UPCOMING' ? win.opensAt : dateMs(win.deliversOn) - 12 * HOUR + 9 * HOUR;
  return {
    place,
    metro: !covered,
    covered,
    on,
    stage: win ? stage : null,
    countdownTo: target === null ? null : new Date(target).toISOString(),
    window: win && stage ? windowView(win, stage, now) : null,
    fallback: preview?.fallback ?? st.settings.fallback,
    placements: st.settings.placements,
    supportPhone: PREORDER_SUPPORT_PHONE
  };
}

const ZIP_OF: Record<string, string> = { Lubbock: '79401', Amarillo: '79101', 'El Paso': '79901', Midland: '79701', Odessa: '79761', Laredo: '78040' };

/** GET /v1/preorders/open (at-preorder.js#openList). */
export function openPreorders(now = Date.now(), enabledOverride?: boolean): PreorderOpenList {
  const st = preorderState(now);
  const S = st.settings;
  if (!(enabledOverride ?? S.enabled)) return { windows: [], placements: S.placements };
  const windows: PreorderListItem[] = st.windows
    .filter((w) => !w.draft && w.active && ['open', 'upcoming'].includes(status(w, now)))
    .filter((w) => w.targets.some((k) => { const t = keyParts(k); return t.city ? areaOn(S.areas, { country: t.country, region: t.region, city: t.city }) : S.areas.some((a) => a.key === k && a.on); }))
    .map((w) => {
      const t = keyParts(w.targets[0] ?? '');
      const city = t.city ?? t.region;
      const open = status(w, now) === 'open';
      return { id: w.id, code: w.code, name: w.name, where: locName(w.targets), stage: open ? 'OPEN' : 'UPCOMING', opensAt: new Date(w.opensAt).toISOString(), closesAt: new Date(w.closesAt).toISOString(),
        deliversOn: w.deliversOn, timeZone: w.timeZone, place: { country: t.country, region: t.region, city }, zip: t.country === 'US' ? (ZIP_OF[city] ?? null) : null, heroUrl: null,
        doorFeeMinor: w.doorFeeMinor, currency: w.currency } satisfies PreorderListItem;
    })
    .sort((a, b) => (a.stage === 'OPEN' ? 0 : 1) - (b.stage === 'OPEN' ? 0 : 1) || a.name.localeCompare(b.name));
  return { windows, placements: S.placements };
}

/** Catalogue flag (mocks): groceries are pre-order eligible; nothing in the fixtures is flagged shipsNow. */
export const fixturePreorderEligible = (p: FixtureProduct | undefined): boolean => p?.cat === 'Groceries';

/** "Sun, Oct 25" for a calendar date. */
const dayLabel = (d: string) => new Date(dateMs(d)).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' });

/** Groups the cart's eligible lines into one pre-order package when the place has a live window (orders-service). */
export function withPreorderPackage(cart: Cart, session: MockSession, now: Date): Cart {
  const stored = getDb().sessions.get(session.sid) ?? session;
  const r = resolvePreorder(stored, null, now.getTime());
  if (!r.on || !r.window || !r.stage) return cart;
  const w = r.window;
  const db = getDb();
  const eligible = (atin: string) => fixturePreorderEligible(db.products.get(atin)) && w.departments.includes(db.products.get(atin)?.cat ?? '');
  const preLines = cart.packages.flatMap((p) => p.lines.filter((l) => eligible(l.atin)));
  if (!preLines.length) return cart;
  const others = cart.packages
    .map((p) => {
      const lines = p.lines.filter((l) => !eligible(l.atin));
      const selected = lines.filter((l) => l.selected);
      return { ...p, lines, itemCount: lines.reduce((a, l) => a + l.quantity, 0), subtotalMinor: selected.reduce((a, l) => a + l.lineTotalMinor, 0) };
    })
    .filter((p) => p.lines.length > 0);
  const options = [w.door && { fulfilment: 'DOOR' as const, feeMinor: w.doorFeeMinor }, w.pickup && { fulfilment: 'PICKUP' as const, feeMinor: w.pickupFeeMinor }].filter((o): o is { fulfilment: PreorderFulfilment; feeMinor: number } => !!o);
  const chosen: PreorderFulfilment = options.some((o) => o.fulfilment === stored.preorderFulfilment) ? (stored.preorderFulfilment as PreorderFulfilment) : (options[0]?.fulfilment ?? 'DOOR');
  const deliversT = dayLabel(w.deliversOn);
  const toOption = (o: { fulfilment: PreorderFulfilment; feeMinor: number }): DeliveryOption =>
    o.fulfilment === 'DOOR'
      ? { key: 'afrotransact_delivery', label: 'Pre-order door delivery', eta: deliversT, feeMinor: o.feeMinor, free: o.feeMinor === 0 }
      : { key: 'pickup', label: 'Pre-order pickup point', eta: `${deliversT}, ${w.pickupWindow}`, feeMinor: o.feeMinor, free: o.feeMinor === 0 };
  const ordered = [...options].sort((a, b) => (a.fulfilment === chosen ? -1 : b.fulfilment === chosen ? 1 : 0));
  const block: CartPreorder = {
    windowId: w.id, code: w.code, name: w.name, stage: r.stage, opensAt: w.opensAt, closesAt: w.closesAt, deliversOn: w.deliversOn, timeZone: w.timeZone, fulfilment: chosen, options,
    door: w.door, pickup: w.pickup, doorFeeMinor: w.doorFeeMinor, pickupFeeMinor: w.pickupFeeMinor, currency: w.currency, pickupWindow: w.pickupWindow, allowedMethods: w.allowedMethods
  };
  const fee = chosen === 'PICKUP' ? w.pickupFeeMinor : w.doorFeeMinor;
  const lines = preLines.map((l) => ({ ...l, preorderEligible: true }));
  const pkg: CartPackage = {
    packageId: `preorder-${w.id}`,
    seller: { id: 'afrotransact', name: 'AfroTransact', kind: 'PLATFORM', city: `${r.place.city}, ${r.place.region}` },
    fulfilment: 'AFROTRANSACT',
    heading: `${w.name} · delivered ${deliversT}`,
    shipsFrom: `${r.place.city}, ${r.place.region}`,
    delivery: { days: Math.max(0, Math.round((dateMs(w.deliversOn) - now.getTime()) / DAY)), label: deliversT, prefix: 'Pre-order · delivered', free: fee === 0, feeMinor: fee, expressLabel: null, cutoff: null },
    deliveryOptions: ordered.map(toOption),
    lines,
    itemCount: lines.reduce((a, l) => a + l.quantity, 0),
    subtotalMinor: lines.filter((l) => l.selected).reduce((a, l) => a + l.lineTotalMinor, 0),
    freeShippingNudge: null,
    preorder: block
  };
  return { ...cart, packages: [pkg, ...others], sellerCount: new Set([pkg, ...others].map((p) => p.seller.id)).size };
}

/** Checkout view of the pre-order package: promise dates on the delivery day and the window's pickup point. */
export function preorderCheckoutPackage(p: CartPackage): CheckoutPackage {
  const block = p.preorder;
  if (!block) return p;
  const day = block.deliversOn;
  const options = p.deliveryOptions.map((o) => ({ ...o, earliestDate: day, latestDate: day }));
  const point: PickupPoint = {
    id: `pu-pre-${block.windowId}`,
    name: `${p.shipsFrom.split(',')[0] ?? ''} pre-order pickup point`,
    address: 'Announced by email before delivery',
    hours: `${dayLabel(day)}, ${block.pickupWindow}`,
    ready: `Ready ${dayLabel(day)}, ${block.pickupWindow}`,
    setBy: 'platform'
  };
  return { ...p, deliveryOptions: options, pickupPoints: block.pickup ? [point] : [] };
}

/** Pre-order number for a placed order: LBK-2001, LBK-2002… */
export function nextPreorderNumber(code: string, windowId: string): string {
  const st = preorderState();
  const n = (st.placed.get(windowId) ?? 0) + 1;
  st.placed.set(windowId, n);
  return `${code.split('-')[0] ?? 'PRE'}-${2000 + n}`;
}
