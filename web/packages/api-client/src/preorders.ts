/* Storefront pre-orders (S273, docs/spikes/preorder-design-implementation.md §1–2): the resolution of the shopper's
   delivery place, the open/upcoming list for /pre-orders, the cart package `preorder` block, the waitlist and the
   door/pickup choice. Fees, currency, pickup window and payment methods are set per window in the back office and
   always come from these payloads — the storefront never hard-codes $7.99 or "free". Staff endpoints live in
   staff-preorders.ts. */
import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';
import { DeliveryFeeRuleRefSchema, MinorSchema } from './schemas/common';

/* ── Schemas ───────────────────────────────────────────────────── */

/** Shopper-facing stage of a window (drafts, paused and delivered windows never reach the storefront). */
export const PreorderStageSchema = z.enum(['UPCOMING', 'OPEN', 'CLOSED']);
export type PreorderStage = z.infer<typeof PreorderStageSchema>;

export const PreorderFulfilmentSchema = z.enum(['DOOR', 'PICKUP']);
export type PreorderFulfilment = z.infer<typeof PreorderFulfilmentSchema>;

/** Payment methods accepted for the window (back office › payment setup): card, wallet (Apple Pay / Google Pay), ACH. */
export const PreorderPaymentMethodSchema = z.enum(['CARD', 'WALLET', 'ACH']);
export type PreorderPaymentMethod = z.infer<typeof PreorderPaymentMethodSchema>;

/** What the storefront does where no pre-order is live: ship by UPS Ground (quoted) or collect waitlist emails only. */
export const PreorderFallbackSchema = z.enum(['SHIP', 'WAITLIST']);
export type PreorderFallback = z.infer<typeof PreorderFallbackSchema>;

/** Platform placement switches (back office › Pre-orders › Settings). */
export const PreorderPlacementsSchema = z.object({
  ribbon: z.boolean(),
  hero: z.boolean(),
  badge: z.boolean(),
  pdp: z.boolean(),
  cart: z.boolean(),
  family: z.boolean()
});
export type PreorderPlacements = z.infer<typeof PreorderPlacementsSchema>;

export const DEFAULT_PREORDER_PLACEMENTS: PreorderPlacements = { ribbon: true, hero: true, badge: true, pdp: true, cart: true, family: true };

/** A place as delivery-runs keys it: ISO-3166 alpha-2 country, subdivision code, city name. */
export const PreorderPlaceSchema = z.object({ country: z.string(), region: z.string(), city: z.string() });
export type PreorderPlace = z.infer<typeof PreorderPlaceSchema>;

export const PreorderCreativeSchema = z.object({ url: z.string(), width: z.number().int(), height: z.number().int() });
export type PreorderCreative = z.infer<typeof PreorderCreativeSchema>;

/** Money and payment setup of a window (shared by the resolution and the cart package block). */
export const PreorderTermsSchema = z.object({
  door: z.boolean(),
  pickup: z.boolean(),
  doorFeeMinor: MinorSchema,
  pickupFeeMinor: MinorSchema,
  currency: z.string(),
  /** Pickup hours on delivery day, e.g. "10am–2pm". */
  pickupWindow: z.string(),
  allowedMethods: z.array(PreorderPaymentMethodSchema).min(1)
});
export type PreorderTerms = z.infer<typeof PreorderTermsSchema>;

export const PreorderWindowSchema = PreorderTermsSchema.extend({
  id: z.string(),
  /** e.g. LBK-7; links carry it as po=. */
  code: z.string(),
  name: z.string(),
  ribbon: z.string(),
  headline: z.string(),
  categories: z.array(z.string()),
  /** Catalogue departments the categories belong to (the window page lists eligible products from them). */
  departments: z.array(z.string()),
  opensAt: z.string(),
  closesAt: z.string(),
  /** Calendar date (YYYY-MM-DD) in the market's time zone. */
  deliversOn: z.string(),
  /** IANA zone the window's dates are shown in. */
  timeZone: z.string(),
  showCount: z.boolean(),
  /** Paid pre-orders so far; null when the count is hidden or the window has not opened. */
  count: z.number().int().min(0).nullable(),
  creatives: z.object({ hero: PreorderCreativeSchema.nullable(), pdp: PreorderCreativeSchema.nullable() })
});
export type PreorderWindow = z.infer<typeof PreorderWindowSchema>;

/** GET /v1/preorders/resolve: what the storefront shows for the shopper's delivery place (no PII). */
export const PreorderResolutionSchema = z.object({
  place: PreorderPlaceSchema,
  /** Next-day metro city (served by geo zones): never pre-order. */
  metro: z.boolean(),
  /** A published window covers the place (even when switched off). */
  covered: z.boolean(),
  /** Window found, master switch on, window active and the area switched on. */
  on: z.boolean(),
  stage: PreorderStageSchema.nullable(),
  /** closesAt when open, opensAt when upcoming, delivery day when closed. */
  countdownTo: z.string().nullable(),
  window: PreorderWindowSchema.nullable(),
  fallback: PreorderFallbackSchema,
  placements: PreorderPlacementsSchema,
  supportPhone: z.string()
});
export type PreorderResolution = z.infer<typeof PreorderResolutionSchema>;

/** GET /v1/preorders/open: open and upcoming windows for the /pre-orders list. */
export const PreorderListItemSchema = z.object({
  id: z.string(),
  code: z.string(),
  name: z.string(),
  /** "Lubbock, TX" / "Toronto, ON · Canada · Mississauga, ON · Canada + 1 more". */
  where: z.string(),
  stage: z.enum(['UPCOMING', 'OPEN']),
  opensAt: z.string(),
  closesAt: z.string(),
  deliversOn: z.string(),
  timeZone: z.string(),
  /** First target city: "Shop for <city>" switches the delivery place to it. */
  place: PreorderPlaceSchema,
  /** A postal code in that city when the storefront can switch to it by ZIP. */
  zip: z.string().nullable(),
  heroUrl: z.string().nullable(),
  /** Door fee of the window (the "send to family" banner quotes it). */
  doorFeeMinor: MinorSchema,
  currency: z.string()
});
export type PreorderListItem = z.infer<typeof PreorderListItemSchema>;

export const PreorderOpenListSchema = z.object({ windows: z.array(PreorderListItemSchema), placements: PreorderPlacementsSchema });
export type PreorderOpenList = z.infer<typeof PreorderOpenListSchema>;

export const PreorderOptionSchema = z.object({ fulfilment: PreorderFulfilmentSchema, feeMinor: MinorSchema, /** The delivery fee rule behind this fee (ADR 0005 §3). */ rule: DeliveryFeeRuleRefSchema.optional() });
export type PreorderOption = z.infer<typeof PreorderOptionSchema>;

/** Cart / checkout `packages[].preorder`: lines eligible for the place's window are one pre-order package. */
export const CartPreorderSchema = PreorderTermsSchema.extend({
  windowId: z.string(),
  code: z.string(),
  name: z.string(),
  stage: PreorderStageSchema,
  opensAt: z.string(),
  closesAt: z.string(),
  deliversOn: z.string(),
  timeZone: z.string(),
  fulfilment: PreorderFulfilmentSchema,
  /** The delivery fee of the chosen fulfilment: the rule and its version. */
  feeRule: DeliveryFeeRuleRefSchema.optional(),
  options: z.array(PreorderOptionSchema)
});
export type CartPreorder = z.infer<typeof CartPreorderSchema>;

/** Last-touch attribution sent with checkout (src = ig | fb | tt | wa | email | sms | app | hero | ribbon | pdp | page). */
export const PreorderAttributionSchema = z.object({
  src: z.string().min(1).max(32),
  cr: z.string().max(64).optional(),
  po: z.string().max(32).optional()
});
export type PreorderAttribution = z.infer<typeof PreorderAttributionSchema>;

export const PreorderWaitlistRequestSchema = z.object({ email: z.email(), place: PreorderPlaceSchema.optional() });
export type PreorderWaitlistRequest = z.infer<typeof PreorderWaitlistRequestSchema>;
export const PreorderWaitlistResponseSchema = z.object({ status: z.enum(['subscribed']), city: z.string() });
export type PreorderWaitlistResponse = z.infer<typeof PreorderWaitlistResponseSchema>;

/** POST /v1/preorders/waitlist/unsubscribe: the token of a waitlist email's unsubscribe link (no sign-in). */
export const PreorderWaitlistLeaveRequestSchema = z.object({ token: z.string().trim().min(1).max(200) });
export type PreorderWaitlistLeaveRequest = z.infer<typeof PreorderWaitlistLeaveRequestSchema>;
export const PreorderWaitlistLeaveResponseSchema = z.object({ status: z.enum(['unsubscribed']) });
export type PreorderWaitlistLeaveResponse = z.infer<typeof PreorderWaitlistLeaveResponseSchema>;

export const PreorderFulfilmentRequestSchema = z.object({ windowId: z.string().min(1), fulfilment: PreorderFulfilmentSchema });
export type PreorderFulfilmentRequest = z.infer<typeof PreorderFulfilmentRequestSchema>;

/** `preorder` on an order confirmation: the "You're in" facts. */
export const PreorderConfirmationSchema = z.object({
  number: z.string(),
  windowName: z.string(),
  deliversOn: z.string(),
  closesAt: z.string(),
  timeZone: z.string(),
  /** Units in the same order that ship now. */
  otherItemCount: z.number().int().min(0)
});
export type PreorderConfirmation = z.infer<typeof PreorderConfirmationSchema>;

/* ── Client methods (spread into createApiClient) ─────────────── */

export function preorderMethods(request: Requester) {
  return {
    /** Resolution for the shopper's delivery location, or for an explicit place (the window pages of /pre-orders). */
    resolvePreorder: (place?: PreorderPlace, signal?: AbortSignal): Promise<PreorderResolution> =>
      request('/v1/preorders/resolve', PreorderResolutionSchema, { query: place ? { country: place.country, region: place.region, city: place.city } : undefined, signal }),
    getOpenPreorders: (signal?: AbortSignal): Promise<PreorderOpenList> => request('/v1/preorders/open', PreorderOpenListSchema, { signal }),
    joinPreorderWaitlist: (body: PreorderWaitlistRequest): Promise<PreorderWaitlistResponse> =>
      request('/v1/preorders/waitlist', PreorderWaitlistResponseSchema, { method: 'POST', body }),
    /** One-click leave from a waitlist email (`/pre-orders/waitlist/unsubscribe?token=`); leaving twice is fine. */
    leavePreorderWaitlist: (token: string): Promise<PreorderWaitlistLeaveResponse> =>
      request('/v1/preorders/waitlist/unsubscribe', PreorderWaitlistLeaveResponseSchema, { method: 'POST', body: { token } })
  };
}

/* ── Query keys and options ────────────────────────────────────── */

export const preorderKeys = {
  all: ['preorders'] as const,
  resolutions: () => [...preorderKeys.all, 'resolution'] as const,
  /** No place = the shopper's delivery location (invalidate after the deliver-to place changes). */
  resolution: (place?: PreorderPlace) => [...preorderKeys.resolutions(), place ? `${place.country}/${place.region}/${place.city}` : 'delivery-location'] as const,
  open: () => [...preorderKeys.all, 'open'] as const,
  confirmation: (orderNumber: string) => [...preorderKeys.all, 'confirmation', orderNumber] as const
};

interface PreorderApi {
  resolvePreorder: (place?: PreorderPlace, signal?: AbortSignal) => Promise<PreorderResolution>;
  getOpenPreorders: (signal?: AbortSignal) => Promise<PreorderOpenList>;
  getCheckoutStatus: (orderNumber: string) => Promise<{ preorder?: PreorderConfirmation | null; totalMinor: number; currency: string }>;
}

const MINUTE = 60_000;

export const preorderQueries = {
  resolution: (api: Pick<PreorderApi, 'resolvePreorder'>, place?: PreorderPlace) =>
    queryOptions({ queryKey: preorderKeys.resolution(place), queryFn: ({ signal }) => api.resolvePreorder(place, signal), staleTime: MINUTE }),
  open: (api: Pick<PreorderApi, 'getOpenPreorders'>) => queryOptions({ queryKey: preorderKeys.open(), queryFn: ({ signal }) => api.getOpenPreorders(signal), staleTime: MINUTE }),
  confirmation: (api: Pick<PreorderApi, 'getCheckoutStatus'>, orderNumber: string) =>
    queryOptions({
      queryKey: preorderKeys.confirmation(orderNumber),
      /** The "You're in" facts of a placed order; preorder is null for orders without a pre-order package. */
      queryFn: async () => {
        const c = await api.getCheckoutStatus(orderNumber);
        return { preorder: c.preorder ?? null, totalMinor: c.totalMinor, currency: c.currency };
      },
      staleTime: Infinity,
      enabled: orderNumber.length > 0
    })
};

/* ── Pure helpers (storefront and mobile share them) ──────────── */

const DAY_MS = 86_400_000;

export interface CountdownParts {
  days: number;
  hours: number;
  minutes: number;
  seconds: number;
  /** Whole milliseconds left (0 when the target has passed). */
  remainingMs: number;
}

/** Days / hours / minutes / seconds until a target (never negative). */
export function countdownParts(target: string | number | Date, now: number): CountdownParts {
  const t = typeof target === 'number' ? target : new Date(target).getTime();
  const diff = Math.max(0, t - now);
  return {
    days: Math.floor(diff / DAY_MS),
    hours: Math.floor((diff % DAY_MS) / 3_600_000),
    minutes: Math.floor((diff % 3_600_000) / 60_000),
    seconds: Math.floor((diff % 60_000) / 1000),
    remainingMs: diff
  };
}

const p2 = (n: number) => String(n).padStart(2, '0');

/** "3d 04h 12m 09s" (the ribbon / PDP / cart chip). */
export function formatCountdownShort(c: CountdownParts): string {
  return `${c.days}d ${p2(c.hours)}h ${p2(c.minutes)}m ${p2(c.seconds)}s`;
}

/** The four hero blocks: ["3","04","12","09"] (days, hrs, min, sec). */
export function countdownBlocks(c: CountdownParts): [string, string, string, string] {
  return [String(c.days), p2(c.hours), p2(c.minutes), p2(c.seconds)];
}

/** Fee of a fulfilment choice from the window's payment setup (door or pickup). */
export function preorderFeeMinor(terms: Pick<PreorderTerms, 'doorFeeMinor' | 'pickupFeeMinor'>, fulfilment: PreorderFulfilment): number {
  return fulfilment === 'PICKUP' ? terms.pickupFeeMinor : terms.doorFeeMinor;
}

/** Fulfilment options the window offers, in display order (door, then pickup). */
export function preorderOptions(terms: PreorderTerms): PreorderOption[] {
  const out: PreorderOption[] = [];
  if (terms.door) out.push({ fulfilment: 'DOOR', feeMinor: terms.doorFeeMinor });
  if (terms.pickup) out.push({ fulfilment: 'PICKUP', feeMinor: terms.pickupFeeMinor });
  return out;
}

/** Only an open window can be checked out; upcoming and closed windows lock the pre-order lines. */
export const preorderLocked = (stage: PreorderStage | null | undefined): boolean => stage !== 'OPEN';

export interface CartSplit {
  /** Selected units across the cart. */
  itemCount: number;
  itemsMinor: number;
  preorderItemsMinor: number;
  preorderUnits: number;
  /** Door or pickup fee of the pre-order package (0 when there are no pre-order lines). */
  preorderDeliveryMinor: number;
  /** First (cheapest quoted) option of every other package. */
  otherShippingMinor: number;
  otherUnits: number;
  totalMinor: number;
  hasPreorder: boolean;
  hasOther: boolean;
  /** Pre-order lines in the cart while the window is not open: checkout is blocked. */
  locked: boolean;
}

interface SplitPackage {
  preorder?: Pick<CartPreorder, 'stage' | 'fulfilment' | 'doorFeeMinor' | 'pickupFeeMinor'> | null;
  lines: { selected: boolean; quantity: number; lineTotalMinor: number }[];
  deliveryOptions: { feeMinor: number }[];
}

/** Cart summary split: pre-order package (door or pickup fee from the window) and the packages that ship now. */
export function cartSplit(packages: readonly SplitPackage[]): CartSplit {
  let preorderItemsMinor = 0;
  let preorderUnits = 0;
  let otherItemsMinor = 0;
  let otherUnits = 0;
  let preorderDeliveryMinor = 0;
  let otherShippingMinor = 0;
  let locked = false;
  for (const p of packages) {
    const sel = p.lines.filter((l) => l.selected);
    if (!sel.length) continue;
    const units = sel.reduce((a, l) => a + l.quantity, 0);
    const minor = sel.reduce((a, l) => a + l.lineTotalMinor, 0);
    if (p.preorder) {
      preorderItemsMinor += minor;
      preorderUnits += units;
      preorderDeliveryMinor += preorderFeeMinor(p.preorder, p.preorder.fulfilment);
      if (preorderLocked(p.preorder.stage)) locked = true;
    } else {
      otherItemsMinor += minor;
      otherUnits += units;
      otherShippingMinor += p.deliveryOptions[0]?.feeMinor ?? 0;
    }
  }
  const itemsMinor = preorderItemsMinor + otherItemsMinor;
  return {
    itemCount: preorderUnits + otherUnits,
    itemsMinor,
    preorderItemsMinor,
    preorderUnits,
    preorderDeliveryMinor,
    otherShippingMinor,
    otherUnits,
    totalMinor: itemsMinor + preorderDeliveryMinor + otherShippingMinor,
    hasPreorder: preorderUnits > 0,
    hasOther: otherUnits > 0,
    locked
  };
}

/** Where a product stands for the shopper's resolution: badge, add-to-cart state (spec §5 Badge, PDP). */
export type PreorderItemState =
  | { kind: 'preorder'; stage: PreorderStage; locked: boolean; showBadge: boolean }
  | { kind: 'ships-now' }
  | { kind: 'metro' }
  | { kind: 'waitlist' }
  | { kind: 'none' };

/** The platform fallback (ship by carrier, or waitlist only) applies to a place a pre-order window covers while that
    pre-order is off. A place no window covers (a next-day metro, or any other city) is a normal delivery place: no
    pre-order UI and no fallback (design at-preorder.js `resolve` returns null there). */
export function preorderFallback(r: PreorderResolution | null | undefined): PreorderResolution['fallback'] | null {
  if (!r || r.metro || r.on || !r.covered) return null;
  return r.fallback;
}

/** Pre-order item = the catalogue marks it eligible for the place's window and the pre-order is on there. */
export function preorderItemState(eligible: boolean | undefined, r: PreorderResolution | null | undefined): PreorderItemState {
  if (!r) return { kind: 'none' };
  if (r.metro) return { kind: 'metro' };
  if (r.on && r.stage) {
    if (eligible) return { kind: 'preorder', stage: r.stage, locked: preorderLocked(r.stage), showBadge: r.placements.badge };
    return { kind: 'ships-now' };
  }
  const fallback = preorderFallback(r);
  if (fallback === 'WAITLIST') return { kind: 'waitlist' };
  if (fallback === 'SHIP') return { kind: 'ships-now' };
  return { kind: 'none' };
}
