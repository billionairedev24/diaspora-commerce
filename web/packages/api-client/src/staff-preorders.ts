/* Back office Pre-orders (S274): the staff BFF routes /v1/staff/preorders/** that mirror delivery-runs' staff API
   (docs/spikes/preorder-design-implementation.md §2), their Zod schemas, TanStack Query key factories and queryOptions,
   and the rules the console and the mock BFF share (status, publish checks, area coverage, late-add validation, fees).
   Money is in minor units throughout; dates are ISO-8601 instants, except `deliversOn` (a calendar date). */
import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { ApiError, problemFrom } from './errors';
import { absolute, type ApiClientOptions, type FetchLike, joinUrl, type Requester } from './http';
import { tableSearchParams } from './table-query';

/* ── Enums ─────────────────────────────────────────────── */

export const PreorderStatusSchema = z.enum(['DRAFT', 'PAUSED', 'UPCOMING', 'OPEN', 'CLOSED', 'DELIVERED']);
export type PreorderStatus = z.infer<typeof PreorderStatusSchema>;

/** List tabs: All, Open, Scheduled (upcoming), Closed (closed + delivered) and Drafts. */
export const PreorderListTabSchema = z.enum(['all', 'open', 'upcoming', 'closed', 'draft']);
export type PreorderListTab = z.infer<typeof PreorderListTabSchema>;

export const CreativeSlotSchema = z.enum(['HERO', 'PDP', 'STORY', 'FEED', 'EMAIL']);
export type CreativeSlot = z.infer<typeof CreativeSlotSchema>;

export const CommKindSchema = z.enum(['ANNOUNCE', 'REMIND_48H', 'LAST_CALL_6H', 'CLOSED', 'OUT_FOR_DELIVERY', 'PAID_SOCIAL']);
export type CommKind = z.infer<typeof CommKindSchema>;

export const PreorderChannelSchema = z.enum([
  'SITE_HERO', 'SITE_RIBBON', 'SITE_PDP', 'SITE_PAGE', 'SITE_DIRECT', 'APP', 'INSTAGRAM', 'FACEBOOK', 'TIKTOK', 'WHATSAPP', 'EMAIL', 'SMS', 'AGENT_LATE'
]);
export type PreorderChannel = z.infer<typeof PreorderChannelSchema>;

export const PreorderOrderStatusSchema = z.enum(['PAID', 'LATE_ADD', 'CANCELLED']);
export type PreorderOrderStatus = z.infer<typeof PreorderOrderStatusSchema>;

export const FulfilmentSchema = z.enum(['DOOR', 'PICKUP']);
export type Fulfilment = z.infer<typeof FulfilmentSchema>;

export const PreorderFallbackSchema = z.enum(['SHIP', 'WAITLIST']);
export type PreorderFallback = z.infer<typeof PreorderFallbackSchema>;

export const PaymentMethodSchema = z.enum(['CARD', 'WALLET', 'ACH']);
export type PaymentMethod = z.infer<typeof PaymentMethodSchema>;

export const LateAddPaymentSchema = z.enum(['SAVED_CARD', 'PAYMENT_LINK']);
export type LateAddPayment = z.infer<typeof LateAddPaymentSchema>;

export const LateAddReasonSchema = z.enum(['CUSTOMER_CALLED', 'LIVE_CHAT', 'PAYMENT_FAILED', 'GOODWILL']);
export type LateAddReason = z.infer<typeof LateAddReasonSchema>;

export const PlacementKeySchema = z.enum(['ribbon', 'hero', 'badge', 'pdp', 'cart', 'family']);
export type PlacementKey = z.infer<typeof PlacementKeySchema>;

/* ── Labels (design copy) ──────────────────────────────── */

/**
 * Effective state of a window = master switch AND its own switch AND an area on. PAUSED = the window's own switch is
 * off; GLOBAL_OFF = the master switch holds it; AREA_OFF = every target area is off; NOT_LIVE = draft or delivered.
 */
export const PreorderSwitchStateSchema = z.enum(['ON', 'PAUSED', 'GLOBAL_OFF', 'AREA_OFF', 'NOT_LIVE']);
export type PreorderSwitchState = z.infer<typeof PreorderSwitchStateSchema>;

/** What the list and detail say about a window's switch ("Off" is the window's own switch, distinct from the global one). */
export const PREORDER_SWITCH_LABEL: Record<PreorderSwitchState, string> = {
  ON: 'On', PAUSED: 'Off', GLOBAL_OFF: 'Off (global switch)', AREA_OFF: 'Area off', NOT_LIVE: '—'
};

export const PREORDER_STATUS_LABEL: Record<PreorderStatus, string> = {
  OPEN: 'Open', UPCOMING: 'Scheduled', CLOSED: 'Closed · delivering', DELIVERED: 'Delivered', DRAFT: 'Draft', PAUSED: 'Paused'
};

export const PREORDER_CHANNEL_LABEL: Record<PreorderChannel, string> = {
  SITE_HERO: 'Site · hero', SITE_RIBBON: 'Site · ribbon', SITE_PDP: 'Site · product page', SITE_PAGE: 'Site · pre-order page', SITE_DIRECT: 'Site · direct',
  APP: 'Mobile app', INSTAGRAM: 'Instagram', FACEBOOK: 'Facebook', TIKTOK: 'TikTok', WHATSAPP: 'WhatsApp', EMAIL: 'Email', SMS: 'SMS', AGENT_LATE: 'Agent (late)'
};

/** Channels in the Orders filter, in the design's order. */
export const PREORDER_FILTER_CHANNELS: PreorderChannel[] = ['SITE_HERO', 'SITE_RIBBON', 'SITE_PDP', 'APP', 'INSTAGRAM', 'FACEBOOK', 'TIKTOK', 'WHATSAPP', 'EMAIL', 'SMS', 'AGENT_LATE'];

export const PREORDER_ORDER_STATUS_LABEL: Record<PreorderOrderStatus, string> = { PAID: 'Paid', LATE_ADD: 'Late add', CANCELLED: 'Cancelled' };

export const LATE_ADD_REASON_LABEL: Record<LateAddReason, string> = {
  CUSTOMER_CALLED: 'Customer called', LIVE_CHAT: 'Live chat', PAYMENT_FAILED: 'Payment failed at close', GOODWILL: 'Goodwill'
};

export const PREORDER_CATEGORIES = ['Pantry', 'Spices', 'Snacks', 'Oils', 'Grains & flours', 'Beverages'] as const;

export interface CreativeSlotSpec {
  slot: CreativeSlot;
  name: string;
  spec: string;
  width: number;
  height: number;
  where: string;
  required: boolean;
}

/** The five advertorial slots (the hero is required to publish). */
export const CREATIVE_SLOTS: CreativeSlotSpec[] = [
  { slot: 'HERO', name: 'Storefront hero', spec: '1920 × 640', width: 1920, height: 640, where: 'Home + pre-order page', required: true },
  { slot: 'PDP', name: 'Product page banner', spec: '1200 × 300', width: 1200, height: 300, where: 'Eligible product pages', required: false },
  { slot: 'STORY', name: 'Social story', spec: '1080 × 1920', width: 1080, height: 1920, where: 'Instagram, TikTok, WhatsApp', required: false },
  { slot: 'FEED', name: 'Social feed post', spec: '1080 × 1080', width: 1080, height: 1080, where: 'Instagram + Facebook feed', required: false },
  { slot: 'EMAIL', name: 'Email header', spec: '1200 × 400', width: 1200, height: 400, where: 'Announcement + reminders', required: false }
];

export interface CommSpec {
  kind: CommKind;
  title: string;
  when: string;
  channels: string;
}

export const COMMS: CommSpec[] = [
  { kind: 'ANNOUNCE', title: 'Announcement', when: 'At opening time', channels: 'Email · push · SMS' },
  { kind: 'REMIND_48H', title: 'Reminder', when: '48 hours before close', channels: 'Email · push' },
  { kind: 'LAST_CALL_6H', title: 'Last call', when: '6 hours before close', channels: 'Push · SMS' },
  { kind: 'CLOSED', title: 'Closed + delivery date', when: 'At closing time', channels: 'Email' },
  { kind: 'OUT_FOR_DELIVERY', title: 'Out for delivery', when: 'Delivery day (driver app events)', channels: 'SMS · push' },
  { kind: 'PAID_SOCIAL', title: 'Paid social', when: 'Opening → closing, geo-targeted', channels: 'Instagram · Facebook · TikTok' }
];

/** Ratio tolerance before a creative is flagged "Ratio off" (warn, not block). */
export const RATIO_TOLERANCE = 0.12;

export const ratioOff = (slot: CreativeSlot, width: number, height: number): boolean => {
  const spec = CREATIVE_SLOTS.find((s) => s.slot === slot);
  if (!spec || !width || !height) return false;
  const want = spec.width / spec.height;
  return Math.abs(width / height - want) / want > RATIO_TOLERANCE;
};

/* ── Schemas ───────────────────────────────────────────── */

export const PreorderTargetSchema = z.object({ key: z.string(), label: z.string() });
export type PreorderTarget = z.infer<typeof PreorderTargetSchema>;

export const PreorderCreativeSchema = z.object({
  slot: CreativeSlotSchema,
  assetId: z.string(),
  url: z.string(),
  width: z.number().int(),
  height: z.number().int(),
  ratioOff: z.boolean()
});
export type PreorderCreative = z.infer<typeof PreorderCreativeSchema>;

export const PreorderWindowSchema = z.object({
  id: z.string(),
  code: z.string(),
  name: z.string(),
  targets: z.array(PreorderTargetSchema),
  where: z.string(),
  opensAt: z.string(),
  closesAt: z.string(),
  deliversOn: z.string(),
  ribbon: z.string(),
  headline: z.string(),
  smsCopy: z.string(),
  categories: z.array(z.string()),
  door: z.boolean(),
  pickup: z.boolean(),
  pickupPoint: z.string().nullable(),
  /** Per-window fee overrides in minor units (null = the platform default for the window's currency). */
  doorFeeOverride: z.number().int().nullable(),
  pickupFeeOverride: z.number().int().nullable(),
  currency: z.string(),
  /** Fees shoppers pay for this window (override, else the platform default). */
  effectiveDoorFee: z.number().int(),
  effectivePickupFee: z.number().int(),
  showCount: z.boolean(),
  socialBudget: z.number().int(),
  comms: z.array(CommKindSchema),
  draft: z.boolean(),
  active: z.boolean(),
  status: PreorderStatusSchema,
  /** True when the master switch or every target area is off. */
  areaOff: z.boolean(),
  /** Published and not delivered: the window has its own on/off switch. */
  switchable: z.boolean().default(false),
  /** Which switch holds the window off the storefront (ON when live). */
  switchState: PreorderSwitchStateSchema.default('NOT_LIVE'),
  creatives: z.array(PreorderCreativeSchema),
  orderCount: z.number().int(),
  revenue: z.number().int(),
  /** Optimistic-lock version; PUT sends it back as If-Match. */
  version: z.number().int()
});
export type PreorderWindow = z.infer<typeof PreorderWindowSchema>;

export const PreorderListRowSchema = PreorderWindowSchema.pick({
  id: true, code: true, name: true, where: true, opensAt: true, closesAt: true, deliversOn: true, status: true, areaOff: true, orderCount: true, revenue: true, currency: true,
  active: true, switchable: true, switchState: true, version: true
}).extend({ active: z.boolean().default(true), version: z.number().int().default(0) });
export type PreorderListRow = z.infer<typeof PreorderListRowSchema>;

export const PreorderListSchema = z.object({
  items: z.array(PreorderListRowSchema),
  counts: z.object({ all: z.number().int(), open: z.number().int(), upcoming: z.number().int(), closed: z.number().int(), draft: z.number().int() }),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  enabled: z.boolean()
});
export type PreorderList = z.infer<typeof PreorderListSchema>;

/** Body of POST /v1/staff/preorders (a draft) and PUT /v1/staff/preorders/{id}. */
export const PreorderWindowInputSchema = z.object({
  name: z.string().max(120),
  targets: z.array(z.string()),
  opensAt: z.string(),
  closesAt: z.string(),
  deliversOn: z.string(),
  ribbon: z.string().max(80),
  headline: z.string().max(140),
  smsCopy: z.string(),
  categories: z.array(z.string()),
  door: z.boolean(),
  pickup: z.boolean(),
  pickupPoint: z.string().nullable(),
  doorFeeOverride: z.number().int().min(0).nullable(),
  pickupFeeOverride: z.number().int().min(0).nullable(),
  showCount: z.boolean(),
  socialBudget: z.number().int().min(0),
  comms: z.array(CommKindSchema)
});
export type PreorderWindowInput = z.infer<typeof PreorderWindowInputSchema>;

export const PreorderOrderLineSchema = z.object({ atin: z.string(), name: z.string(), sellerName: z.string(), qty: z.number().int() });
export type PreorderOrderLine = z.infer<typeof PreorderOrderLineSchema>;

/** A late add's Idempotency-Key (16–64 characters), made when the dialog opens and kept until the order is added. */
export const newLateAddKey = (random: () => string = () => crypto.randomUUID()): string => `late-${random()}`;

export const PreorderOrderSchema = z.object({
  orderId: z.string(),
  number: z.string(),
  windowId: z.string(),
  customerName: z.string(),
  phone: z.string(),
  email: z.string(),
  addressLine: z.string(),
  city: z.string(),
  region: z.string(),
  zip: z.string(),
  deliveryNote: z.string(),
  placedAt: z.string(),
  channel: PreorderChannelSchema,
  trackingTag: z.string(),
  lines: z.array(PreorderOrderLineSchema),
  units: z.number().int(),
  fulfilment: FulfilmentSchema,
  total: z.number().int(),
  status: PreorderOrderStatusSchema,
  note: z.string()
});
export type PreorderOrder = z.infer<typeof PreorderOrderSchema>;

export const PreorderOrderPageSchema = z.object({
  items: z.array(PreorderOrderSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  sort: z.array(z.string()).default([]),
  partial: z.boolean().default(false)
});
export type PreorderOrderPage = z.infer<typeof PreorderOrderPageSchema>;

export const PreorderSummarySchema = z.object({
  kpis: z.object({
    preorders: z.number().int(),
    cancelled: z.number().int(),
    customers: z.number().int(),
    units: z.number().int(),
    revenue: z.number().int(),
    avgOrder: z.number().int().nullable(),
    lateAdds: z.number().int()
  }),
  ordersTotal: z.number().int(),
  /** Calendar days (YYYY-MM-DD) from the opening day; afterClose marks days after the closing date. */
  byDay: z.array(z.object({ date: z.string(), count: z.number().int(), afterClose: z.boolean() })),
  byChannel: z.array(z.object({ channel: PreorderChannelSchema, count: z.number().int() })),
  topProducts: z.array(z.object({ name: z.string(), units: z.number().int() })),
  fulfilmentSplit: z.object({ door: z.number().int(), pickup: z.number().int() }),
  placements: z.array(
    z.object({ key: z.string(), name: z.string(), creative: z.boolean(), impressions: z.number().int(), clicks: z.number().int(), ctr: z.number().nullable(), orders: z.number().int() })
  )
});
export type PreorderSummary = z.infer<typeof PreorderSummarySchema>;

/** A late add's payment link lasts 1–24 hours: orders-service creates links for at most 24 h (delivery-runs enforces it). */
export const LINK_EXPIRY_MIN_HOURS = 1;
export const LINK_EXPIRY_MAX_HOURS = 24;

/** Why a payment-link lifetime cannot be saved (null = fine). */
export function linkExpiryProblem(hours: number): string | null {
  if (!Number.isInteger(hours)) return 'Enter whole hours';
  if (hours < LINK_EXPIRY_MIN_HOURS) return `Payment links last at least ${LINK_EXPIRY_MIN_HOURS} hour`;
  if (hours > LINK_EXPIRY_MAX_HOURS) return `Payment links expire after at most ${LINK_EXPIRY_MAX_HOURS} hours`;
  return null;
}

export const PreorderPaymentSettingsSchema = z.object({
  /** Door delivery fee per currency, minor units (default USD 799). */
  doorFee: z.record(z.string(), z.number().int().min(0)),
  pickupFee: z.record(z.string(), z.number().int().min(0)),
  pickupWindow: z.string(),
  allowedMethods: z.array(PaymentMethodSchema),
  lateAdd: z.object({
    savedCard: z.boolean(),
    paymentLink: z.boolean(),
    linkExpiryHours: z
      .number()
      .int()
      .min(LINK_EXPIRY_MIN_HOURS, `Payment links last at least ${LINK_EXPIRY_MIN_HOURS} hour`)
      .max(LINK_EXPIRY_MAX_HOURS, `Payment links expire after at most ${LINK_EXPIRY_MAX_HOURS} hours`)
  }),
  missedDelivery: z.object({ offerRefund: z.boolean(), offerNewDate: z.boolean() })
});
export type PreorderPaymentSettings = z.infer<typeof PreorderPaymentSettingsSchema>;

export const PreorderSettingsSchema = z.object({
  enabled: z.boolean(),
  fallback: PreorderFallbackSchema,
  placements: z.record(PlacementKeySchema, z.boolean()),
  payment: PreorderPaymentSettingsSchema,
  version: z.number().int()
});
export type PreorderSettings = z.infer<typeof PreorderSettingsSchema>;
export type PreorderSettingsInput = Omit<PreorderSettings, 'version'>;

export const AreaLevelSchema = z.enum(['COUNTRY', 'REGION', 'AREA']);
export type AreaLevel = z.infer<typeof AreaLevelSchema>;

export const PreorderAreaRowSchema = z.object({
  /** Country code, country/region, or the area key (country/region[/city]) on the AREA level. */
  key: z.string(),
  name: z.string(),
  coverage: z.string(),
  windows: z.number().int(),
  /** Areas switched on / in this row (an AREA row is 1 or 0 of 1). */
  on: z.number().int(),
  total: z.number().int(),
  leaf: z.boolean()
});
export type PreorderAreaRow = z.infer<typeof PreorderAreaRowSchema>;

export const PreorderAreaPageSchema = z.object({
  level: AreaLevelSchema,
  country: z.string().nullable(),
  region: z.string().nullable(),
  countryName: z.string().nullable(),
  regionName: z.string().nullable(),
  regionLabel: z.string(),
  rows: z.array(PreorderAreaRowSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  onCount: z.number().int(),
  offCount: z.number().int(),
  /** Every area that is switched on (the wizard's "reuse a saved area"). */
  saved: z.array(PreorderTargetSchema)
});
export type PreorderAreaPage = z.infer<typeof PreorderAreaPageSchema>;

export const PreorderSwitchOutcomeSchema = z.object({
  id: z.string(),
  outcome: z.enum(['SWITCHED', 'UNCHANGED', 'SKIPPED']),
  window: PreorderListRowSchema.partial().nullable().default(null),
  code: z.string().nullable().default(null),
  reason: z.string().nullable().default(null)
});
export const PreorderBulkSwitchSchema = z.object({
  items: z.array(PreorderSwitchOutcomeSchema),
  switched: z.number().int(),
  unchanged: z.number().int(),
  skipped: z.number().int()
});
export type PreorderBulkSwitch = z.infer<typeof PreorderBulkSwitchSchema>;

export const PreorderRegionRowSchema = z.object({
  key: z.string(),
  name: z.string(),
  on: z.boolean(),
  version: z.number().int(),
  /** Live pre-orders selling here: removing the row is refused while they do. */
  windows: z.array(z.string())
});
export type PreorderRegionRow = z.infer<typeof PreorderRegionRowSchema>;

/** One state's / province's coverage for the in-place editor; `version` goes back as If-Match. */
export const PreorderRegionSchema = z.object({
  country: z.string(),
  region: z.string(),
  countryName: z.string(),
  regionName: z.string(),
  regionLabel: z.string(),
  wholeRegion: PreorderRegionRowSchema.nullable(),
  cities: z.array(PreorderRegionRowSchema),
  version: z.number().int()
});
export type PreorderRegion = z.infer<typeof PreorderRegionSchema>;

/** Body of PUT …/areas/regions/{country}/{region}: the region as it should be (cities left out are removed). */
export interface PreorderRegionInput {
  everyCity: boolean | null;
  cities: { city: string; on: boolean }[];
}

export const PlaceTypeSchema = z.enum(['COUNTRY', 'REGION', 'CITY']);
export const PlaceSchema = z.object({ key: z.string(), code: z.string(), name: z.string(), label: z.string(), sub: z.string(), type: PlaceTypeSchema });
export type Place = z.infer<typeof PlaceSchema>;
export const PlaceListSchema = z.object({ items: z.array(PlaceSchema), regionLabel: z.string() });
export type PlaceList = z.infer<typeof PlaceListSchema>;

export const LateAddSavedAddressSchema = z.object({
  id: z.string(),
  label: z.string(),
  fullName: z.string().optional(),
  phone: z.string().optional(),
  line1: z.string(),
  line2: z.string().optional(),
  city: z.string(),
  region: z.string(),
  zip: z.string(),
  country: z.string().optional(),
  instructions: z.string().optional()
});
export type LateAddSavedAddress = z.infer<typeof LateAddSavedAddressSchema>;

export const LateAddCustomerSchema = z.object({
  id: z.string(),
  name: z.string(),
  phone: z.string(),
  email: z.string(),
  city: z.string(),
  region: z.string(),
  country: z.string(),
  addresses: z.array(LateAddSavedAddressSchema),
  /** identity could not list the customer's addresses (enter a new one). */
  addressesUnavailable: z.boolean().optional()
});
export type LateAddCustomer = z.infer<typeof LateAddCustomerSchema>;

/**
 * The delivery address snapshot of a late order (delivery-runs `PreorderLateAddress`). With a saved `addressId`
 * delivery-runs needs street, city, region and postal code (else 422 `delivery-runs.address-details-required`); the
 * BFF fills blanks from identity, and the console sends what the customer picker already returned.
 */
export interface LateAddAddressSnapshot {
  fullName?: string;
  phone?: string;
  line1?: string;
  line2?: string;
  city?: string;
  region?: string;
  zip?: string;
  country?: string;
  instructions?: string;
}

/** The snapshot of a customer's saved address (customer name / phone / country as fallbacks); undefined if unknown. */
export function lateAddAddressSnapshot(customer: LateAddCustomer | null | undefined, addressId: string): LateAddAddressSnapshot | undefined {
  const a = customer?.addresses.find((x) => x.id === addressId);
  if (!customer || !a) return undefined;
  const raw: LateAddAddressSnapshot = {
    fullName: a.fullName || customer.name,
    phone: a.phone || customer.phone,
    line1: a.line1,
    line2: a.line2,
    city: a.city,
    region: a.region,
    zip: a.zip,
    country: a.country || customer.country,
    instructions: a.instructions
  };
  const out: LateAddAddressSnapshot = {};
  (Object.keys(raw) as (keyof LateAddAddressSnapshot)[]).forEach((k) => {
    const v = raw[k]?.trim();
    if (v) out[k] = v;
  });
  return out;
}
export const LateAddCustomerListSchema = z.object({ items: z.array(LateAddCustomerSchema) });
export type LateAddCustomerList = z.infer<typeof LateAddCustomerListSchema>;

export const PreorderProductSchema = z.object({ atin: z.string(), name: z.string(), price: z.number().int(), sellerName: z.string() });
export type PreorderProduct = z.infer<typeof PreorderProductSchema>;
export const PreorderProductListSchema = z.object({ items: z.array(PreorderProductSchema) });

export const AddressVerificationSchema = z.object({ verified: z.boolean(), placeId: z.string().nullable(), formatted: z.string().nullable() });
export type AddressVerification = z.infer<typeof AddressVerificationSchema>;

export interface LateAddRequest {
  customerId: string;
  addressId?: string;
  newAddress?: { line1: string; zip: string; placeId: string };
  /** Required in substance with `addressId` (see {@link lateAddAddressSnapshot}); recipient details with a new one. */
  address?: LateAddAddressSnapshot;
  phoneConfirmed: true;
  items: { atin: string; qty: number }[];
  payment: LateAddPayment;
  reason: LateAddReason;
  note: string;
}

export const UploadTicketSchema = z.object({
  assetId: z.string(),
  upload: z.object({ method: z.enum(['PUT', 'POST']), url: z.string(), headers: z.record(z.string(), z.string()) })
});
export type PreorderUploadTicket = z.infer<typeof UploadTicketSchema>;

export type PreorderExportKind = 'orders' | 'orders-filtered' | 'picking-list' | 'advertorial-report';

export interface PreorderListQuery {
  status: PreorderListTab;
  q?: string;
  page: number;
}

export interface PreorderOrdersQuery {
  status?: PreorderOrderStatus | '';
  channel?: PreorderChannel | '';
  /** One search over every column the table shows. */
  q?: string;
  /** `<column>,asc|desc`, repeated per sort column. */
  sort?: string[];
  page: number;
  size: number;
}

export interface PreorderAreasQuery {
  country?: string;
  region?: string;
  q?: string;
  page: number;
  /** Rows per page (delivery-runs default 12, at most 100). */
  size?: number;
}

export interface PlacesQuery {
  country?: string;
  region?: string;
  q?: string;
}

/* ── Rules shared by the console and the mock BFF ──────── */

const DAY = 86_400_000;

/** A calendar date (YYYY-MM-DD) at local noon, as the design does. */
export const dateAtNoon = (d: string): number => new Date(`${d.slice(0, 10)}T12:00`).getTime();

/** Derived status: DRAFT, else PAUSED, else UPCOMING < opens ≤ OPEN ≤ closes < CLOSED < deliversOn + 1 day ≤ DELIVERED. */
export function preorderStatus(w: Pick<PreorderWindow, 'draft' | 'active' | 'opensAt' | 'closesAt' | 'deliversOn'>, now: number): PreorderStatus {
  if (w.draft) return 'DRAFT';
  if (!w.active) return 'PAUSED';
  if (now < Date.parse(w.opensAt)) return 'UPCOMING';
  if (now <= Date.parse(w.closesAt)) return 'OPEN';
  if (now < dateAtNoon(w.deliversOn) + DAY) return 'CLOSED';
  return 'DELIVERED';
}

/** Which list tab a status belongs to (delivered windows sit under Closed; paused only under All). */
export const tabOf = (s: PreorderStatus): PreorderListTab | null =>
  s === 'OPEN' ? 'open' : s === 'UPCOMING' ? 'upcoming' : s === 'CLOSED' || s === 'DELIVERED' ? 'closed' : s === 'DRAFT' ? 'draft' : null;

export type PreorderCheckKey = 'name' | 'area' | 'dates' | 'delivery' | 'hero' | 'ribbon' | 'fulfilment';
export interface PreorderCheck {
  key: PreorderCheckKey;
  ok: boolean;
  label: string;
}

export type PreorderCheckInput = Pick<PreorderWindowInput, 'name' | 'targets' | 'opensAt' | 'closesAt' | 'deliversOn' | 'ribbon' | 'door' | 'pickup'>;

/** The seven publish checks, in the design's order. Publish is blocked while any fails. */
export function preorderChecks(d: PreorderCheckInput, hasHero: boolean): PreorderCheck[] {
  const opens = Date.parse(d.opensAt);
  const closes = Date.parse(d.closesAt);
  const delivers = d.deliversOn ? dateAtNoon(d.deliversOn) : Number.NaN;
  return [
    { key: 'name', ok: d.name.trim().length > 0, label: 'Name set' },
    { key: 'area', ok: d.targets.length > 0, label: 'At least one pre-order area chosen' },
    { key: 'dates', ok: closes > opens, label: 'Closing date is after opening date' },
    { key: 'delivery', ok: delivers >= closes - DAY / 2, label: 'Delivery date is on or after closing date' },
    { key: 'hero', ok: hasHero, label: 'Storefront hero uploaded (required)' },
    { key: 'ribbon', ok: d.ribbon.trim().length > 0, label: 'Ribbon text set' },
    { key: 'fulfilment', ok: d.door || d.pickup, label: 'At least one fulfilment option' }
  ];
}

export interface CoverageArea {
  key: string;
  country: string;
  region: string;
  city: string | null;
  on: boolean;
}

export function areaFromKey(key: string, on = true): CoverageArea {
  const [country = '', region = '', city] = key.split('/');
  return { key, country, region, city: city || null, on };
}

/** Whether pre-order is switched on for a place: a city row overrides its region row; a region row covers every city in it. */
export function areaOn(areas: CoverageArea[], country: string, region: string, city?: string | null): boolean {
  const c = city ? city.toLowerCase() : null;
  const exact = c ? areas.find((a) => a.country === country && a.region === region && a.city !== null && a.city.toLowerCase() === c) : undefined;
  if (exact) return exact.on;
  const reg = areas.find((a) => a.country === country && a.region === region && a.city === null);
  return !!reg?.on;
}

/** Whether an area key is the given key or lies under it (US ⊃ US/TX ⊃ US/TX/Lubbock). */
export const keyWithin = (key: string, scope: string): boolean => key === scope || key.startsWith(`${scope}/`);

/** Bulk switch: every area at or under one of the keys gets `on` (a country or region row toggles all its areas). */
export const setAreasOn = (areas: CoverageArea[], keys: string[], on: boolean): CoverageArea[] =>
  areas.map((a) => (keys.some((k) => keyWithin(a.key, k)) ? { ...a, on } : a));

/** Whether a window still reaches shoppers in at least one target (city targets use areaOn; region targets need their own row on). */
/**
 * The design's effective state: master switch AND the window's own switch AND an area on. The window's own switch is
 * read first, so a paused window says "Off" whatever the master switch says; the master switch never rewrites it.
 */
export function switchStateOf(w: { draft: boolean; active: boolean; status: PreorderStatus }, masterOn: boolean, anyAreaOn: boolean): PreorderSwitchState {
  if (w.draft || w.status === 'DRAFT' || w.status === 'DELIVERED') return 'NOT_LIVE';
  if (!w.active) return 'PAUSED';
  if (!masterOn) return 'GLOBAL_OFF';
  if (!anyAreaOn) return 'AREA_OFF';
  return 'ON';
}

/** Rows of one region ("Every city" first, then cities by name). */
export const regionAreas = (areas: CoverageArea[], country: string, region: string): CoverageArea[] =>
  areas
    .filter((a) => a.country === country && a.region === region)
    .sort((a, b) => (a.city ? 1 : 0) - (b.city ? 1 : 0) || String(a.city ?? '').localeCompare(String(b.city ?? '')));

/** Whether a row decides a target's switch: its own row, or for a city its region's "Every city" row. */
export function areaGoverns(areas: CoverageArea[], target: string): boolean {
  const t = areaFromKey(target);
  const same = (a: CoverageArea) => a.key.toLowerCase() === t.key.toLowerCase();
  return areas.some(same) || (!!t.city && areas.some((a) => a.country === t.country && a.region === t.region && !a.city));
}

/** Targets that `after` no longer covers at all (removed, not just switched off). */
export const strandedTargets = (before: CoverageArea[], after: CoverageArea[], liveTargets: string[]): string[] =>
  [...new Set(liveTargets.filter((t) => areaGoverns(before, t) && !areaGoverns(after, t)))];

export const windowAreaOn = (areas: CoverageArea[], targets: string[]): boolean =>
  targets.some((k) => {
    const a = areaFromKey(k);
    return a.city ? areaOn(areas, a.country, a.region, a.city) : areas.some((x) => x.key === k && x.on);
  });

const CURRENCY: Record<string, string> = { US: 'USD', CA: 'CAD', GB: 'GBP', NG: 'NGN', GH: 'GHS', KE: 'KES', ZA: 'ZAR', JM: 'JMD', IE: 'EUR' };
export const currencyOfCountry = (country: string): string => CURRENCY[country] ?? 'USD';
/** A window's currency is its first target's country's. */
export const currencyOfTargets = (targets: string[]): string => currencyOfCountry(areaFromKey(targets[0] ?? 'US').country);

export const DEFAULT_PAYMENT: PreorderPaymentSettings = {
  doorFee: { USD: 799, CAD: 999 },
  pickupFee: { USD: 0, CAD: 0 },
  pickupWindow: '10am–2pm',
  allowedMethods: ['CARD', 'WALLET', 'ACH'],
  lateAdd: { savedCard: true, paymentLink: true, linkExpiryHours: 24 },
  missedDelivery: { offerRefund: true, offerNewDate: true }
};

/** Fees a window charges: its override, else the platform default for its currency (USD default when none is set). */
export function effectiveFees(
  w: Pick<PreorderWindowInput, 'doorFeeOverride' | 'pickupFeeOverride'>,
  payment: PreorderPaymentSettings,
  currency: string
): { door: number; pickup: number } {
  return {
    door: w.doorFeeOverride ?? payment.doorFee[currency] ?? payment.doorFee.USD ?? DEFAULT_PAYMENT.doorFee.USD ?? 0,
    pickup: w.pickupFeeOverride ?? payment.pickupFee[currency] ?? payment.pickupFee.USD ?? 0
  };
}

export interface LateAddDraft {
  customerId: string;
  addressId: string;
  newAddress: { line1: string; zip: string; placeId: string | null } | null;
  phoneConfirmed: boolean;
  items: string[];
  payment: LateAddPayment;
  reason: LateAddReason;
  note: string;
}

export type LateAddProblem = 'customer' | 'address' | 'verify' | 'phone' | 'items' | 'status';

/** What still blocks a late add (empty = it can be saved). Late adds are allowed while OPEN (assisted) or CLOSED. */
export function lateAddProblems(d: LateAddDraft, status: PreorderStatus): LateAddProblem[] {
  const out: LateAddProblem[] = [];
  if (status !== 'OPEN' && status !== 'CLOSED') out.push('status');
  if (!d.customerId) out.push('customer');
  if (d.newAddress) {
    if (!d.newAddress.placeId) out.push('verify');
  } else if (!d.addressId) out.push('address');
  if (!d.phoneConfirmed) out.push('phone');
  if (!d.items.length) out.push('items');
  return out;
}

/* ── SMS copy (wizard › Comms) ─────────────────────────────── */

/** Placeholder notifications replaces with the tracked SMS link (go/services/notifications rules_preorder.go). */
export const SMS_LINK_TOKEN = '{link}';
/** One SMS segment: 160 GSM-7 septets, or 70 characters when the text needs UCS-2 (delivery-runs Gsm7). */
export const SMS_GSM7_LIMIT = 160;
export const SMS_UCS2_LIMIT = 70;
/** Window code used for the length estimate before the server assigns one (CITY-NN, e.g. LBK-7 → 3 letters, dash, 2 digits). */
const ESTIMATE_CODE = 'XXX-00';

const GSM7_BASIC = '@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !"#¤%&\'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà';
const GSM7_EXTENDED = '^{}\\[~]|€\f';

/** GSM-7 septets of a text (extension characters count 2), or null when it must go as UCS-2. */
export function gsm7Septets(text: string): number | null {
  let n = 0;
  for (const c of text) {
    if (GSM7_BASIC.includes(c)) n += 1;
    else if (GSM7_EXTENDED.includes(c)) n += 2;
    else return null;
  }
  return n;
}

/**
 * The tracked SMS link notifications puts in place of `{link}`: `<PUBLIC_WEB_URL>/pre-orders?src=sms&po=<CODE>&cr=announce`
 * (announce is the longest SMS slot; last call and out-for-delivery links are shorter).
 */
export function preorderSmsLink(storefrontUrl: string, code: string | null | undefined): string {
  const base = storefrontUrl.replace(/\/+$/, '');
  return `${base}/pre-orders?src=sms&po=${encodeURIComponent(code || ESTIMATE_CODE)}&cr=announce`;
}

/** What notifications does to SMS text before sending (smsSafeDisplay): typographic characters become GSM-7 ones. */
export function smsSafeDisplay(text: string): string {
  return text.replaceAll(' · ', ' at ').replaceAll('·', '-').replaceAll('–', '-').replaceAll('—', '-').replaceAll('‘', "'").replaceAll('’', "'").replaceAll('“', '"').replaceAll('”', '"').replaceAll('…', '...');
}

export interface SmsPreview {
  /** The message as sent: `{link}` expanded, typography made SMS-safe. */
  text: string;
  /** Septets (GSM-7) or characters (UCS-2) of `text`. */
  length: number;
  limit: number;
  encoding: 'GSM-7' | 'UCS-2';
  fits: boolean;
  /** Length the link adds for each `{link}` (0 without one). */
  linkLength: number;
  usesLink: boolean;
}

/** The SMS a window's copy turns into, counted the way carriers count it, so the 160 limit is honest. */
export function smsPreview(copy: string, storefrontUrl: string, code?: string | null): SmsPreview {
  const link = preorderSmsLink(storefrontUrl, code);
  const usesLink = copy.includes(SMS_LINK_TOKEN);
  const text = smsSafeDisplay(copy.trim().replaceAll(SMS_LINK_TOKEN, link));
  const septets = gsm7Septets(text);
  const length = septets ?? [...text].length;
  const limit = septets === null ? SMS_UCS2_LIMIT : SMS_GSM7_LIMIT;
  return { text, length, limit, encoding: septets === null ? 'UCS-2' : 'GSM-7', fits: length <= limit, linkLength: usesLink ? link.length : 0, usesLink };
}

/** RFC 4180 CSV text (Excel and Sheets open it). */
export function toCsvText(rows: (string | number | null | undefined)[][]): string {
  return rows.map((r) => r.map((v) => { const s = String(v ?? ''); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; }).join(',')).join('\n');
}

/** Saves a blob as a file in the browser (no-op outside one). */
export function triggerDownload(blob: Blob, fileName: string): void {
  if (typeof document === 'undefined' || typeof URL.createObjectURL !== 'function') return;
  const a = document.createElement('a');
  const url = URL.createObjectURL(blob);
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

const fileNameOf = (disposition: string | null, fallback: string): string => {
  const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition ?? '');
  return m?.[1] ? decodeURIComponent(m[1]) : fallback;
};

/* ── Client ────────────────────────────────────────────── */

const enc = encodeURIComponent;
const P = '/v1/staff/preorders';
const ifMatch = (version: number): Record<string, string> => ({ 'If-Match': `"${version}"` });

/** Pre-order routes of the staff BFF (mounted as `staffApi().preorders`). */
export function createStaffPreordersApi(request: Requester, options: ApiClientOptions) {
  const doFetch: FetchLike = options.fetch ?? ((req) => fetch(req));
  const ordersQuery = (q: PreorderOrdersQuery) => ({ status: q.status || undefined, channel: q.channel || undefined, q: q.q || undefined, page: String(q.page), size: String(q.size) });

  const api = {
    list: (q: PreorderListQuery, signal?: AbortSignal): Promise<PreorderList> =>
      request(P, PreorderListSchema, { query: { status: q.status === 'all' ? undefined : q.status, q: q.q || undefined, page: String(q.page) }, signal }),
    get: (id: string, signal?: AbortSignal): Promise<PreorderWindow> => request(`${P}/${enc(id)}`, PreorderWindowSchema, { signal }),
    create: (body: PreorderWindowInput): Promise<PreorderWindow> => request(P, PreorderWindowSchema, { method: 'POST', body }),
    /** Optimistic update: 412 PRECONDITION_FAILED when someone saved in between. */
    update: (id: string, body: PreorderWindowInput, version: number): Promise<PreorderWindow> =>
      request(`${P}/${enc(id)}`, PreorderWindowSchema, { method: 'PUT', body, headers: ifMatch(version) }),
    /** 422 PREORDER_CHECKS_FAILED with the failed checks as field errors. */
    publish: (id: string): Promise<PreorderWindow> => request(`${P}/${enc(id)}/publish`, PreorderWindowSchema, { method: 'POST' }),
    pause: (id: string): Promise<PreorderWindow> => request(`${P}/${enc(id)}/pause`, PreorderWindowSchema, { method: 'POST' }),
    resume: (id: string): Promise<PreorderWindow> => request(`${P}/${enc(id)}/resume`, PreorderWindowSchema, { method: 'POST' }),
    /** The window's own on/off switch (never the master switch). Idempotent; 409 for drafts and delivered windows. */
    setActive: (id: string, active: boolean, version?: number): Promise<PreorderWindow> =>
      request(`${P}/${enc(id)}`, PreorderWindowSchema, { method: 'PATCH', body: { active }, headers: version === undefined ? undefined : ifMatch(version) }),
    /** Switch several windows on or off, each on its own (SWITCHED / UNCHANGED / SKIPPED with the reason). */
    setActiveMany: (ids: string[], active: boolean): Promise<PreorderBulkSwitch> => request(P, PreorderBulkSwitchSchema, { method: 'PATCH', body: { ids, active } }),
    setCreative: (id: string, slot: CreativeSlot, assetId: string): Promise<PreorderWindow> =>
      request(`${P}/${enc(id)}/creatives/${slot}`, PreorderWindowSchema, { method: 'PUT', body: { assetId } }),
    removeCreative: (id: string, slot: CreativeSlot): Promise<PreorderWindow> => request(`${P}/${enc(id)}/creatives/${slot}`, PreorderWindowSchema, { method: 'DELETE' }),
    requestUpload: (body: { fileName: string; contentType: string; size: number; width: number; height: number }): Promise<PreorderUploadTicket> =>
      request(`${P}/uploads`, UploadTicketSchema, { method: 'POST', body }),
    orders: (id: string, q: PreorderOrdersQuery, signal?: AbortSignal): Promise<PreorderOrderPage> =>
      request(`${P}/${enc(id)}/orders`, PreorderOrderPageSchema, { query: tableSearchParams(ordersQuery(q), q.sort), signal }),
    summary: (id: string, signal?: AbortSignal): Promise<PreorderSummary> => request(`${P}/${enc(id)}/summary`, PreorderSummarySchema, { signal }),
    products: (id: string, signal?: AbortSignal): Promise<PreorderProduct[]> =>
      request(`${P}/${enc(id)}/products`, PreorderProductListSchema, { signal }).then((r) => r.items),
    /**
     * Required Idempotency-Key: make it once per dialog ({@link newLateAddKey}) and reuse it on every retry until the
     * order is added, so a retry after a timeout returns the same order instead of charging the saved card again.
     */
    addLateOrder: (id: string, body: LateAddRequest, idempotencyKey: string): Promise<PreorderOrder> =>
      request(`${P}/${enc(id)}/late-orders`, PreorderOrderSchema, { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }),
    /**
     * After a timeout: did the late add go through anyway? The window's newest late add (Agent, late) for this phone
     * number with the same items, placed at or after `since`, or null.
     */
    findLateAdd: async (id: string, phone: string, atins: string[], since: string, signal?: AbortSignal): Promise<PreorderOrder | null> => {
      const page = await request(`${P}/${enc(id)}/orders`, PreorderOrderPageSchema, {
        query: tableSearchParams(ordersQuery({ status: 'LATE_ADD', channel: 'AGENT_LATE', q: phone, page: 0, size: 50 }), undefined),
        signal
      });
      const want = [...new Set(atins)].sort().join('|');
      const same = (o: PreorderOrder) => [...new Set(o.lines.map((l) => l.atin))].sort().join('|') === want;
      return page.items.filter((o) => o.placedAt >= since && same(o)).sort((a, b) => b.placedAt.localeCompare(a.placedAt))[0] ?? null;
    },
    customers: (q: string, windowId: string, signal?: AbortSignal): Promise<LateAddCustomerList> =>
      request('/v1/staff/customers/search', LateAddCustomerListSchema, { query: { q: q || undefined, windowId }, signal }),
    verifyAddress: (body: { line1: string; zip: string; city: string; region: string; country: string }): Promise<AddressVerification> =>
      request(`${P}/address-verify`, AddressVerificationSchema, { method: 'POST', body }),
    getSettings: (signal?: AbortSignal): Promise<PreorderSettings> => request(`${P}/settings`, PreorderSettingsSchema, { signal }),
    updateSettings: (body: PreorderSettingsInput, version: number): Promise<PreorderSettings> =>
      request(`${P}/settings`, PreorderSettingsSchema, { method: 'PUT', body, headers: ifMatch(version) }),
    areas: (q: PreorderAreasQuery, signal?: AbortSignal): Promise<PreorderAreaPage> =>
      request(`${P}/areas`, PreorderAreaPageSchema, { query: { country: q.country, region: q.region, q: q.q || undefined, page: String(q.page), size: q.size ? String(q.size) : undefined }, signal }),
    addArea: (key: string): Promise<PreorderAreaPage> => request(`${P}/areas`, PreorderAreaPageSchema, { method: 'POST', body: { key } }),
    /** Bulk switch every area at or under each key (a country, a region or one area). */
    setAreas: (keys: string[], on: boolean): Promise<PreorderAreaPage> => request(`${P}/areas`, PreorderAreaPageSchema, { method: 'PATCH', body: { keys, on } }),
    removeArea: (key: string): Promise<PreorderAreaPage> => request(`${P}/areas/${enc(key)}`, PreorderAreaPageSchema, { method: 'DELETE' }),
    region: (country: string, region: string, signal?: AbortSignal): Promise<PreorderRegion> =>
      request(`${P}/areas/regions/${enc(country)}/${enc(region)}`, PreorderRegionSchema, { signal }),
    /** Replaces the region's cities in place; 412 when anyone changed the region since `version`, 409 area-in-use. */
    updateRegion: (country: string, region: string, body: PreorderRegionInput, version: number): Promise<PreorderRegion> =>
      request(`${P}/areas/regions/${enc(country)}/${enc(region)}`, PreorderRegionSchema, { method: 'PUT', body, headers: ifMatch(version) }),
    places: (q: PlacesQuery, signal?: AbortSignal): Promise<PlaceList> =>
      request(`${P}/places/search`, PlaceListSchema, { query: { country: q.country, region: q.region, q: q.q || undefined }, signal }),
    /** URL of a CSV export (the BFF streams it; `preorders:export`). */
    exportUrl: (id: string, kind: PreorderExportKind, q?: PreorderOrdersQuery): string =>
      absolute(joinUrl(options.baseUrl, `${P}/${enc(id)}/exports/${kind}.csv`, kind === 'orders-filtered' && q ? ordersQuery(q) : undefined)),
    /** Fetches a CSV export and saves it in the browser. Returns the file name and row count (lines − header). */
    downloadCsv: async (id: string, kind: PreorderExportKind, q?: PreorderOrdersQuery): Promise<{ fileName: string; rows: number }> => {
      const res = await doFetch(new Request(api.exportUrl(id, kind, q), { headers: { Accept: 'text/csv', ...options.headers }, credentials: 'include' }));
      if (!res.ok) throw new ApiError(await problemFrom(res));
      const text = await res.text();
      const fileName = fileNameOf(res.headers.get('content-disposition'), `${id}-${kind}.csv`);
      triggerDownload(new Blob([text], { type: 'text/csv;charset=utf-8' }), fileName);
      return { fileName, rows: Math.max(0, text.split('\n').filter(Boolean).length - 1) };
    },
    /** Media upload (signed PUT), then attaches the asset to the slot (delivery-runs verifies it with media-service). */
    uploadCreative: async (id: string, slot: CreativeSlot, file: Blob & { name?: string }, dims: { width: number; height: number }): Promise<PreorderWindow> => {
      const ticket = await api.requestUpload({ fileName: file.name ?? `${slot.toLowerCase()}.jpg`, contentType: file.type || 'image/jpeg', size: file.size, ...dims });
      const res = await doFetch(new Request(absolute(ticket.upload.url), { method: ticket.upload.method, headers: ticket.upload.headers, body: file }));
      if (!res.ok) throw new ApiError(await problemFrom(res));
      return api.setCreative(id, slot, ticket.assetId);
    }
  };
  return api;
}

export type StaffPreordersApi = ReturnType<typeof createStaffPreordersApi>;

/* ── Query keys & options ──────────────────────────────── */

/** Everything hangs off ['staff', 'preorders'], so a mutation can refresh the family (list counts, detail, orders). */
export const staffPreorderKeys = {
  all: ['staff', 'preorders'] as const,
  lists: () => [...staffPreorderKeys.all, 'list'] as const,
  list: (q: PreorderListQuery) => [...staffPreorderKeys.lists(), q] as const,
  window: (id: string) => [...staffPreorderKeys.all, 'window', id] as const,
  orders: (id: string, q: PreorderOrdersQuery) => [...staffPreorderKeys.window(id), 'orders', q] as const,
  summary: (id: string) => [...staffPreorderKeys.window(id), 'summary'] as const,
  products: (id: string) => [...staffPreorderKeys.window(id), 'products'] as const,
  customers: (id: string, q: string) => [...staffPreorderKeys.window(id), 'customers', q] as const,
  settings: () => [...staffPreorderKeys.all, 'settings'] as const,
  areas: () => [...staffPreorderKeys.all, 'areas'] as const,
  areaPage: (q: PreorderAreasQuery) => [...staffPreorderKeys.areas(), q] as const,
  region: (country: string, region: string) => [...staffPreorderKeys.areas(), 'region', country, region] as const,
  places: (q: PlacesQuery) => [...staffPreorderKeys.all, 'places', q] as const
};

export const staffPreorderQueries = {
  list: (api: StaffPreordersApi, q: PreorderListQuery) =>
    queryOptions({ queryKey: staffPreorderKeys.list(q), queryFn: ({ signal }) => api.list(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  window: (api: StaffPreordersApi, id: string) => queryOptions({ queryKey: staffPreorderKeys.window(id), queryFn: ({ signal }) => api.get(id, signal), staleTime: 10_000 }),
  orders: (api: StaffPreordersApi, id: string, q: PreorderOrdersQuery) =>
    queryOptions({ queryKey: staffPreorderKeys.orders(id, q), queryFn: ({ signal }) => api.orders(id, q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  summary: (api: StaffPreordersApi, id: string) => queryOptions({ queryKey: staffPreorderKeys.summary(id), queryFn: ({ signal }) => api.summary(id, signal), staleTime: 15_000 }),
  products: (api: StaffPreordersApi, id: string) => queryOptions({ queryKey: staffPreorderKeys.products(id), queryFn: ({ signal }) => api.products(id, signal), staleTime: 60_000 }),
  customers: (api: StaffPreordersApi, id: string, q: string) =>
    queryOptions({ queryKey: staffPreorderKeys.customers(id, q), queryFn: ({ signal }) => api.customers(q, id, signal), placeholderData: keepPreviousData, staleTime: 30_000 }),
  settings: (api: StaffPreordersApi) => queryOptions({ queryKey: staffPreorderKeys.settings(), queryFn: ({ signal }) => api.getSettings(signal), staleTime: 15_000 }),
  areas: (api: StaffPreordersApi, q: PreorderAreasQuery) =>
    queryOptions({ queryKey: staffPreorderKeys.areaPage(q), queryFn: ({ signal }) => api.areas(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  region: (api: StaffPreordersApi, country: string, region: string) =>
    queryOptions({ queryKey: staffPreorderKeys.region(country, region), queryFn: ({ signal }) => api.region(country, region, signal), staleTime: 5_000 }),
  places: (api: StaffPreordersApi, q: PlacesQuery) =>
    queryOptions({ queryKey: staffPreorderKeys.places(q), queryFn: ({ signal }) => api.places(q, signal), staleTime: 5 * 60_000 })
};
