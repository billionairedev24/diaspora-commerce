/* Mock of the staff Pre-orders routes (/v1/staff/preorders/**, mirroring delivery-runs' staff API, S274). Seeded from
   design/at-preorder.js (SEED windows, coverage areas, GEO list) and the Pre-Order v2 back office: orders are generated
   per window exactly like the design's gen() (same seeded RNG, so the same window always gets the same orders). Seed
   dates are shifted so that the design's "today" (Sat, Oct 10 2026) falls on the mock database's day, which keeps every
   status (open, scheduled, closed · delivering, delivered, draft, paused, area off) reachable whenever mocks run. */
import { http, HttpResponse } from 'msw';
import { z } from 'zod';

import { type AccessLevel } from '../../schemas/seller';
import {
  areaFromKey,
  type AreaLevel,
  areaOn,
  CommKindSchema,
  type CoverageArea,
  CREATIVE_SLOTS,
  type CreativeSlot,
  CreativeSlotSchema,
  currencyOfTargets,
  DEFAULT_PAYMENT,
  effectiveFees,
  keyWithin,
  LATE_ADD_REASON_LABEL,
  LateAddPaymentSchema,
  LateAddReasonSchema,
  type Place,
  type PlaceList,
  type PreorderAreaPage,
  type PreorderAreaRow,
  type PreorderBulkSwitch,
  type PreorderChannel,
  PreorderChannelSchema,
  preorderChecks,
  type PreorderCreative,
  type PreorderList,
  type PreorderListRow,
  type PreorderOrder,
  type PreorderOrderPage,
  PreorderOrderStatusSchema,
  type PreorderRegion,
  type PreorderSettings,
  PreorderSettingsSchema,
  preorderStatus,
  type PreorderSummary,
  type PreorderWindow,
  type PreorderWindowInput,
  PreorderWindowInputSchema,
  ratioOff,
  regionAreas,
  setAreasOn,
  strandedTargets,
  switchStateOf,
  tabOf,
  toCsvText,
  windowAreaOn
} from '../../staff-preorders';
import { parseSort, queryRows, type SortKey, type TableColumnLike } from '../table';
import { getStaffDb, type MockStaffDb } from './db';
import { allows, levelOf } from './model';
import { GEO } from './preorder-geo';

const DAY = 86_400_000;
const PAGE = 12;

/* ── Seed data (design/at-preorder.js + Pre-Order v2) ── */

interface CatalogItem { id: string; name: string; price: number; seller: string; now?: boolean }
const CATALOG: CatalogItem[] = [
  { id: 'garri', name: 'Ijebu Garri, 4 lb', price: 1299, seller: 'Mama Put Pantry' },
  { id: 'egusi', name: 'Ground Egusi, 2 lb', price: 1449, seller: 'Lagos Larder' },
  { id: 'palm', name: 'Red Palm Oil, 1 L', price: 999, seller: 'Accra Market Co.' },
  { id: 'jollof', name: 'Jollof Spice Kit', price: 849, seller: 'Mama Put Pantry' },
  { id: 'plantain', name: 'Plantain Chips, 6-pack', price: 1199, seller: 'Dakar Snacks' },
  { id: 'shea', name: 'Raw Shea Butter, 16 oz', price: 1599, seller: 'Tamale Naturals', now: true },
  { id: 'fabric', name: 'Ankara Print Fabric, 6 yd', price: 3400, seller: 'Kente & Co.', now: true }
];
const PRECAT = CATALOG.filter((p) => !p.now);
const atinOf = (id: string) => `ATN-PRE-${id.toUpperCase()}`;
const BY_ATIN = new Map(CATALOG.map((p) => [atinOf(p.id), p]));

const SOURCES: [PreorderChannel, number][] = [
  ['SITE_HERO', 18], ['SITE_RIBBON', 10], ['SITE_PDP', 12], ['APP', 14], ['INSTAGRAM', 12], ['FACEBOOK', 9], ['TIKTOK', 7], ['WHATSAPP', 8], ['EMAIL', 7], ['SMS', 3]
];
const TAG: Record<PreorderChannel, string> = {
  SITE_HERO: 'hero', SITE_RIBBON: 'ribbon', SITE_PDP: 'pdp', SITE_PAGE: 'page', SITE_DIRECT: 'direct', APP: 'app', INSTAGRAM: 'ig&cr=story', FACEBOOK: 'fb&cr=feed',
  TIKTOK: 'tt&cr=story', WHATSAPP: 'wa', EMAIL: 'email&cr=announce', SMS: 'sms', AGENT_LATE: 'agent'
};
const FIRST = ['Adaeze', 'Kwame', 'Fatou', 'Chidi', 'Amina', 'Tunde', 'Grace', 'Ibrahim', 'Ngozi', 'Yaw', 'Aisha', 'Emeka', 'Zainab', 'Kofi', 'Blessing', 'Samuel', 'Halima', 'Olu', 'Mariam', 'Jide'];
const LAST = ['Okafor', 'Mensah', 'Diallo', 'Eze', 'Bello', 'Adeyemi', 'Owusu', 'Sow', 'Nwosu', 'Boateng', 'Kamara', 'Obi', 'Musa', 'Asante', 'Tetteh', 'Yusuf', 'Bakare', 'Cissé'];

interface SeedWindow {
  id: string; targets: string[]; name: string; opens: string; closes: string; delivers: string; ribbon: string; headline: string; cats: string[];
  door: boolean; pickup: boolean; showCount: boolean; active: boolean; draft?: boolean; seedN: number;
}
/* design/at-preorder.js SEED, plus a paused window and a window whose only area is switched off (both spec states). */
const SEED: SeedWindow[] = [
  { id: 'lbk-7', targets: ['US/TX/Lubbock'], name: 'Lubbock Pre-order #7', opens: '2026-10-06T09:00', closes: '2026-10-22T23:59', delivers: '2026-10-25', ribbon: 'Lubbock pre-order is open', headline: 'Fresh egusi, garri and palm oil, delivered to Lubbock on Sunday, Oct 25.', cats: ['Pantry', 'Spices', 'Snacks', 'Oils'], door: true, pickup: true, showCount: true, active: true, seedN: 142 },
  { id: 'ama-4', targets: ['US/TX/Amarillo'], name: 'Amarillo Pre-order #4', opens: '2026-09-26T09:00', closes: '2026-10-09T23:59', delivers: '2026-10-12', ribbon: 'Amarillo pre-order', headline: 'Amarillo deliveries go out Monday, Oct 12.', cats: ['Pantry', 'Spices'], door: true, pickup: false, showCount: false, active: true, seedN: 96 },
  { id: 'elp-3', targets: ['US/TX/El Paso'], name: 'El Paso Pre-order #3', opens: '2026-10-15T09:00', closes: '2026-10-30T23:59', delivers: '2026-11-03', ribbon: 'El Paso pre-order opens Oct 15', headline: 'El Paso, your next pre-order opens Thursday, Oct 15.', cats: ['Pantry', 'Spices', 'Snacks'], door: true, pickup: true, showCount: true, active: true, seedN: 0 },
  { id: 'mid-2', targets: ['US/TX/Midland', 'US/TX/Odessa'], name: 'Midland–Odessa Pre-order #2', opens: '2026-10-27T09:00', closes: '2026-11-07T23:59', delivers: '2026-11-11', ribbon: 'Midland–Odessa pre-order', headline: 'Midland and Odessa, one pre-order for both cities.', cats: ['Pantry'], door: true, pickup: false, showCount: true, active: true, draft: true, seedN: 0 },
  { id: 'tor-1', targets: ['CA/ON/Toronto', 'CA/ON/Mississauga', 'CA/ON/Brampton'], name: 'Greater Toronto Pre-order #1', opens: '2026-11-02T09:00', closes: '2026-11-14T23:59', delivers: '2026-11-18', ribbon: 'Toronto pre-order', headline: 'First AfroTransact pre-order for the Greater Toronto Area.', cats: ['Pantry', 'Spices'], door: true, pickup: true, showCount: true, active: true, seedN: 0 },
  { id: 'lbk-6', targets: ['US/TX/Lubbock'], name: 'Lubbock Pre-order #6', opens: '2026-09-08T09:00', closes: '2026-09-24T23:59', delivers: '2026-09-27', ribbon: 'Lubbock pre-order', headline: 'Lubbock #6', cats: ['Pantry'], door: true, pickup: true, showCount: true, active: true, seedN: 178 },
  { id: 'ama-5', targets: ['US/TX/Amarillo'], name: 'Amarillo Pre-order #5', opens: '2026-10-13T09:00', closes: '2026-10-27T23:59', delivers: '2026-10-30', ribbon: 'Amarillo pre-order', headline: 'Amarillo, the next pre-order is on hold.', cats: ['Pantry', 'Spices'], door: true, pickup: false, showCount: true, active: false, seedN: 0 },
  { id: 'lrd-1', targets: ['US/TX/Laredo'], name: 'Laredo Pre-order #1', opens: '2026-10-20T09:00', closes: '2026-11-01T23:59', delivers: '2026-11-04', ribbon: 'Laredo pre-order', headline: 'Laredo, your first pre-order.', cats: ['Pantry'], door: true, pickup: false, showCount: true, active: true, seedN: 0 }
];
const DEF_AREAS = ['US/TX/Lubbock', 'US/TX/Amarillo', 'US/TX/El Paso', 'US/TX/Midland', 'US/TX/Odessa', 'CA/ON/Toronto', 'CA/ON/Mississauga', 'CA/ON/Brampton']
  .map((k) => areaFromKey(k))
  .concat([areaFromKey('US/TX/Laredo', false)]);
const ALL_COMMS = CommKindSchema.options;
const DESIGN_TODAY = Date.parse('2026-10-10T17:00:00Z');
/** Seed times are Central (UTC−5 in October), like the design's Texas windows. */
const at = (local: string, shift: number) => new Date(Date.parse(`${local}:00-05:00`) + shift).toISOString();
const dateShift = (d: string, shift: number) => new Date(Date.parse(`${d}T12:00:00Z`) + shift).toISOString().slice(0, 10);

/** Design RNG (FNV-1a seeded mulberry32), so generated orders match the prototype's. */
function rng(seed: string): () => number {
  let h = 2166136261;
  for (const c of seed) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
  return () => {
    h += 0x6d2b79f5;
    let t = h;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const pick = <T>(r: () => number, a: readonly T[]): T => a[Math.floor(r() * a.length)] as T;

/* ── State ── */


/** Late adds by Idempotency-Key, per mock state (replays answer the same order). */
const LATE_ADD_REPLAYS = new WeakMap<object, Map<string, PreorderOrder>>();
export interface MockPreorderWindow extends PreorderWindowInput {
  id: string;
  code: string;
  draft: boolean;
  active: boolean;
  version: number;
  creatives: PreorderCreative[];
}

export interface MockPreorderState {
  /** Real clock when seeded and the mock database's "now" then: the mock clock is base + elapsed real time. */
  seededAt: number;
  base: number;
  windows: MockPreorderWindow[];
  orders: PreorderOrder[];
  settings: PreorderSettings;
  areas: CoverageArea[];
  assets: Record<string, { url: string | null; width: number; height: number; contentType: string }>;
  seq: number;
}

const slugOf = (targets: string[]) => (areaFromKey(targets[0] ?? 'PRE').city ?? areaFromKey(targets[0] ?? 'PRE').region).replace(/[^A-Za-z]/g, '').slice(0, 3).toUpperCase() || 'PRE';
const countryName = (c: string) => GEO[c]?.name ?? c;
const regionName = (c: string, r: string) => GEO[c]?.regions[r]?.name ?? r;
const regionLabel = (c: string) => GEO[c]?.regionLabel ?? 'State / region';
export const areaLabel = (key: string): string => {
  const { country: c, region: r, city } = areaFromKey(key);
  const tail = c !== 'US' ? ` · ${countryName(c)}` : '';
  return city ? `${city}, ${r}${tail}` : `All of ${regionName(c, r)}${tail}`;
};
export const locName = (targets: string[]): string => {
  if (!targets.length) return 'No area chosen';
  const names = targets.map(areaLabel);
  return names.length <= 3 ? names.join(' · ') : `${names.slice(0, 2).join(' · ')} + ${names.length - 2} more`;
};

/** A placeholder creative (SVG data URL at the asset's size) for seeded slots. */
function placeholderCreative(slot: CreativeSlot, width: number, height: number, label: string): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><rect width="100%" height="100%" fill="#067457"/><circle cx="${width * 0.82}" cy="${height * 0.3}" r="${Math.min(width, height) * 0.35}" fill="#FFD400"/><text x="6%" y="55%" font-family="Inter, sans-serif" font-size="${Math.round(Math.min(width, height) / 7)}" font-weight="900" fill="#FFFFFF">${label}</text><text x="6%" y="72%" font-family="Inter, sans-serif" font-size="${Math.round(Math.min(width, height) / 16)}" fill="#FFF4BF">${slot} · ${width}×${height}</text></svg>`;
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

function fakeAddr(id: string, targets: string[]) {
  const { country: c, region: r, city: city0 } = areaFromKey(targets[0] ?? 'US/TX/Lubbock');
  const city = city0 ?? GEO[c]?.regions[r]?.cities[0] ?? r;
  const rr = rng(id);
  const ST = ['19th St', 'Indiana Ave', 'Slide Rd', 'Quaker Ave', 'University Ave', 'Main St', 'Elm St', 'Oak Dr', 'Maple Ave', 'Cedar Ln', 'Park Blvd', 'Lakeview Dr'];
  const AC: Record<string, string> = { Lubbock: '806', Amarillo: '806', 'El Paso': '915', Midland: '432', Odessa: '432', Toronto: '416', Mississauga: '905', Brampton: '905', Houston: '713', Dallas: '214', Atlanta: '404', Laredo: '956' };
  const ZP: Record<string, string> = { Lubbock: '794', Amarillo: '791', 'El Paso': '799', Midland: '797', Odessa: '797', Houston: '770', Dallas: '752', Atlanta: '303', Laredo: '780' };
  const n = 100 + Math.floor(rr() * 8900);
  const st = pick(rr, ST);
  const zip = c === 'CA' ? `L${Math.floor(rr() * 9)}T ${Math.floor(rr() * 9)}K${Math.floor(rr() * 9)}` : (ZP[city] ?? `7${Math.floor(rr() * 90 + 10)}`) + String(Math.floor(rr() * 90 + 10));
  const phone = `${c === 'US' || c === 'CA' ? '+1 ' : '+'}${AC[city] ?? '555'} 555 0${String(Math.floor(rr() * 900 + 100))}`;
  const notes = ['', '', '', 'Leave at side door', 'Call on arrival', 'Gate code 4512', 'Ring twice'];
  return { phone, line1: `${n} ${st}`, city, region: r, zip, note: pick(rr, notes) };
}

function genOrders(w: MockPreorderWindow, now: number, doorFee: number): PreorderOrder[] {
  const seedN = SEED_N[w.id] ?? 0;
  const r = rng(w.id);
  const out: PreorderOrder[] = [];
  const o = Date.parse(w.opensAt);
  const span = Math.max(0, Math.min(now, Date.parse(w.closesAt)) - o);
  const tot = SOURCES.reduce((a, b) => a + b[1], 0);
  const slug = slugOf(w.targets);
  const order = (number: string, customer: string, placed: number, channel: PreorderChannel, lines: { id: string; qty: number }[], door: boolean, status: PreorderOrder['status'], note: string): PreorderOrder => {
    const f = fakeAddr(number, w.targets);
    const sub = lines.reduce((a, l) => a + (CATALOG.find((p) => p.id === l.id)?.price ?? 0) * l.qty, 0);
    return {
      orderId: `ord-${w.id}-${number}`, number, windowId: w.id, customerName: customer, phone: f.phone, email: '', addressLine: f.line1, city: f.city, region: f.region, zip: f.zip,
      deliveryNote: f.note, placedAt: new Date(placed).toISOString(), channel, trackingTag: `src=${TAG[channel]}&po=${w.code}`,
      lines: lines.map((l) => { const p = CATALOG.find((x) => x.id === l.id); return { atin: atinOf(l.id), name: p?.name ?? l.id, sellerName: p?.seller ?? 'Marketplace seller', qty: l.qty }; }),
      units: lines.reduce((a, l) => a + l.qty, 0), fulfilment: door ? 'DOOR' : 'PICKUP', total: sub + (door ? doorFee : 0), status, note
    };
  };
  for (let i = 0; i < (span ? seedN : 0); i++) {
    let x = r() * tot;
    let src: PreorderChannel = SOURCES[0]?.[0] ?? 'SITE_HERO';
    for (const [k, v] of SOURCES) {
      if ((x -= v) < 0) {
        src = k;
        break;
      }
    }
    const lines: { id: string; qty: number }[] = [];
    const n = 1 + Math.floor(r() * 3);
    for (let j = 0; j < n; j++) lines.push({ id: pick(r, PRECAT).id, qty: 1 + Math.floor(r() * 2) });
    const door = w.pickup ? r() < 0.7 : true;
    const cancelled = r() < 0.04;
    const customer = `${pick(r, FIRST)} ${pick(r, LAST)}`;
    const placed = o + Math.pow(r(), 0.85) * span;
    out.push(order(`${slug}-${1001 + i}`, customer, placed, src, lines, door, cancelled ? 'CANCELLED' : 'PAID', ''));
  }
  const st = preorderStatus(w, now);
  if (st === 'CLOSED' || st === 'DELIVERED') {
    for (let i = 0; i < Math.round(seedN * 0.03); i++) {
      const p = CATALOG[Math.floor(r() * 5)] ?? PRECAT[0];
      const customer = `${FIRST[Math.floor(r() * 20)] ?? 'Ada'} ${LAST[Math.floor(r() * 18)] ?? 'Obi'}`;
      out.push(order(`${slug}-L${11 + i}`, customer, Date.parse(w.closesAt) + r() * DAY * 1.5, 'AGENT_LATE', [{ id: p?.id ?? 'garri', qty: 1 }], true, 'LATE_ADD', 'Customer called · by T. Ade (CS)'));
    }
  }
  return out;
}
const SEED_N: Record<string, number> = Object.fromEntries(SEED.map((s) => [s.id, s.seedN]));

export function seedPreorders(now: Date): MockPreorderState {
  const base = now.getTime();
  const shift = Math.round((base - DESIGN_TODAY) / DAY) * DAY;
  const windows: MockPreorderWindow[] = SEED.map((s) => ({
    id: s.id, code: s.id.toUpperCase(), name: s.name, targets: s.targets, opensAt: at(s.opens, shift), closesAt: at(s.closes, shift), deliversOn: dateShift(s.delivers, shift),
    ribbon: s.ribbon, headline: s.headline, smsCopy: `AfroTransact: ${s.name.replace(/ #\d+$/, '')} is open. {link} Reply STOP to opt out`, categories: s.cats,
    door: s.door, pickup: s.pickup, pickupPoint: s.pickup ? `${areaFromKey(s.targets[0] ?? '').city ?? ''} community hall, 10am–2pm` : null, doorFeeOverride: null, pickupFeeOverride: null,
    showCount: s.showCount, socialBudget: 20_000, comms: [...ALL_COMMS], draft: !!s.draft, active: s.active, version: 1, creatives: []
  }));
  const settings: PreorderSettings = { enabled: true, fallback: 'SHIP', placements: { ribbon: true, hero: true, badge: true, pdp: true, cart: true, family: true }, payment: structuredClone(DEFAULT_PAYMENT), version: 1 };
  const lbk = windows.find((w) => w.id === 'lbk-7');
  const lbk6 = windows.find((w) => w.id === 'lbk-6');
  const assets: MockPreorderState['assets'] = {};
  const seedCreative = (w: MockPreorderWindow | undefined, slot: CreativeSlot, width: number, height: number) => {
    if (!w) return;
    const assetId = `ast-seed-${w.id}-${slot.toLowerCase()}`;
    const url = placeholderCreative(slot, width, height, w.name.replace(/ Pre-order.*/, ''));
    assets[assetId] = { url, width, height, contentType: 'image/svg+xml' };
    w.creatives.push({ slot, assetId, url, width, height, ratioOff: ratioOff(slot, width, height) });
  };
  seedCreative(lbk, 'HERO', 1920, 640);
  seedCreative(lbk, 'STORY', 1080, 1920);
  seedCreative(lbk, 'FEED', 1080, 1350); // 4:5, flagged "Ratio off"
  seedCreative(lbk6, 'HERO', 1920, 640);
  windows.filter((w) => !w.draft && w.id !== 'lbk-7' && w.id !== 'lbk-6').forEach((w) => seedCreative(w, 'HERO', 1920, 640));
  const orders = windows.flatMap((w) => genOrders(w, base, effectiveFees(w, settings.payment, currencyOfTargets(w.targets)).door));
  return { seededAt: Date.now(), base, windows, orders, settings, areas: DEF_AREAS.map((a) => ({ ...a })), assets, seq: 0 };
}

/* ── Views ── */

const nowOf = (s: MockPreorderState) => s.base + (Date.now() - s.seededAt);
const statusOf = (s: MockPreorderState, w: MockPreorderWindow) => preorderStatus(w, nowOf(s));
const live = (o: PreorderOrder) => o.status !== 'CANCELLED';
export const ordersOf = (s: MockPreorderState, w: MockPreorderWindow) => s.orders.filter((o) => o.windowId === w.id).sort((a, b) => b.placedAt.localeCompare(a.placedAt));

function areaOffOf(s: MockPreorderState, w: MockPreorderWindow): boolean {
  const st = statusOf(s, w);
  if (st === 'DELIVERED' || st === 'DRAFT') return false;
  return !s.settings.enabled || !windowAreaOn(s.areas, w.targets);
}

export function windowView(s: MockPreorderState, w: MockPreorderWindow): PreorderWindow {
  const paid = ordersOf(s, w).filter(live);
  const currency = currencyOfTargets(w.targets);
  const fees = effectiveFees(w, s.settings.payment, currency);
  return {
    ...w,
    targets: w.targets.map((key) => ({ key, label: areaLabel(key) })),
    where: locName(w.targets),
    currency,
    effectiveDoorFee: fees.door,
    effectivePickupFee: fees.pickup,
    status: statusOf(s, w),
    areaOff: areaOffOf(s, w),
    switchState: switchStateOf({ draft: w.draft, active: w.active, status: statusOf(s, w) }, s.settings.enabled, windowAreaOn(s.areas, w.targets)),
    switchable: switchStateOf({ draft: w.draft, active: w.active, status: statusOf(s, w) }, true, true) !== 'NOT_LIVE',
    orderCount: paid.length,
    revenue: paid.reduce((a, o) => a + o.total, 0),
    creatives: CREATIVE_SLOTS.flatMap((sl) => w.creatives.filter((c) => c.slot === sl.slot))
  };
}

const rowOf = (s: MockPreorderState, w: MockPreorderWindow): PreorderListRow => {
  const v = windowView(s, w);
  return {
    id: v.id, code: v.code, name: v.name, where: v.where, opensAt: v.opensAt, closesAt: v.closesAt, deliversOn: v.deliversOn, status: v.status, areaOff: v.areaOff,
    orderCount: v.orderCount, revenue: v.revenue, currency: v.currency, active: v.active, switchable: v.switchable, switchState: v.switchState, version: v.version
  };
};

function summary(s: MockPreorderState, w: MockPreorderWindow): PreorderSummary {
  const all = ordersOf(s, w);
  const paid = all.filter(live);
  const now = nowOf(s);
  const revenue = paid.reduce((a, o) => a + o.total, 0);
  const units = new Map<string, number>();
  paid.forEach((o) => o.lines.forEach((l) => units.set(l.name, (units.get(l.name) ?? 0) + l.qty)));
  const byChannel = new Map<PreorderChannel, number>();
  paid.forEach((o) => byChannel.set(o.channel, (byChannel.get(o.channel) ?? 0) + 1));
  const byDay: PreorderSummary['byDay'] = [];
  if (all.length) {
    const d0 = new Date(w.opensAt);
    d0.setHours(0, 0, 0, 0);
    const last = Math.min(now, Date.parse(w.closesAt) + 2 * DAY);
    const days = Math.max(1, Math.ceil((last - d0.getTime()) / DAY));
    const arr: number[] = Array.from({ length: days }, () => 0);
    paid.forEach((o) => {
      const i = Math.floor((Date.parse(o.placedAt) - d0.getTime()) / DAY);
      if (i >= 0 && i < days) arr[i] = (arr[i] ?? 0) + 1;
    });
    arr.forEach((count, i) => {
      const t = d0.getTime() + i * DAY;
      const day = new Date(t);
      byDay.push({ date: `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`, count, afterClose: t > Date.parse(w.closesAt) });
    });
  }
  const r2 = rng(w.id);
  const has = (slot: CreativeSlot) => w.creatives.some((c) => c.slot === slot);
  const PL: [string, string, boolean, PreorderChannel[]][] = [
    ['HERO', 'Storefront hero', has('HERO'), ['SITE_HERO']],
    ['RIBBON', 'Header ribbon (text)', !!w.ribbon.trim(), ['SITE_RIBBON']],
    ['PDP', 'Product page banner', has('PDP'), ['SITE_PDP']],
    ['STORY', 'Social story', has('STORY'), ['INSTAGRAM', 'TIKTOK', 'WHATSAPP']],
    ['FEED', 'Social feed', has('FEED'), ['FACEBOOK']],
    ['EMAIL', 'Email header', has('EMAIL'), ['EMAIL']]
  ];
  return {
    kpis: {
      preorders: paid.length,
      cancelled: all.length - paid.length,
      customers: new Set(paid.map((o) => o.customerName)).size,
      units: paid.reduce((a, o) => a + o.units, 0),
      revenue,
      avgOrder: paid.length ? Math.round(revenue / paid.length) : null,
      lateAdds: paid.filter((o) => o.status === 'LATE_ADD').length
    },
    ordersTotal: all.length,
    byDay,
    byChannel: [...byChannel.entries()].sort((a, b) => b[1] - a[1]).map(([channel, count]) => ({ channel, count })),
    topProducts: [...units.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8).map(([name, n]) => ({ name, units: n })),
    fulfilmentSplit: { door: paid.filter((o) => o.fulfilment === 'DOOR').length, pickup: paid.filter((o) => o.fulfilment === 'PICKUP').length },
    placements: PL.map(([key, name, creative, srcs]) => {
      const orders = paid.filter((o) => srcs.includes(o.channel)).length;
      const impressions = Math.round(orders * (55 + r2() * 40) + 200 * r2());
      const clicks = Math.round(orders * (3 + r2() * 3) + 5 * r2());
      return { key, name, creative, impressions, clicks, ctr: impressions ? Math.round((clicks / impressions) * 1000) / 10 : null, orders };
    })
  };
}

interface OrdersFilter { status: string; channel: string; q: string }
/** What the orders table shows (and what its one search and sort see), as in the BFF (PreorderOrderColumns). */
export const PREORDER_ORDER_COLUMNS: TableColumnLike[] = [
  { key: 'number', kind: 'mono' }, { key: 'customerName', kind: 'strong' }, { key: 'phone', kind: 'text' }, { key: 'email', kind: 'text' },
  { key: 'addressLine', kind: 'text' }, { key: 'city', kind: 'text' }, { key: 'zip', kind: 'mono' }, { key: 'deliveryNote', kind: 'text' },
  { key: 'placedAt', kind: 'datetime' }, { key: 'channel', kind: 'status' }, { key: 'trackingTag', kind: 'mono' }, { key: 'units', kind: 'number' },
  { key: 'fulfilment', kind: 'status' }, { key: 'total', kind: 'money' }, { key: 'status', kind: 'status' }
];
export const preorderOrderRow = (o: PreorderOrder) => ({
  id: o.orderId,
  order: o,
  cells: Object.fromEntries(PREORDER_ORDER_COLUMNS.map((c) => [c.key, (o as unknown as Record<string, string | number | null>)[c.key] ?? null]))
});

const filterOrders = (list: PreorderOrder[], f: OrdersFilter, sort: SortKey[] = []) =>
  queryRows(
    list.filter((o) => (!f.status || o.status === f.status) && (!f.channel || o.channel === f.channel)).map(preorderOrderRow),
    PREORDER_ORDER_COLUMNS,
    f.q,
    sort
  ).map((r) => r.order);

const ORDER_CSV_HEADER = ['Order', 'Pre-order', 'Customer', 'Phone', 'Email', 'Street', 'City', 'Region', 'Postal code', 'Delivery note', 'Placed', 'Channel', 'Tracking tag', 'Items', 'Units', 'Fulfilment', 'Total', 'Status', 'Note'];
const CHANNEL_CSV: Record<PreorderChannel, string> = {
  SITE_HERO: 'Site · hero', SITE_RIBBON: 'Site · ribbon', SITE_PDP: 'Site · product page', SITE_PAGE: 'Site · pre-order page', SITE_DIRECT: 'Site · direct', APP: 'Mobile app',
  INSTAGRAM: 'Instagram', FACEBOOK: 'Facebook', TIKTOK: 'TikTok', WHATSAPP: 'WhatsApp', EMAIL: 'Email', SMS: 'SMS', AGENT_LATE: 'Agent (late)'
};
const STATUS_CSV = { PAID: 'Paid', LATE_ADD: 'Late add', CANCELLED: 'Cancelled' } as const;
const ordersCsv = (w: MockPreorderWindow, list: PreorderOrder[]) =>
  toCsvText([
    ORDER_CSV_HEADER,
    ...list.map((o) => [
      o.number, w.name, o.customerName, o.phone, o.email, o.addressLine, o.city, o.region, o.zip, o.deliveryNote, o.placedAt, CHANNEL_CSV[o.channel], o.trackingTag,
      o.lines.map((l) => `${l.name} x${l.qty}`).join('; '), o.units, o.fulfilment === 'DOOR' ? 'Door' : 'Pickup', (o.total / 100).toFixed(2), STATUS_CSV[o.status], o.note
    ])
  ]);

/* Coverage areas (drill-down country → region → area) */

/** FNV-1a of the region's rows: changes whenever a row is added, removed or switched. */
function regionVersion(rows: CoverageArea[]): number {
  let h = 2166136261;
  for (const ch of rows.map((a) => `${a.key.toLowerCase()}|${a.on}`).join(';')) h = Math.imul(h ^ ch.charCodeAt(0), 16777619);
  return h >>> 0;
}

function regionOf(s: MockPreorderState, country: string, region: string): PreorderRegion {
  const rows = regionAreas(s.areas, country, region);
  const live = s.windows.filter((w) => !w.draft && statusOf(s, w) !== 'DELIVERED');
  const sellers = (a: CoverageArea) => live.filter((w) => w.targets.some((t) => keyWithin(t, a.key) || keyWithin(a.key, t))).map((w) => w.name);
  const regionName = GEO[country]?.regions[region]?.name ?? region;
  const toRow = (a: CoverageArea) => ({ key: a.key, name: a.city ?? `Every city in ${regionName}`, on: a.on, version: 0, windows: sellers(a) });
  const whole = rows.find((a) => !a.city);
  return {
    country, region, countryName: countryName(country), regionName, regionLabel: GEO[country]?.regionLabel ?? 'State',
    wholeRegion: whole ? toRow(whole) : null, cities: rows.filter((a) => a.city).map(toRow), version: regionVersion(rows)
  };
}

function areaPage(s: MockPreorderState, country: string | null, region: string | null, q: string, page: number, size = PAGE): PreorderAreaPage {
  const per = Math.min(Math.max(1, size), 100);
  const A = s.areas;
  const winsIn = (k: string) => s.windows.filter((w) => !w.draft && w.targets.some((t) => keyWithin(k, t) || keyWithin(t, k))).length;
  const sumOn = (list: CoverageArea[]) => list.filter((a) => a.on).length;
  let level: AreaLevel = 'COUNTRY';
  let rows: PreorderAreaRow[];
  if (!country) {
    const by = new Map<string, CoverageArea[]>();
    A.forEach((a) => by.set(a.country, [...(by.get(a.country) ?? []), a]));
    rows = [...by.entries()].map(([c, list]) => {
      const regs = new Set(list.map((a) => a.region)).size;
      const label = regionLabel(c).toLowerCase();
      return { key: c, name: countryName(c), coverage: `${regs} ${label}${regs > 1 ? 's' : ''} · ${list.filter((a) => a.city).length} cities`, windows: winsIn(c), on: sumOn(list), total: list.length, leaf: false };
    });
  } else if (!region) {
    level = 'REGION';
    const by = new Map<string, CoverageArea[]>();
    A.filter((a) => a.country === country).forEach((a) => by.set(a.region, [...(by.get(a.region) ?? []), a]));
    rows = [...by.entries()].map(([r, list]) => {
      const whole = list.some((a) => !a.city);
      const cities = list.filter((a) => a.city).length;
      const coverage = (whole ? `Every city${cities ? ' + ' : ''}` : '') + (cities ? `${cities} cit${cities > 1 ? 'ies' : 'y'}` : '');
      return { key: `${country}/${r}`, name: regionName(country, r), coverage, windows: winsIn(`${country}/${r}`), on: sumOn(list), total: list.length, leaf: false };
    });
  } else {
    level = 'AREA';
    rows = A.filter((a) => a.country === country && a.region === region)
      .sort((x, y) => (x.city ? 1 : 0) - (y.city ? 1 : 0) || String(x.city).localeCompare(String(y.city)))
      .map((a) => ({
        key: a.key,
        name: a.city ?? `Every city in ${regionName(country, region)}`,
        coverage: a.city ? 'City' : `Whole ${regionLabel(country).toLowerCase()}`,
        windows: winsIn(a.key),
        on: a.on ? 1 : 0,
        total: 1,
        leaf: true
      }));
  }
  const ql = q.trim().toLowerCase();
  rows = rows.filter((r) => !ql || r.name.toLowerCase().includes(ql));
  if (level !== 'AREA') rows.sort((a, b) => a.name.localeCompare(b.name));
  const pages = Math.max(1, Math.ceil(rows.length / per));
  const p = Math.min(Math.max(0, page), pages - 1);
  return {
    level, country, region, countryName: country ? countryName(country) : null, regionName: country && region ? regionName(country, region) : null,
    regionLabel: country ? regionLabel(country) : 'State', rows: rows.slice(p * per, p * per + per), total: rows.length, page: p, size: per,
    onCount: A.filter((a) => a.on).length, offCount: A.filter((a) => !a.on).length,
    saved: A.filter((a) => a.on).map((a) => ({ key: a.key, label: areaLabel(a.key) })).sort((a, b) => a.label.localeCompare(b.label))
  };
}

function places(country: string, region: string, q: string): PlaceList {
  const t = q.trim().toLowerCase();
  if (t.length >= 2) {
    const out: (Place & { rank: number })[] = [];
    Object.entries(GEO).forEach(([c, C]) => {
      if (C.name.toLowerCase().includes(t)) out.push({ key: c, code: c, name: C.name, label: C.name, sub: 'Country', type: 'COUNTRY', rank: C.name.toLowerCase().startsWith(t) ? 0 : 2 });
      Object.entries(C.regions).forEach(([r, R0]) => {
        if (R0.name.toLowerCase().includes(t) || r.toLowerCase() === t) out.push({ key: `${c}/${r}`, code: r, name: R0.name, label: `All of ${R0.name}`, sub: `${C.regionLabel} · ${C.name}`, type: 'REGION', rank: R0.name.toLowerCase().startsWith(t) || r.toLowerCase() === t ? 0 : 2 });
        R0.cities.forEach((x) => {
          if (x.toLowerCase().includes(t)) out.push({ key: `${c}/${r}/${x}`, code: x, name: x, label: `${x}, ${r}`, sub: `${R0.name} · ${C.name}`, type: 'CITY', rank: x.toLowerCase().startsWith(t) ? 1 : 3 });
        });
      });
    });
    return { items: out.sort((a, b) => a.rank - b.rank || a.label.localeCompare(b.label)).slice(0, 8).map(({ rank: _rank, ...p }) => p), regionLabel: regionLabel(country || 'US') };
  }
  const C = country ? GEO[country] : undefined;
  if (country && region) {
    const R0 = C?.regions[region];
    return { items: [...(R0?.cities ?? [])].sort().map((x) => ({ key: `${country}/${region}/${x}`, code: x, name: x, label: `${x}, ${region}`, sub: R0?.name ?? region, type: 'CITY' })), regionLabel: regionLabel(country) };
  }
  if (country) {
    return {
      items: Object.entries(C?.regions ?? {}).map(([r, R0]) => ({ key: `${country}/${r}`, code: r, name: R0.name, label: R0.name, sub: C?.name ?? country, type: 'REGION' as const })).sort((a, b) => a.name.localeCompare(b.name)),
      regionLabel: regionLabel(country)
    };
  }
  return { items: Object.entries(GEO).map(([c, G]) => ({ key: c, code: c, name: G.name, label: G.name, sub: G.regionLabel, type: 'COUNTRY' as const })).sort((a, b) => a.name.localeCompare(b.name)), regionLabel: 'State' };
}

function directory(w: MockPreorderWindow) {
  const P0 = [['Adaeze Nwosu', 'adaeze.n@gmail.com'], ['Kwame Mensah', 'kwame.mensah@outlook.com'], ['Fatou Diallo', 'fatou.d@yahoo.com'], ['Chidi Eze', 'chidi.eze@gmail.com'], ['Grace Owusu', 'grace.owusu@icloud.com'], ['Ibrahim Sow', 'isow@gmail.com']] as const;
  const { country } = areaFromKey(w.targets[0] ?? 'US/TX/Lubbock');
  return P0.map(([name, email], i) => {
    const f = fakeAddr(name, w.targets);
    const f2 = fakeAddr(`${name}#2`, w.targets);
    const addr = (id: string, label: string, x: typeof f) => ({ id, label, fullName: name, phone: x.phone, line1: x.line1, city: x.city, region: x.region, zip: x.zip, country, ...(x.note ? { instructions: x.note } : {}) });
    return { id: `c${i}`, name, email, phone: f.phone, city: f.city, region: f.region, country, addresses: [addr('a1', 'Home', f), ...(i % 2 ? [addr('a2', 'Work', f2)] : [])] };
  });
}

/* ── Handlers ── */

/** What the shared staff handler plumbing hands this module (structurally a subset of handlers.ts' context). */
export interface PreorderCtx {
  db: MockStaffDb;
  roles: string[];
  permissions: string[];
  request: Request;
  now: Date;
  action: { actor: string; actorId: string };
}
type Resolver = (ctx: PreorderCtx, params: Record<string, string>) => Promise<unknown> | unknown;
type MswResolver = Parameters<typeof http.get>[1];

export interface PreorderHandlerDeps {
  route: (fn: Resolver, status?: number) => MswResolver;
  json: <S extends z.ZodType>(request: Request, schema: S) => Promise<z.infer<S>>;
  problem: (status: number, code: string, title: string, detail: string, errors?: Record<string, string>) => Error;
}

const RANK: Record<AccessLevel, number> = { VIEW: 1, EDIT: 2, APPROVE: 3, FULL: 4 };

export function preorderHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const forbidden = (detail: string) => problem(403, 'FORBIDDEN', 'Forbidden', detail);
  const notFound = (detail: string) => problem(404, 'NOT_FOUND', 'Not found', detail);
  const st = (ctx: PreorderCtx) => ctx.db.preorders;
  const view = (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'preorders')) throw forbidden('Your roles can’t open pre-orders.');
  };
  const manage = (ctx: PreorderCtx) => {
    view(ctx);
    const lv = levelOf(ctx.roles, 'preorders');
    if (!ctx.permissions.includes('preorders:manage') || !lv || RANK[lv] < RANK.EDIT) throw forbidden('Only pre-order managers can open, edit, pause or publish pre-orders and upload creatives.');
  };
  const full = (ctx: PreorderCtx) => {
    view(ctx);
    if (levelOf(ctx.roles, 'preorders') !== 'FULL') throw forbidden('Only full Pre-orders access can change the platform switch, areas, placements and payment settings.');
  };
  const windowOf = (ctx: PreorderCtx, id: string | undefined) => {
    const w = st(ctx).windows.find((x) => x.id === id);
    if (!w) throw notFound('No such pre-order.');
    return w;
  };
  const audit = (ctx: PreorderCtx, action: string, entityId: string, summaryText: string) =>
    ctx.db.audit.unshift({ at: ctx.now.toISOString(), actor: ctx.action.actor, action, summary: summaryText, entityId });
  const ifMatch = (ctx: PreorderCtx, version: number) => {
    const raw = ctx.request.headers.get('if-match');
    if (!raw) throw problem(428, 'PRECONDITION_REQUIRED', 'If-Match required', 'Send the version you edited as If-Match.');
    if (raw.replace(/^W\//, '').replace(/"/g, '') !== String(version)) {
      throw problem(412, 'PRECONDITION_FAILED', 'Changed by someone else', 'Someone saved this in the meantime. Reload to see their changes, then edit again.');
    }
  };
  const withEtag = (body: { version: number }) => HttpResponse.json(body as never, { headers: { ETag: `"${body.version}"` } });
  const validateInput = (b: PreorderWindowInput) => {
    const errors: Record<string, string> = {};
    if (Number.isNaN(Date.parse(b.opensAt))) errors.opensAt = 'Enter an opening date and time';
    if (Number.isNaN(Date.parse(b.closesAt))) errors.closesAt = 'Enter a closing date and time';
    if (!/^\d{4}-\d{2}-\d{2}$/.test(b.deliversOn)) errors.deliversOn = 'Enter a delivery date';
    b.targets.forEach((k) => {
      const a = areaFromKey(k);
      if (!a.country || !a.region) errors.targets = `“${k}” is not a country/region[/city] key`;
    });
    if (b.pickup && b.pickupPoint !== null && b.pickupPoint.length > 160) errors.pickupPoint = 'Keep the pickup point under 160 characters';
    if (Object.keys(errors).length) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
  };
  /** Targets of a saved window become pre-order areas (switched on) when they are not yet. */
  const rememberAreas = (ctx: PreorderCtx, targets: string[]) => {
    targets.forEach((k) => {
      if (!st(ctx).areas.some((a) => a.key === k)) st(ctx).areas.push(areaFromKey(k));
    });
  };
  /** Sets one window's own switch (never the master switch): switched, unchanged, or skipped (draft / delivered). */
  const switchOne = (ctx: PreorderCtx, w: MockPreorderWindow, active: boolean): 'switched' | 'unchanged' | 'skipped' => {
    const st0 = statusOf(st(ctx), w);
    if (w.draft || st0 === 'DELIVERED') return 'skipped';
    if (w.active === active) return 'unchanged';
    w.active = active;
    w.version += 1;
    audit(ctx, active ? 'preorder.window.resumed' : 'preorder.window.paused', w.id, `Switched ${active ? 'on' : 'off'} ${w.name}`);
    return 'switched';
  };
  const ordersQuery = (ctx: PreorderCtx): OrdersFilter & { page: number; size: number } => {
    const qs = new URL(ctx.request.url).searchParams;
    const status = qs.get('status') ?? '';
    const channel = qs.get('channel') ?? '';
    if (status && !PreorderOrderStatusSchema.safeParse(status).success) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Unknown status.', { status: 'Unknown status' });
    if (channel && !PreorderChannelSchema.safeParse(channel).success) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Unknown channel.', { channel: 'Unknown channel' });
    return { status, channel, q: qs.get('q') ?? '', page: Math.max(0, Number(qs.get('page') ?? 0) || 0), size: Math.min(100, Math.max(1, Number(qs.get('size') ?? PAGE) || PAGE)) };
  };
  const csv = (fileName: string, text: string) =>
    new HttpResponse(text, { headers: { 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': `attachment; filename="${fileName}"` } });

  return [
    http.get(
      `${W}/preorders`,
      route((ctx): PreorderList => {
        view(ctx);
        const s = st(ctx);
        const qs = new URL(ctx.request.url).searchParams;
        const tab = qs.get('status') ?? 'all';
        const q = (qs.get('q') ?? '').trim().toLowerCase();
        const page = Math.max(0, Number(qs.get('page') ?? 0) || 0);
        const rows = s.windows.map((w) => rowOf(s, w));
        const counts = { all: rows.length, open: 0, upcoming: 0, closed: 0, draft: 0 };
        rows.forEach((r) => {
          const t = tabOf(r.status);
          if (t && t !== 'all') counts[t] += 1;
        });
        const items = rows.filter((r) => (tab === 'all' || tabOf(r.status) === tab) && (!q || `${r.name} ${r.where} ${r.code}`.toLowerCase().includes(q)));
        const size = 50;
        return { items: items.slice(page * size, page * size + size), counts, total: items.length, page, size, enabled: s.settings.enabled };
      })
    ),
    http.post(
      `${W}/preorders`,
      route(async (ctx): Promise<PreorderWindow> => {
        const b = await json(ctx.request, PreorderWindowInputSchema);
        manage(ctx);
        validateInput(b);
        const s = st(ctx);
        s.seq += 1;
        const slug = slugOf(b.targets);
        const n = s.windows.filter((w) => w.code.startsWith(`${slug}-`)).length + 1;
        let code = `${slug}-${n}`;
        while (s.windows.some((w) => w.code === code)) code = `${code}X`;
        const w: MockPreorderWindow = { ...b, id: `pre-${Date.now().toString(36)}${s.seq}`, code, draft: true, active: true, version: 1, creatives: [] };
        s.windows.unshift(w);
        rememberAreas(ctx, b.targets);
        audit(ctx, 'preorder.window.created', w.id, `Opened draft ${w.name}`);
        return windowView(s, w);
      }, 201)
    ),
    http.get(
      `${W}/preorders/settings`,
      route((ctx) => {
        view(ctx);
        return withEtag(st(ctx).settings);
      })
    ),
    http.put(
      `${W}/preorders/settings`,
      route(async (ctx) => {
        const b = await json(ctx.request, PreorderSettingsSchema.omit({ version: true }));
        full(ctx);
        const s = st(ctx);
        ifMatch(ctx, s.settings.version);
        const fees = [...Object.entries(b.payment.doorFee), ...Object.entries(b.payment.pickupFee)];
        if (fees.some(([c]) => !/^[A-Z]{3}$/.test(c))) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Fees are keyed by ISO 4217 currency.', { 'payment.doorFee': 'Use a 3-letter currency code' });
        if (!b.payment.allowedMethods.length) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Allow at least one payment method.', { 'payment.allowedMethods': 'Allow at least one payment method' });
        if (!b.payment.lateAdd.savedCard && !b.payment.lateAdd.paymentLink) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Late adds need a way to pay.', { 'payment.lateAdd': 'Keep saved card or payment link on' });
        if (!b.payment.missedDelivery.offerRefund && !b.payment.missedDelivery.offerNewDate) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Shoppers need a choice when a delivery is missed.', { 'payment.missedDelivery': 'Offer a refund or a new date' });
        const before = s.settings;
        s.settings = { ...b, version: before.version + 1 };
        audit(ctx, 'preorder.settings.changed', 'preorder-settings', before.enabled !== b.enabled ? `Pre-order switched ${b.enabled ? 'on' : 'off'} for all cities` : 'Updated pre-order settings');
        return withEtag(s.settings);
      })
    ),
    http.get(
      `${W}/preorders/areas`,
      route((ctx): PreorderAreaPage => {
        view(ctx);
        const qs = new URL(ctx.request.url).searchParams;
        return areaPage(st(ctx), qs.get('country') || null, qs.get('region') || null, qs.get('q') ?? '', Number(qs.get('page') ?? 0) || 0, Number(qs.get('size') ?? PAGE) || PAGE);
      })
    ),
    http.post(
      `${W}/preorders/areas`,
      route(async (ctx): Promise<PreorderAreaPage> => {
        const b = await json(ctx.request, z.object({ key: z.string().min(3).max(120) }));
        manage(ctx);
        const a = areaFromKey(b.key.trim());
        if (!/^[A-Z]{2}$/.test(a.country) || !a.region || b.key.split('/').length > 3) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Use country/region or country/region/city.', { key: 'Use country/region or country/region/city' });
        const s = st(ctx);
        if (s.areas.some((x) => x.key.toLowerCase() === a.key.toLowerCase())) throw problem(409, 'CONFLICT', 'Already an area', `${areaLabel(a.key)} is already a pre-order area.`);
        s.areas.push(a);
        audit(ctx, 'preorder.area.added', a.key, `${areaLabel(a.key)} added to pre-order areas`);
        return areaPage(s, a.country, a.region, '', 0);
      }, 201)
    ),
    http.patch(
      `${W}/preorders/areas`,
      route(async (ctx): Promise<PreorderAreaPage> => {
        const b = await json(ctx.request, z.object({ keys: z.array(z.string()).min(1).max(500), on: z.boolean() }));
        full(ctx);
        const s = st(ctx);
        s.areas = setAreasOn(s.areas, b.keys, b.on);
        audit(ctx, 'preorder.area.switched', b.keys.join(','), `Switched ${b.on ? 'on' : 'off'}: ${b.keys.map((k) => (k.includes('/') ? areaLabel(k) : countryName(k))).join(', ')}`);
        const first = areaFromKey(b.keys[0] ?? '');
        return areaPage(s, first.country || null, b.keys[0]?.includes('/') ? first.region : null, '', 0);
      })
    ),
    http.delete(
      `${W}/preorders/areas/:key`,
      route((ctx, p): PreorderAreaPage => {
        full(ctx);
        const s = st(ctx);
        const key = decodeURIComponent(p.key ?? '');
        if (!s.areas.some((a) => a.key === key)) throw notFound('No such pre-order area.');
        s.areas = s.areas.filter((a) => a.key !== key);
        audit(ctx, 'preorder.area.removed', key, `${areaLabel(key)} removed from pre-order areas`);
        const a = areaFromKey(key);
        return areaPage(s, a.country, a.region, '', 0);
      })
    ),
    http.get(
      `${W}/preorders/areas/regions/:country/:region`,
      route((ctx, p) => {
        view(ctx);
        return withEtag(regionOf(st(ctx), String(p.country ?? '').toUpperCase(), String(p.region ?? '').toUpperCase()));
      })
    ),
    http.put(
      `${W}/preorders/areas/regions/:country/:region`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ everyCity: z.boolean().nullable().default(null), cities: z.array(z.object({ city: z.string().trim().min(1).max(80).regex(/^[^/]+$/), on: z.boolean() })).max(2000) }));
        manage(ctx);
        const s = st(ctx);
        const c = String(p.country ?? '').toUpperCase();
        const r = String(p.region ?? '').toUpperCase();
        ifMatch(ctx, regionOf(s, c, r).version);
        const want: CoverageArea[] = [];
        if (b.everyCity !== null) want.push(areaFromKey(`${c}/${r}`, b.everyCity));
        const seen = new Map<string, CoverageArea>();
        b.cities.forEach((x) => seen.set(x.city.toLowerCase(), areaFromKey(`${c}/${r}/${x.city}`, x.on)));
        want.push(...seen.values());
        const after = s.areas.filter((a) => !(a.country === c && a.region === r)).concat(want);
        const live = s.windows.filter((w) => !w.draft && statusOf(s, w) !== 'DELIVERED');
        const stranded = strandedTargets(s.areas, after, live.flatMap((w) => w.targets));
        if (stranded.length) {
          const names = live.filter((w) => w.targets.some((t) => stranded.includes(t))).map((w) => w.name);
          throw problem(409, 'delivery-runs.area-in-use', 'Area in use', `${stranded.map(areaLabel).join(', ')} can’t be removed while ${names.join(', ')} ${names.length > 1 ? 'sell' : 'sells'} there. Switch it off instead, or change those pre-orders first.`);
        }
        s.areas = after;
        audit(ctx, 'preorder.areas.changed', `${c}/${r}`, `Pre-order coverage of ${c}/${r} edited`);
        return withEtag(regionOf(s, c, r));
      })
    ),
    http.patch(
      `${W}/preorders`,
      route(async (ctx): Promise<PreorderBulkSwitch> => {
        const b = await json(ctx.request, z.object({ ids: z.array(z.string()).min(1).max(100), active: z.boolean() }));
        manage(ctx);
        const s = st(ctx);
        const items = b.ids.map((id) => {
          const w = s.windows.find((x) => x.id === id);
          if (!w) return { id, outcome: 'SKIPPED' as const, window: null, code: 'delivery-runs.preorder-not-found', reason: 'Pre-order not found' };
          const sw = switchOne(ctx, w, b.active);
          if (sw === 'skipped') return { id, outcome: 'SKIPPED' as const, window: rowOf(s, w), code: 'delivery-runs.invalid-transition', reason: w.draft ? 'A draft has no switch; publish it first' : 'A delivered pre-order cannot be switched' };
          return { id, outcome: sw === 'switched' ? ('SWITCHED' as const) : ('UNCHANGED' as const), window: rowOf(s, w), code: null, reason: null };
        });
        const n = (o: string) => items.filter((i) => i.outcome === o).length;
        return { items, switched: n('SWITCHED'), unchanged: n('UNCHANGED'), skipped: n('SKIPPED') };
      })
    ),
    http.patch(
      `${W}/preorders/:id`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, z.object({ active: z.boolean() }));
        manage(ctx);
        const w = windowOf(ctx, p.id);
        if (ctx.request.headers.get('if-match')) ifMatch(ctx, w.version);
        if (switchOne(ctx, w, b.active) === 'skipped') throw problem(409, 'delivery-runs.invalid-transition', 'Invalid transition', w.draft ? 'A draft has no switch; publish it first.' : 'A delivered pre-order cannot be switched.');
        return withEtag(windowView(st(ctx), w));
      })
    ),
    http.get(
      `${W}/preorders/places/search`,
      route((ctx): PlaceList => {
        view(ctx);
        const qs = new URL(ctx.request.url).searchParams;
        return places(qs.get('country') ?? '', qs.get('region') ?? '', qs.get('q') ?? '');
      })
    ),
    http.post(
      `${W}/preorders/uploads`,
      route(async (ctx) => {
        const b = await json(ctx.request, z.object({ fileName: z.string().max(200), contentType: z.string(), size: z.number().int().min(1), width: z.number().int().min(0), height: z.number().int().min(0) }));
        manage(ctx);
        if (!/^image\/(jpeg|png|webp|gif|svg\+xml)$/.test(b.contentType)) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Upload a JPEG, PNG, WebP, GIF or SVG image.', { contentType: 'Upload an image' });
        if (b.size > 8 * 1024 * 1024) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Images can be up to 8 MB.', { size: 'Images can be up to 8 MB' });
        const s = st(ctx);
        s.seq += 1;
        const assetId = `ast-${Date.now().toString(36)}${s.seq}`;
        s.assets[assetId] = { url: null, width: b.width, height: b.height, contentType: b.contentType };
        return { assetId, upload: { method: 'PUT', url: `${new URL(ctx.request.url).origin}/api/bff/mock-preorder-uploads/${assetId}`, headers: { 'Content-Type': b.contentType } } };
      }, 201)
    ),
    http.put('*/mock-preorder-uploads/:id', async ({ request, params }) => {
      const s = getStaffDb().preorders;
      const id = typeof params.id === 'string' ? params.id : '';
      const asset = s.assets[id];
      if (!asset) return new HttpResponse(null, { status: 404 });
      const bytes = new Uint8Array(await request.arrayBuffer());
      let bin = '';
      for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
      asset.url = `data:${asset.contentType};base64,${btoa(bin)}`;
      return new HttpResponse(null, { status: 200 });
    }),
    http.get(
      `${W}/preorders/:id`,
      route((ctx, p) => {
        view(ctx);
        return withEtag(windowView(st(ctx), windowOf(ctx, p.id)));
      })
    ),
    http.put(
      `${W}/preorders/:id`,
      route(async (ctx, p) => {
        const b = await json(ctx.request, PreorderWindowInputSchema);
        manage(ctx);
        const w = windowOf(ctx, p.id);
        ifMatch(ctx, w.version);
        validateInput(b);
        Object.assign(w, b, { version: w.version + 1 });
        rememberAreas(ctx, b.targets);
        audit(ctx, 'preorder.window.updated', w.id, `Edited ${w.name}`);
        return withEtag(windowView(st(ctx), w));
      })
    ),
    http.post(
      `${W}/preorders/:id/publish`,
      route((ctx, p): PreorderWindow => {
        manage(ctx);
        const w = windowOf(ctx, p.id);
        const failed = preorderChecks(w, w.creatives.some((c) => c.slot === 'HERO')).filter((c) => !c.ok);
        if (failed.length) {
          throw problem(422, 'PREORDER_CHECKS_FAILED', `${failed.length} check${failed.length > 1 ? 's' : ''} to fix`, 'Fix the failed checks before publishing.', Object.fromEntries(failed.map((c) => [c.key, c.label])));
        }
        w.draft = false;
        w.active = true;
        w.version += 1;
        audit(ctx, 'preorder.window.published', w.id, `Published ${w.name}`);
        return windowView(st(ctx), w);
      })
    ),
    http.post(
      `${W}/preorders/:id/pause`,
      route((ctx, p): PreorderWindow => {
        manage(ctx);
        const w = windowOf(ctx, p.id);
        if (w.draft) throw problem(409, 'CONFLICT', 'Conflict', 'A draft is not live; there is nothing to pause.');
        w.active = false;
        w.version += 1;
        audit(ctx, 'preorder.window.paused', w.id, `Paused ${w.name}`);
        return windowView(st(ctx), w);
      })
    ),
    http.post(
      `${W}/preorders/:id/resume`,
      route((ctx, p): PreorderWindow => {
        manage(ctx);
        const w = windowOf(ctx, p.id);
        w.active = true;
        w.version += 1;
        audit(ctx, 'preorder.window.resumed', w.id, `Resumed ${w.name}`);
        return windowView(st(ctx), w);
      })
    ),
    http.put(
      `${W}/preorders/:id/creatives/:slot`,
      route(async (ctx, p): Promise<PreorderWindow> => {
        const b = await json(ctx.request, z.object({ assetId: z.string() }));
        manage(ctx);
        const w = windowOf(ctx, p.id);
        const slot = CreativeSlotSchema.safeParse(p.slot);
        if (!slot.success) throw notFound('No such creative slot.');
        const asset = st(ctx).assets[b.assetId];
        if (!asset?.url) throw problem(422, 'ASSET_NOT_READY', 'Upload not finished', 'The image has not finished uploading. Try again.', { assetId: 'Upload not finished' });
        w.creatives = [...w.creatives.filter((c) => c.slot !== slot.data), { slot: slot.data, assetId: b.assetId, url: asset.url, width: asset.width, height: asset.height, ratioOff: ratioOff(slot.data, asset.width, asset.height) }];
        audit(ctx, 'preorder.creative.uploaded', w.id, `${CREATIVE_SLOTS.find((x) => x.slot === slot.data)?.name ?? slot.data} uploaded for ${w.name}`);
        return windowView(st(ctx), w);
      })
    ),
    http.delete(
      `${W}/preorders/:id/creatives/:slot`,
      route((ctx, p): PreorderWindow => {
        manage(ctx);
        const w = windowOf(ctx, p.id);
        w.creatives = w.creatives.filter((c) => c.slot !== p.slot);
        return windowView(st(ctx), w);
      })
    ),
    http.get(
      `${W}/preorders/:id/orders`,
      route((ctx, p): PreorderOrderPage => {
        view(ctx);
        const w = windowOf(ctx, p.id);
        const f = ordersQuery(ctx);
        const sort = parseSort(new URL(ctx.request.url).searchParams.getAll('sort'), (k) => PREORDER_ORDER_COLUMNS.some((c) => c.key === k));
        if (!sort) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'This table can’t be sorted that way.', { sort: 'Unknown column' });
        const all = filterOrders(ordersOf(st(ctx), w), f, sort);
        const pages = Math.max(1, Math.ceil(all.length / f.size));
        const page = Math.min(f.page, pages - 1);
        return { items: all.slice(page * f.size, page * f.size + f.size), total: all.length, page, size: f.size, sort: sort.map((k) => `${k.key},${k.desc ? 'desc' : 'asc'}`), partial: false };
      })
    ),
    http.get(
      `${W}/preorders/:id/summary`,
      route((ctx, p): PreorderSummary => {
        view(ctx);
        return summary(st(ctx), windowOf(ctx, p.id));
      })
    ),
    http.get(
      `${W}/preorders/:id/products`,
      route((ctx, p) => {
        view(ctx);
        windowOf(ctx, p.id);
        return { items: PRECAT.map((x) => ({ atin: atinOf(x.id), name: x.name, price: x.price, sellerName: x.seller })) };
      })
    ),
    http.get(
      `${W}/preorders/:id/exports/:file`,
      route((ctx, p) => {
        view(ctx);
        if (!ctx.permissions.includes('preorders:export')) throw forbidden('Exports need the preorders:export permission.');
        const s = st(ctx);
        const w = windowOf(ctx, p.id);
        const kind = (p.file ?? '').replace(/\.csv$/, '');
        const base = w.code.toLowerCase();
        const paid = ordersOf(s, w).filter(live);
        let out: Response;
        if (kind === 'orders') out = csv(`${base}-orders.csv`, ordersCsv(w, ordersOf(s, w)));
        else if (kind === 'orders-filtered') out = csv(`${base}-orders-filtered.csv`, ordersCsv(w, filterOrders(ordersOf(s, w), ordersQuery(ctx))));
        else if (kind === 'picking-list') {
          const units = new Map<string, { seller: string; n: number }>();
          paid.forEach((o) => o.lines.forEach((l) => units.set(l.name, { seller: l.sellerName, n: (units.get(l.name)?.n ?? 0) + l.qty })));
          out = csv(`${base}-picking-list.csv`, toCsvText([['Product', 'Seller', 'Units'], ...[...units.entries()].sort((a, b) => b[1].n - a[1].n).map(([name, v]) => [name, v.seller, v.n])]));
        } else if (kind === 'advertorial-report') {
          out = csv(`${base}-advertorial-report.csv`, toCsvText([['Placement', 'Creative', 'Impressions', 'Clicks', 'CTR %', 'Pre-orders'], ...summary(s, w).placements.map((a) => [a.name, a.creative ? 'yes' : 'no', a.impressions, a.clicks, a.ctr ?? 0, a.orders])]));
        } else throw notFound('No such export.');
        audit(ctx, 'preorders.export.created', w.id, `Exported ${kind} for ${w.name}`);
        return out;
      })
    ),
    http.post(
      `${W}/preorders/address-verify`,
      route(async (ctx) => {
        const b = await json(ctx.request, z.object({ line1: z.string(), zip: z.string(), city: z.string(), region: z.string(), country: z.string() }));
        view(ctx);
        const ok = b.line1.trim().length > 0 && b.zip.trim().length >= 3;
        return { verified: ok, placeId: ok ? `mock-place-${encodeURIComponent(`${b.line1}-${b.zip}`.toLowerCase().replace(/\s+/g, '-'))}` : null, formatted: ok ? `${b.line1.trim()}, ${b.city}, ${b.region} ${b.zip.trim()}` : null };
      })
    ),
    http.post(
      `${W}/preorders/:id/late-orders`,
      route(async (ctx, p): Promise<PreorderOrder> => {
        const b = await json(
          ctx.request,
          z.object({
            customerId: z.string().min(1),
            addressId: z.string().optional(),
            newAddress: z.object({ line1: z.string().min(1), zip: z.string().min(3), placeId: z.string().min(1) }).optional(),
            address: z
              .object({
                fullName: z.string().max(120), phone: z.string().max(40), line1: z.string().max(200), line2: z.string().max(200), city: z.string().max(120),
                region: z.string().max(40), zip: z.string().max(20), country: z.string().length(2), instructions: z.string().max(240)
              })
              .partial()
              .optional(),
            phoneConfirmed: z.literal(true),
            items: z.array(z.object({ atin: z.string(), qty: z.number().int().min(1).max(20) })).min(1),
            payment: LateAddPaymentSchema,
            reason: LateAddReasonSchema,
            note: z.string().max(200)
          })
        );
        view(ctx);
        if (!ctx.permissions.includes('preorders:late-add')) throw forbidden('Late adds need the preorders:late-add permission.');
        const s = st(ctx);
        // Like the BFF and delivery-runs: the key is required, and a replay returns the same order (charged once).
        const key = (ctx.request.headers.get('Idempotency-Key') ?? '').trim();
        if (key.length < 16 || key.length > 64) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Send a 16–64 character Idempotency-Key header.', { 'Idempotency-Key': 'Required' });
        const replays = LATE_ADD_REPLAYS.get(s) ?? new Map<string, PreorderOrder>();
        LATE_ADD_REPLAYS.set(s, replays);
        const replay = replays.get(key);
        if (replay) return replay;
        const w = windowOf(ctx, p.id);
        const status = statusOf(s, w);
        if (status !== 'OPEN' && status !== 'CLOSED') throw problem(409, 'PREORDER_NOT_LATE_ADDABLE', 'Late add not possible', status === 'DELIVERED' ? 'This pre-order has been delivered.' : 'Late adds are possible while a pre-order is open or closed and delivering.');
        const pay = s.settings.payment.lateAdd;
        if ((b.payment === 'SAVED_CARD' && !pay.savedCard) || (b.payment === 'PAYMENT_LINK' && !pay.paymentLink)) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'That payment option is switched off in settings.', { payment: 'Not allowed' });
        const cust = directory(w).find((c) => c.id === b.customerId);
        if (!cust) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Choose the customer.', { customerId: 'Unknown customer' });
        // Like the BFF: a saved address id is completed from the address book (fields sent win); a new one keeps its street.
        const saved = b.addressId ? cust.addresses.find((a) => a.id === b.addressId) : undefined;
        if (b.addressId && !saved) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'That saved address is not on the customer’s account.', { addressId: 'Choose another address or enter a new one' });
        const base = b.newAddress ? { line1: b.newAddress.line1, city: cust.city, region: cust.region, zip: b.newAddress.zip } : saved;
        const addr = base && { line1: b.address?.line1 || base.line1, city: b.address?.city || base.city, region: b.address?.region || base.region, zip: b.address?.zip || base.zip };
        if (!addr) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'Choose a saved address or verify a new one.', { addressId: 'Choose an address' });
        const lines = b.items.map((i) => {
          const prod = BY_ATIN.get(i.atin);
          if (!prod || prod.now) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'An item is not in this pre-order.', { items: `${i.atin} is not in this pre-order` });
          return { atin: i.atin, name: prod.name, sellerName: prod.seller, qty: i.qty, price: prod.price };
        });
        s.seq += 1;
        const fee = effectiveFees(w, s.settings.payment, currencyOfTargets(w.targets)).door;
        const order: PreorderOrder = {
          orderId: `ord-${w.id}-late-${s.seq}`, number: `${slugOf(w.targets)}-L${90 + s.orders.filter((o) => o.windowId === w.id && o.number.includes('-L9')).length}`, windowId: w.id,
          customerName: cust.name, phone: cust.phone, email: cust.email, addressLine: addr.line1, city: addr.city, region: addr.region, zip: addr.zip, deliveryNote: b.note,
          placedAt: new Date(nowOf(s)).toISOString(), channel: 'AGENT_LATE', trackingTag: `src=agent&po=${w.code}`, lines: lines.map(({ price: _p, ...l }) => l),
          units: lines.reduce((a, l) => a + l.qty, 0), fulfilment: 'DOOR', total: lines.reduce((a, l) => a + l.price * l.qty, 0) + fee, status: 'LATE_ADD',
          note: `${LATE_ADD_REASON_LABEL[b.reason]} · ${b.payment === 'PAYMENT_LINK' ? `pay-now link sent by SMS (expires in ${pay.linkExpiryHours} h)` : 'saved card charged'} · by ${ctx.action.actor}`
        };
        s.orders.push(order);
        replays.set(key, order);
        audit(ctx, 'preorder.late_order.added', w.id, `Late order ${order.number} added to ${w.name}`);
        return order;
      }, 201)
    ),
    http.get(
      `${W}/customers/search`,
      route((ctx) => {
        view(ctx);
        const qs = new URL(ctx.request.url).searchParams;
        const s = st(ctx);
        const w = s.windows.find((x) => x.id === qs.get('windowId')) ?? s.windows[0];
        const q = (qs.get('q') ?? '').trim().toLowerCase();
        return { items: w ? directory(w).filter((c) => !q || `${c.name} ${c.phone} ${c.email}`.toLowerCase().includes(q)) : [] };
      })
    )
  ];
}

/** Whether a city has pre-order on (exported for tests of the area rules against the seeded areas). */
export const mockAreaOn = (s: MockPreorderState, key: string) => {
  const a = areaFromKey(key);
  return areaOn(s.areas, a.country, a.region, a.city);
};
