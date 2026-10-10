import { z } from 'zod';

/* Zod schemas for contracts/openapi/gateway-bff-seller.yaml (Seller Center & Provider Hub). Every response is
   validated at runtime; test/seller-contract.test.ts keeps them assignable to the generated contract types. */

const nullableString = z.string().nullable();

/* ── Shell ─────────────────────────────────────────────── */

export const OrgKindSchema = z.enum(['SELLER', 'PROVIDER']);
export type OrgKind = z.infer<typeof OrgKindSchema>;
export const OrgStatusSchema = z.enum(['DRAFT', 'PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED', 'APPROVED', 'REJECTED', 'SUSPENDED']);
export type OrgStatus = z.infer<typeof OrgStatusSchema>;
export const PortalSchema = z.enum(['SELLER_CENTER', 'PROVIDER_HUB']);
export type Portal = z.infer<typeof PortalSchema>;
export const AccessLevelSchema = z.enum(['VIEW', 'EDIT', 'APPROVE', 'FULL']);
export type AccessLevel = z.infer<typeof AccessLevelSchema>;
export const ModuleKeySchema = z.enum([
  'onboarding', 'home', 'orders', 'listings', 'inventory', 'storefront', 'calendar', 'requests', 'hours', 'services', 'policy',
  'public-page', 'earnings', 'payouts', 'fees', 'billing', 'account-health', 'team'
]);
export type ModuleKey = z.infer<typeof ModuleKeySchema>;

export const SellerUserSchema = z.object({ id: z.string(), firstName: z.string(), lastName: z.string(), email: z.string(), initials: z.string() });
export type SellerUser = z.infer<typeof SellerUserSchema>;

export const OrgSummarySchema = z.object({
  id: z.string(),
  kind: OrgKindSchema,
  name: z.string(),
  status: OrgStatusSchema,
  approved: z.boolean(),
  roleKey: z.string(),
  roleLabel: z.string()
});
export type OrgSummary = z.infer<typeof OrgSummarySchema>;

export const NavModuleSchema = z.object({ key: ModuleKeySchema, label: z.string(), level: AccessLevelSchema, badge: z.number().int().nullable() });
export type NavModule = z.infer<typeof NavModuleSchema>;
export const NavGroupSchema = z.object({ key: z.string(), label: z.string(), modules: z.array(NavModuleSchema) });
export type NavGroup = z.infer<typeof NavGroupSchema>;

export const SellerSessionSchema = z.object({
  user: SellerUserSchema,
  orgs: z.array(OrgSummarySchema),
  activeOrg: OrgSummarySchema.nullable(),
  portal: PortalSchema.nullable(),
  approved: z.boolean(),
  navGroups: z.array(NavGroupSchema),
  landing: ModuleKeySchema,
  accessNote: z.string()
});
export type SellerSession = z.infer<typeof SellerSessionSchema>;

export const OnboardingStatusSchema = z.object({
  orgId: z.string(),
  kind: OrgKindSchema,
  status: z.string(),
  reference: nullableString,
  steps: z.array(z.object({ key: z.string(), label: z.string(), state: z.enum(['DONE', 'TODO', 'ACTION', 'WAITING', 'BLOCKED']) })),
  requestedItems: z.array(z.string()),
  canResume: z.boolean()
});
export type OnboardingStatus = z.infer<typeof OnboardingStatusSchema>;

export const ComplianceStatusSchema = z.enum(['COMPLIANT', 'ACTION_NEEDED', 'RESTRICTED_SOON', 'RESTRICTED', 'VERIFYING']);
export const ComplianceSummarySchema = z.object({
  status: ComplianceStatusSchema,
  showBanner: z.boolean(),
  tone: z.enum(['none', 'info', 'warning', 'danger']),
  title: z.string(),
  message: z.string(),
  openItems: z.number().int().min(0),
  deadline: nullableString,
  payoutsPaused: z.boolean(),
  chargesEnabled: z.boolean(),
  payoutsEnabled: z.boolean(),
  listingsHidden: z.boolean(),
  items: z.array(z.object({ requirement: z.string(), label: z.string(), bucket: z.string() }))
});
export type ComplianceSummary = z.infer<typeof ComplianceSummarySchema>;

/* ── Dashboard ─────────────────────────────────────────── */

export const ChartToneSchema = z.enum(['ink', 'gold', 'green', 'blue', 'red', 'grey']);
export type ChartTone = z.infer<typeof ChartToneSchema>;
export const ValueFormatSchema = z.enum(['money', 'count']);
export type ValueFormat = z.infer<typeof ValueFormatSchema>;
export const ChartSpecSchema = z.object({
  id: z.string(),
  title: z.string(),
  kind: z.enum(['trend', 'hbar', 'donut', 'stack']),
  categoryLabel: z.string(),
  valueFormat: ValueFormatSchema,
  series: z.array(z.object({ key: z.string(), label: z.string(), tone: ChartToneSchema, format: ValueFormatSchema.optional() })),
  rows: z.array(z.object({ category: z.string(), values: z.record(z.string(), z.number()) })),
  available: z.boolean()
});
export type ChartSpec = z.infer<typeof ChartSpecSchema>;
export const KpiSchema = z.object({ label: z.string(), value: z.string(), detail: z.string(), tone: z.enum(['positive', 'attention', 'neutral']) });
export type Kpi = z.infer<typeof KpiSchema>;
export const TodoItemSchema = z.object({ label: z.string(), module: ModuleKeySchema, tone: z.enum(['info', 'warning', 'success', 'danger']) });
export type TodoItem = z.infer<typeof TodoItemSchema>;
export const DashboardSchema = z.object({
  portal: PortalSchema,
  rangeDays: z.number().int(),
  kpis: z.array(KpiSchema),
  charts: z.array(ChartSpecSchema),
  todo: z.array(TodoItemSchema)
});
export type Dashboard = z.infer<typeof DashboardSchema>;

/* ── Orders ────────────────────────────────────────────── */

/** SELLER = I ship it; AFROTRANSACT = Fulfilled by AfroTransact (FBA); AFROTRANSACT_DELIVERY = AfroTransact picks up from my store (S255). */
export const FulfilmentKindSchema = z.enum(['SELLER', 'AFROTRANSACT', 'AFROTRANSACT_DELIVERY']);
export type FulfilmentKind = z.infer<typeof FulfilmentKindSchema>;
export const ShipmentStatusSchema = z.enum([
  'PENDING_PAYMENT', 'PLACED', 'CONFIRMED', 'PACKED', 'READY', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERY_FAILED', 'DELIVERED', 'RETURNED', 'CANCELLED'
]);
export type ShipmentStatus = z.infer<typeof ShipmentStatusSchema>;
/** The only statuses a seller may set. */
export const SellerStatusActionSchema = z.enum(['CONFIRMED', 'PACKED', 'READY']);
export type SellerStatusAction = z.infer<typeof SellerStatusActionSchema>;

export const SellerOrderRowSchema = z.object({
  orderNumber: z.string(),
  shipmentId: z.string(),
  placedAt: z.string(),
  buyerName: z.string(),
  shipTo: z.string(),
  itemsSummary: z.string(),
  itemCount: z.number().int(),
  totalMinor: z.number().int(),
  currency: z.string(),
  fulfilment: FulfilmentKindSchema,
  status: ShipmentStatusSchema,
  statusLabel: z.string(),
  carrier: nullableString,
  trackingNumber: nullableString,
  nextActions: z.array(SellerStatusActionSchema),
  canBuyLabel: z.boolean()
});
export type SellerOrderRow = z.infer<typeof SellerOrderRowSchema>;
export const SellerOrderPageSchema = z.object({ items: z.array(SellerOrderRowSchema), nextCursor: nullableString, total: z.number().int() });
export type SellerOrderPage = z.infer<typeof SellerOrderPageSchema>;

export const SellerAddressSchema = z.object({
  name: z.string(),
  phone: nullableString,
  line1: z.string(),
  line2: nullableString,
  city: z.string(),
  state: z.string(),
  postalCode: z.string(),
  country: z.string()
});
export type SellerAddress = z.infer<typeof SellerAddressSchema>;

export const SellerShipmentSchema = z.object({
  shipmentId: z.string(),
  label: z.string(),
  status: ShipmentStatusSchema,
  statusLabel: z.string(),
  fulfilment: FulfilmentKindSchema,
  perishable: z.boolean(),
  lines: z.array(
    z.object({ title: z.string(), quantity: z.number().int(), unitPriceMinor: z.number().int(), itemsMinor: z.number().int(), variantAtin: z.string(), imageUrl: nullableString })
  ),
  itemsMinor: z.number().int(),
  shippingMinor: z.number().int(),
  totalMinor: z.number().int(),
  carrier: nullableString,
  trackingNumber: nullableString,
  nextActions: z.array(SellerStatusActionSchema),
  canBuyLabel: z.boolean(),
  timeline: z.array(z.object({ status: z.string(), at: z.string(), actor: z.string() })),
  /** AfroTransact Delivery from the seller's store (S255): the seller delivery fee is deducted from the payout. */
  localDelivery: z
    .object({
      pickupLocationId: nullableString,
      roadMiles: z.number().nullable(),
      durationMinutes: z.number().nullable(),
      customerChargeMinor: z.number().int(),
      sellerFeeMinor: z.number().int()
    })
    .nullable()
});
export type SellerShipment = z.infer<typeof SellerShipmentSchema>;
export const OrderEarningsSchema = z.object({
  itemsMinor: z.number().int(),
  shippingMinor: z.number().int(),
  commissionMinor: z.number().int(),
  fbaFeesMinor: z.number().int(),
  deliveryFeesMinor: z.number().int(),
  stripeFeeMinor: z.number().int(),
  netMinor: z.number().int()
});
export type OrderEarnings = z.infer<typeof OrderEarningsSchema>;

export const SellerOrderDetailSchema = z.object({
  orderNumber: z.string(),
  status: z.string(),
  placedAt: z.string(),
  currency: z.string(),
  buyerName: z.string(),
  shippingAddress: SellerAddressSchema.nullable(),
  shipments: z.array(SellerShipmentSchema),
  totalMinor: z.number().int(),
  earnings: OrderEarningsSchema.nullable()
});
export type SellerOrderDetail = z.infer<typeof SellerOrderDetailSchema>;

export const ParcelSchema = z.object({
  weightLb: z.number().positive('Enter the weight').max(150, 'At most 150 lb'),
  lengthIn: z.number().min(0).optional(),
  widthIn: z.number().min(0).optional(),
  heightIn: z.number().min(0).optional()
});
export type Parcel = z.infer<typeof ParcelSchema>;

export const CarrierSchema = z.enum(['usps', 'ups', 'fedex', 'dhl']);
export type Carrier = z.infer<typeof CarrierSchema>;
export const RateQuoteSchema = z.object({
  carrier: CarrierSchema,
  service: z.string(),
  serviceName: z.string(),
  amountMinor: z.number().int(),
  currency: z.string(),
  transitDays: z.number().int().nullable(),
  estimatedDelivery: nullableString
});
export type RateQuote = z.infer<typeof RateQuoteSchema>;
export const RateQuotesSchema = z.object({ quotes: z.array(RateQuoteSchema), partial: z.boolean() });
export type RateQuotes = z.infer<typeof RateQuotesSchema>;
export const LabelPurchaseSchema = z.object({
  carrier: CarrierSchema,
  service: z.string(),
  trackingNumber: z.string(),
  costMinor: z.number().int(),
  currency: z.string(),
  documentUrl: nullableString,
  order: SellerOrderDetailSchema
});
export type LabelPurchase = z.infer<typeof LabelPurchaseSchema>;

/** Ship-from form (label buying). */
export const ShipFromFormSchema = z.object({
  name: z.string().trim().min(2, 'Enter the sender name'),
  phone: z.string().trim(),
  line1: z.string().trim().min(3, 'Enter the street address'),
  line2: z.string().trim(),
  city: z.string().trim().min(2, 'Enter the city'),
  state: z.string().trim().regex(/^[A-Z]{2}$/, 'Use the 2-letter state'),
  postalCode: z.string().trim().regex(/^\d{5}(-\d{4})?$/, 'Enter a ZIP code')
});
export type ShipFromForm = z.infer<typeof ShipFromFormSchema>;

export function toSellerAddress(f: ShipFromForm): SellerAddress {
  return { name: f.name, phone: f.phone || null, line1: f.line1, line2: f.line2 || null, city: f.city, state: f.state, postalCode: f.postalCode, country: 'US' };
}

export const PICKUP_DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const;
export const PickupDaySchema = z.enum(PICKUP_DAYS);
export type PickupDay = z.infer<typeof PickupDaySchema>;
export const PickupWindowSchema = z.object({ day: PickupDaySchema, opens: z.string(), closes: z.string() });
export type PickupWindow = z.infer<typeof PickupWindowSchema>;
export interface AfrotransactPickupRequest {
  enabled: boolean;
  hours: PickupWindow[];
  version?: number | null;
}

/** A saved ship-from address of the organisation (seller service; the label form preselects the default). */
export const ShipFromAddressSchema = z.object({
  id: z.string(),
  label: z.string(),
  name: z.string(),
  company: z.string(),
  phone: z.string(),
  line1: z.string(),
  line2: z.string(),
  city: z.string(),
  state: z.string(),
  postalCode: z.string(),
  country: z.string(),
  isDefault: z.boolean(),
  version: z.number().int(),
  /** Where geo-service located the address (null when geocoding failed or geo was down). */
  position: z
    .object({ lat: z.number(), lng: z.number(), precision: z.enum(['ADDRESS', 'ZIP', 'POINT']), geocodedAt: nullableString })
    .nullable(),
  /** "AfroTransact picks up from my store" (S255). */
  afrotransactPickup: z.object({
    enabled: z.boolean(),
    hours: z.array(PickupWindowSchema),
    zoneName: nullableString,
    enabledAt: nullableString
  })
});
export type ShipFromAddress = z.infer<typeof ShipFromAddressSchema>;

const PICKUP_HHMM = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;

/** The seller service's pickup-hours rules (the BFF's AfrotransactPickupRequest.Validate): keys hours / hours.<i>.<field>. */
export function validatePickupHours(enabled: boolean, hours: PickupWindow[]): Record<string, string> {
  const errs: Record<string, string> = {};
  if (enabled && hours.length === 0) errs.hours = 'Add at least one pickup window';
  if (hours.length > 14) errs.hours = 'At most 14 pickup windows';
  const byDay = new Map<string, { opens: string; closes: string }[]>();
  hours.forEach((h, i) => {
    const k = `hours.${i}`;
    if (!PICKUP_HHMM.test(h.opens)) errs[`${k}.opens`] = 'Use HH:MM (24-hour)';
    else if (!PICKUP_HHMM.test(h.closes)) errs[`${k}.closes`] = 'Use HH:MM (24-hour)';
    else if (h.opens >= h.closes) errs[`${k}.closes`] = 'Closes after it opens';
    else {
      const same = byDay.get(h.day) ?? [];
      if (same.some((o) => h.opens < o.closes && o.opens < h.closes)) errs[`${k}.opens`] = `Overlaps another window on ${h.day.toLowerCase()}`;
      byDay.set(h.day, [...same, h]);
    }
  });
  return errs;
}

/* ── Offers: the fulfilment choice of a listing (S255) ── */
export const SellerOfferSchema = z.object({
  offerId: z.string(),
  atin: z.string(),
  productId: z.string(),
  sellerSku: z.string(),
  condition: z.string(),
  priceMinor: z.number().int(),
  currency: z.string(),
  fulfilment: FulfilmentKindSchema,
  quantity: z.number().int(),
  availableQty: z.number().int(),
  shipFromLocationId: nullableString,
  shipsFrom: nullableString,
  status: z.enum(['ACTIVE', 'PAUSED', 'WITHDRAWN']),
  version: z.number().int()
});
export type SellerOffer = z.infer<typeof SellerOfferSchema>;
export const SellerOfferListSchema = z.object({ items: z.array(SellerOfferSchema) });
export type SellerOfferList = z.infer<typeof SellerOfferListSchema>;
export interface OfferFulfilmentRequest {
  fulfilment: FulfilmentKind;
  shipFromLocationId?: string | null;
}
export const ShipFromListSchema = z.object({ items: z.array(ShipFromAddressSchema) });
export type ShipFromInput = ShipFromForm & { label?: string; company?: string; country?: string; makeDefault?: boolean; version?: number | null };

export function shipFromToForm(a: ShipFromAddress): ShipFromForm {
  return { name: a.name, phone: a.phone, line1: a.line1, line2: a.line2, city: a.city, state: a.state, postalCode: a.postalCode };
}

/* ── Listings, builder, stock ──────────────────────────── */

export const ListingStatusSchema = z.enum(['IN_REVIEW', 'PENDING_CATEGORY_APPROVAL', 'LIVE', 'REJECTED', 'PAUSED', 'TAKEN_DOWN']);
export type ListingStatus = z.infer<typeof ListingStatusSchema>;
export const ListingRowSchema = z.object({
  productId: z.string(),
  atin: nullableString,
  title: z.string(),
  sku: z.string(),
  variantCount: z.number().int(),
  priceMinor: z.number().int(),
  currency: z.string(),
  stock: z.number().int(),
  fulfilment: FulfilmentKindSchema,
  status: ListingStatusSchema,
  statusLabel: z.string(),
  moderationNote: nullableString,
  imageUrl: nullableString,
  updatedAt: z.string()
});
export type ListingRow = z.infer<typeof ListingRowSchema>;
export const ListingPageSchema = z.object({ items: z.array(ListingRowSchema), total: z.number().int(), page: z.number().int(), size: z.number().int() });
export type ListingPage = z.infer<typeof ListingPageSchema>;

export interface CategoryNode {
  id: string;
  name: string;
  level: string;
  productType: string | null;
  path: string;
  children: CategoryNode[];
}
export const CategoryNodeSchema: z.ZodType<CategoryNode> = z.lazy(() =>
  z.object({ id: z.string(), name: z.string(), level: z.string(), productType: nullableString, path: z.string(), children: z.array(CategoryNodeSchema) })
);

export const ProductTypeSchemaSchema = z.object({
  code: z.string(),
  label: z.string(),
  identifierRule: z.enum(['gtin', 'isbn', 'none']),
  attributes: z.array(z.object({ key: z.string(), label: z.string(), options: z.array(z.string()), required: z.boolean(), multiline: z.boolean() })),
  themes: z.array(z.object({ name: z.string(), dimensions: z.array(z.object({ name: z.string(), options: z.array(z.string()) })) }))
});
export type ProductTypeSchema = z.infer<typeof ProductTypeSchemaSchema>;

export const GtinCheckSchema = z.object({ digits: z.string(), valid: z.boolean(), suggestion: nullableString, existingAtin: nullableString, message: z.string() });
export type GtinCheck = z.infer<typeof GtinCheckSchema>;

export const CategoryRequestInputSchema = z.object({
  parentPath: z.string().trim().min(3, 'Required').max(200),
  name: z.string().trim().min(3, 'Required').max(80),
  exampleProduct: z.string().trim().min(3, 'Required').max(200),
  detailsNeeded: z.string().trim().min(3, 'Required').max(1000)
});
export type CategoryRequestInput = z.infer<typeof CategoryRequestInputSchema>;
export const CategoryRequestSchema = z.object({
  id: z.string(),
  reference: z.string(),
  path: z.string(),
  status: z.enum(['PENDING_REVIEW', 'APPROVED', 'REJECTED']),
  decisionNote: nullableString,
  createdAt: z.string()
});
export type CategoryRequest = z.infer<typeof CategoryRequestSchema>;

export const ProductImageInputSchema = z.object({
  url: z.string().min(1),
  width: z.number().int().min(0).optional(),
  height: z.number().int().min(0).optional(),
  bytes: z.number().int().min(0).optional(),
  contentType: z.string()
});
export type ProductImageInput = z.infer<typeof ProductImageInputSchema>;
export const VariantInputSchema = z.object({
  options: z.record(z.string(), z.string()),
  sellerSku: z.string().min(1).max(64),
  gtin: z.string().optional(),
  gtinExempt: z.boolean().optional(),
  priceMinor: z.number().int().min(1),
  quantity: z.number().int().min(0)
});
export type VariantInput = z.infer<typeof VariantInputSchema>;
export const ProductDraftSchema = z.object({
  categoryId: z.string().optional(),
  categoryRequestId: z.string().optional(),
  title: z.string().min(10).max(200),
  attributes: z.record(z.string(), z.string()),
  images: z.array(ProductImageInputSchema).min(1).max(10),
  variationTheme: z.string().min(1),
  variants: z.array(VariantInputSchema).min(1).max(200)
});
export type ProductDraft = z.infer<typeof ProductDraftSchema>;

export const UploadRequestSchema = z.object({
  purpose: z.enum(['product-image', 'storefront']),
  fileName: z.string().min(1).max(200),
  contentType: z.enum(['image/jpeg', 'image/png', 'image/webp']),
  size: z.number().int().min(1).max(8 * 1024 * 1024)
});
export type UploadRequest = z.infer<typeof UploadRequestSchema>;
export const UploadTicketSchema = z.object({
  assetId: z.string(),
  method: z.string(),
  url: z.string(),
  headers: z.record(z.string(), z.string()),
  publicUrl: nullableString,
  expiresAt: nullableString
});
export type UploadTicket = z.infer<typeof UploadTicketSchema>;
export const AssetSchema = z.object({
  id: z.string(),
  url: z.string(),
  width: z.number().int().nullable(),
  height: z.number().int().nullable(),
  bytes: z.number().int().nullable(),
  contentType: z.string(),
  status: z.enum(['PENDING', 'READY', 'FLAGGED', 'REJECTED'])
});
export type Asset = z.infer<typeof AssetSchema>;

export const StockPositionSchema = z.object({
  sku: z.string(),
  nodeId: z.string(),
  onHand: z.number().int(),
  reserved: z.number().int(),
  available: z.number().int(),
  inbound: z.number().int(),
  lowStock: z.boolean()
});
export type StockPosition = z.infer<typeof StockPositionSchema>;
export const InventoryViewSchema = z.object({
  positions: z.array(StockPositionSchema),
  totals: z.object({ available: z.number().int(), reserved: z.number().int(), inbound: z.number().int(), lowStockSkus: z.number().int() })
});
export type InventoryView = z.infer<typeof InventoryViewSchema>;
export const StockReasonSchema = z.enum(['STOCK_TAKE', 'DAMAGED', 'FOUND', 'RETURNED', 'OTHER']);
export type StockReason = z.infer<typeof StockReasonSchema>;
export const StockAdjustmentRequestSchema = z.object({ sku: z.string().min(1), quantity: z.number().int().min(0).max(100000), reason: StockReasonSchema });
export type StockAdjustmentRequest = z.infer<typeof StockAdjustmentRequestSchema>;
export const StockAdjustmentSchema = z.object({ sku: z.string(), quantity: z.number().int() });
export type StockAdjustment = z.infer<typeof StockAdjustmentSchema>;

/* ── Money ─────────────────────────────────────────────── */

export const PayoutBalanceSchema = z.object({
  currency: z.string(),
  availableMinor: z.number().int(),
  onHoldMinor: z.number().int(),
  requestedOrInTransitMinor: z.number().int(),
  paidMinor: z.number().int(),
  providerFeesMinor: z.number().int(),
  minimumPayoutMinor: z.number().int(),
  canRequestToday: z.boolean(),
  payoutsPaused: z.boolean(),
  payoutsPausedReason: z.enum(['AUTO_PAST_DUE', 'MANUAL', 'DEAUTHORIZED', 'CHARGES_DISABLED']).nullable(),
  payoutsPausedMessage: nullableString,
  holds: z.array(z.object({ orderId: z.string(), amountMinor: z.number().int(), releaseAt: nullableString }))
});
export type PayoutBalance = z.infer<typeof PayoutBalanceSchema>;
export const PayoutSchema = z.object({
  id: z.string(),
  amountMinor: z.number().int(),
  currency: z.string(),
  status: z.enum(['REQUESTED', 'HELD', 'IN_TRANSIT', 'PAID', 'FAILED']),
  requestedAt: z.string(),
  holdReason: nullableString,
  failureMessage: nullableString
});
export type Payout = z.infer<typeof PayoutSchema>;
export const PayoutsViewSchema = z.object({ balance: PayoutBalanceSchema, payouts: z.array(PayoutSchema), historyAvailable: z.boolean() });
export type PayoutsView = z.infer<typeof PayoutsViewSchema>;

/* ── Fees (read-only; rates in basis points, 1250 = 12.50 %) ── */

export const SellerFeeCategorySchema = z.object({
  category: z.string(),
  label: z.string(),
  commissionBps: z.number().int(),
  source: z.enum(['OVERRIDE', 'CATEGORY', 'DEFAULT', 'RETAIL'])
});
export type SellerFeeCategory = z.infer<typeof SellerFeeCategorySchema>;
export const SellerFeeVersionSchema = z.object({
  version: z.number().int(),
  effectiveFrom: z.string(),
  defaultCommissionBps: z.number().int(),
  categories: z.array(SellerFeeCategorySchema),
  cardPctBps: z.number().int(),
  cardFixedMinor: z.number().int(),
  fbaPerUnitMinor: z.number().int(),
  reverseCommissionOnRefund: z.boolean(),
  /** The seller delivery fee of AfroTransact Delivery from your store (S255); null when the version has no delivery pricing. */
  deliveryFee: z
    .object({
      enabled: z.boolean(),
      maxRadiusMiles: z.number(),
      source: z.enum(['DEFAULT', 'OVERRIDE', 'RETAIL']),
      tariff: z
        .object({
          mode: z.enum(['FLAT', 'DISTANCE']),
          flatPerPackageMinor: z.number().int().nullable(),
          baseMinor: z.number().int().nullable(),
          perMileMinor: z.number().int().nullable(),
          bands: z.array(z.object({ fromMiles: z.number(), toMiles: z.number(), feeMinor: z.number().int() })),
          minMinor: z.number().int().nullable(),
          maxMinor: z.number().int().nullable()
        })
        .nullable(),
      summary: z.string()
    })
    .nullable()
});
export type SellerFeeVersion = z.infer<typeof SellerFeeVersionSchema>;
export const SellerFeeChangeSchema = z.object({ field: z.string(), label: z.string(), from: z.string(), to: z.string() });
export type SellerFeeChange = z.infer<typeof SellerFeeChangeSchema>;
export const SellerFeesViewSchema = z.object({
  currency: z.string(),
  retail: z.boolean(),
  note: z.string().nullable(),
  current: SellerFeeVersionSchema,
  upcoming: SellerFeeVersionSchema.nullable(),
  changes: z.array(SellerFeeChangeSchema)
});
export type SellerFeesView = z.infer<typeof SellerFeesViewSchema>;

export const CadenceSchema = z.enum(['MONTHLY', 'QUARTERLY', 'ANNUAL']);
export type Cadence = z.infer<typeof CadenceSchema>;
export const PlanPriceSchema = z.object({
  cadence: CadenceSchema,
  months: z.number().int(),
  totalMinor: z.number().int(),
  perMonthMinor: z.number().int(),
  savingMinor: z.number().int(),
  perMonthLabel: z.string(),
  billedLabel: z.string(),
  savingLabel: z.string()
});
export type PlanPrice = z.infer<typeof PlanPriceSchema>;
export const PlanSchema = z.object({
  code: z.string(),
  name: z.string(),
  tagline: z.string(),
  monthlyMinor: z.number().int(),
  trialDays: z.number().int(),
  features: z.array(z.string()),
  prices: z.array(PlanPriceSchema),
  current: z.boolean()
});
export type Plan = z.infer<typeof PlanSchema>;
export const BillingPaymentMethodSchema = z.object({
  id: z.string(),
  kind: z.enum(['card', 'bank']),
  brand: nullableString,
  last4: z.string(),
  expMonth: z.number().int().nullable(),
  expYear: z.number().int().nullable(),
  bankName: nullableString,
  accountType: nullableString,
  verified: z.boolean(),
  receivesPayouts: z.boolean(),
  isDefault: z.boolean(),
  label: z.string()
});
export type BillingPaymentMethod = z.infer<typeof BillingPaymentMethodSchema>;
export const InvoiceSchema = z.object({
  id: z.string(),
  number: z.string(),
  periodStart: nullableString,
  periodEnd: nullableString,
  amountDueMinor: z.number().int(),
  amountPaidMinor: z.number().int(),
  currency: z.string(),
  status: z.string(),
  pdfUrl: nullableString,
  hostedUrl: nullableString,
  createdAt: z.string()
});
export type Invoice = z.infer<typeof InvoiceSchema>;
export const SubscriptionSchema = z.object({
  planCode: z.string(),
  planName: z.string(),
  cadence: CadenceSchema,
  status: z.string(),
  priceLabel: z.string(),
  billedLabel: z.string(),
  trialEndsAt: nullableString,
  currentPeriodEnd: nullableString,
  nextChargeAt: nullableString,
  nextChargeMinor: z.number().int().nullable(),
  nextChargeLabel: z.string(),
  cancelAtPeriodEnd: z.boolean(),
  pendingChange: z.object({ planCode: z.string(), planName: z.string(), cadence: CadenceSchema, effectiveAt: z.string() }).nullable(),
  dunning: nullableString
});
export type Subscription = z.infer<typeof SubscriptionSchema>;
export const BillingViewSchema = z.object({
  subscription: SubscriptionSchema,
  plans: z.array(PlanSchema),
  paymentMethods: z.array(BillingPaymentMethodSchema),
  invoices: z.array(InvoiceSchema),
  canManage: z.boolean()
});
export type BillingView = z.infer<typeof BillingViewSchema>;
export const ChangePlanResultSchema = z.object({ applied: z.enum(['IMMEDIATE_UPGRADE', 'AT_RENEWAL', 'NO_CHANGE']), billing: BillingViewSchema });
export type ChangePlanResult = z.infer<typeof ChangePlanResultSchema>;

/* ── Team ──────────────────────────────────────────────── */

export const RoleCardSchema = z.object({
  key: z.string(),
  label: z.string(),
  summary: z.string(),
  note: nullableString,
  can: z.array(z.string()),
  view: z.array(z.string()),
  cannot: z.array(z.string())
});
export type RoleCard = z.infer<typeof RoleCardSchema>;
export const TeamMemberSchema = z.object({
  userId: z.string(),
  name: z.string(),
  email: nullableString,
  roleKey: z.string(),
  roleLabel: z.string(),
  /** INVITED rows are pending invitations (userId `invite:<invitationId>`). */
  status: z.enum(['ACTIVE', 'DISABLED', 'INVITED']),
  isYou: z.boolean(),
  invitationId: z.string().optional(),
  joinedAt: z.string().optional(),
  expiresAt: z.string().optional()
});
export type TeamMember = z.infer<typeof TeamMemberSchema>;

export const InvitationStatusSchema = z.enum(['PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED']);
export const InvitationPreviewSchema = z.object({
  orgName: z.string(),
  orgKind: z.enum(['SELLER', 'PROVIDER']),
  email: z.string(),
  roleKey: z.string(),
  roleLabel: z.string(),
  status: InvitationStatusSchema,
  expiresAt: z.string()
});
export type InvitationPreview = z.infer<typeof InvitationPreviewSchema>;
export const AcceptedInvitationSchema = z.object({
  orgId: z.string(),
  orgName: z.string(),
  orgKind: z.enum(['SELLER', 'PROVIDER']),
  roleKey: z.string(),
  roleLabel: z.string(),
  alreadyMember: z.boolean()
});
export type AcceptedInvitation = z.infer<typeof AcceptedInvitationSchema>;

/** Plain-language messages for the BFF's team problem codes (S236). */
export const TEAM_ERROR_MESSAGES: Record<string, string> = {
  LAST_OWNER: 'The organisation needs at least one owner. Make someone else an owner first.',
  INVALID_ROLE: 'Choose a role of this organisation.',
  OWNER_ONLY: 'Only an owner can do that.',
  OWN_ROLE: 'You can’t change or remove your own role. Ask another owner.',
  ALREADY_MEMBER: 'That person is already a member of this organisation.',
  INVITATION_EXPIRED: 'This invitation has expired. Ask the organisation for a new one.',
  INVITATION_USED: 'This invitation has already been used.',
  INVITATION_REVOKED: 'This invitation was withdrawn.',
  INVITATION_EMAIL_MISMATCH: 'This invitation was sent to a different email address. Sign in with that address.'
};
export const TeamViewSchema = z.object({ members: z.array(TeamMemberSchema), roles: z.array(RoleCardSchema), yourRoleKey: z.string(), canManage: z.boolean() });
export type TeamView = z.infer<typeof TeamViewSchema>;
export const InviteFormSchema = z.object({ email: z.string().trim().pipe(z.email('Enter a valid email address')), roleKey: z.string().min(1, 'Choose a role') });
export type InviteForm = z.infer<typeof InviteFormSchema>;

/* ── Provider Hub ──────────────────────────────────────── */

export const BookingStatusSchema = z.enum([
  'PENDING_PAYMENT', 'REQUESTED', 'CONFIRMED', 'RESCHEDULE_REQUESTED', 'RESCHEDULE_PROPOSED', 'COMPLETED', 'NO_SHOW', 'DECLINED', 'EXPIRED', 'CANCELLED_CUSTOMER', 'CANCELLED_PROVIDER'
]);
export type BookingStatus = z.infer<typeof BookingStatusSchema>;
export const BookingActionSchema = z.enum(['CONFIRM', 'DECLINE', 'PROPOSE', 'COMPLETE', 'NO_SHOW', 'CANCEL']);
export type BookingAction = z.infer<typeof BookingActionSchema>;
export const BookingCardSchema = z.object({
  id: z.string(),
  reference: z.string(),
  customerName: z.string(),
  serviceName: z.string(),
  startAt: z.string(),
  endAt: z.string(),
  durationMinutes: z.number().int(),
  status: BookingStatusSchema,
  statusLabel: z.string(),
  priceMinor: z.number().int(),
  depositMinor: z.number().int(),
  currency: z.string(),
  depositState: z.enum(['none', 'held', 'captured', 'released', 'refunded']),
  note: nullableString,
  practitioner: nullableString,
  expiresAt: nullableString,
  proposedStartAt: nullableString,
  allowedActions: z.array(BookingActionSchema)
});
export type BookingCard = z.infer<typeof BookingCardSchema>;
export const DaySummarySchema = z.object({
  date: z.string(),
  appointments: z.number().int(),
  bookedMinutes: z.number().int(),
  expectedRevenueMinor: z.number().int(),
  needsAction: z.number().int()
});
export type DaySummary = z.infer<typeof DaySummarySchema>;
export const WeekdaySchema = z.enum(['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']);
export type Weekday = z.infer<typeof WeekdaySchema>;
const HHMM = /^([01]\d|2[0-3]):[0-5]\d$|^24:00$/;
export const TimeRangeSchema = z.object({ start: z.string().regex(HHMM, 'Use HH:MM'), end: z.string().regex(HHMM, 'Use HH:MM') });
export type TimeRange = z.infer<typeof TimeRangeSchema>;
export const DayHoursSchema = z.object({ day: WeekdaySchema, ranges: z.array(TimeRangeSchema) });
export type DayHours = z.infer<typeof DayHoursSchema>;
export const CalendarViewSchema = z.object({
  timezone: z.string(),
  from: z.string(),
  to: z.string(),
  openHours: z.array(DayHoursSchema),
  bookings: z.array(BookingCardSchema),
  days: z.array(DaySummarySchema)
});
export type CalendarView = z.infer<typeof CalendarViewSchema>;
export const BookingRulesSchema = z.object({
  leadMinutes: z.number().int().min(0).max(43200),
  horizonDays: z.number().int().min(1).max(365),
  bufferMinutes: z.number().int().min(0).max(240),
  stepMinutes: z.number().int().min(5).max(240),
  maxPerDay: z.number().int().min(1).max(50),
  instantBooking: z.boolean(),
  autoAcceptReschedule: z.boolean()
});
export type BookingRules = z.infer<typeof BookingRulesSchema>;
export const AvailabilitySchema = z.object({
  timezone: z.string(),
  weekly: z.array(DayHoursSchema).length(7),
  blockedDates: z.array(z.object({ date: z.string(), note: z.string() })),
  rules: BookingRulesSchema
});
export type Availability = z.infer<typeof AvailabilitySchema>;

const toMinutes = (t: string): number => {
  const [h, m] = t.split(':').map(Number);
  return (h ?? 0) * 60 + (m ?? 0);
};

/** Client-side check of the week: ranges start before they end and never overlap (same rule as bookings). */
export const AvailabilityFormSchema = AvailabilitySchema.superRefine((a, ctx) => {
  a.weekly.forEach((d, i) => {
    const spans = d.ranges.map((r) => [toMinutes(r.start), toMinutes(r.end)] as const).sort((x, y) => x[0] - y[0]);
    spans.forEach(([s, e], j) => {
      if (s >= e) ctx.addIssue({ code: 'custom', path: ['weekly', i], message: 'Each range must start before it ends' });
      const prev = spans[j - 1];
      if (prev && s < prev[1]) ctx.addIssue({ code: 'custom', path: ['weekly', i], message: 'Working hours overlap' });
    });
  });
});

export const TakeHomeSchema = z.object({ commissionPercent: z.number(), commissionMinor: z.number().int(), cardFeesMinor: z.number().int(), takeHomeMinor: z.number().int() });
export const ServiceOfferSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string(),
  durationMinutes: z.number().int(),
  priceMinor: z.number().int(),
  depositMinor: z.number().int(),
  currency: z.string(),
  active: z.boolean(),
  takeHome: TakeHomeSchema
});
export type ServiceOffer = z.infer<typeof ServiceOfferSchema>;
export const ServiceInputSchema = z
  .object({
    name: z.string().trim().min(3, '3–120 characters').max(120, '3–120 characters'),
    description: z.string().max(2000),
    durationMinutes: z.number().int().min(5, 'At least 5 minutes').max(1440, 'At most 24 hours'),
    priceMinor: z.number().int().min(1, 'Price must be above zero'),
    depositMinor: z.number().int().min(0),
    active: z.boolean()
  })
  .refine((s) => s.depositMinor <= s.priceMinor, { path: ['depositMinor'], message: 'Deposit must be between 0 and the price' });
export type ServiceInput = z.infer<typeof ServiceInputSchema>;

export const PolicyKindSchema = z.enum(['FLEXIBLE', 'MODERATE', 'STRICT', 'CUSTOM']);
export type PolicyKind = z.infer<typeof PolicyKindSchema>;
export const CancellationPolicyInputSchema = z.object({
  kind: PolicyKindSchema,
  freeHours: z.number().int().min(0).max(720).optional(),
  partialRefund: z.boolean().optional(),
  partialFromHours: z.number().int().min(0).max(720).optional(),
  partialPercent: z.number().int().min(1).max(99).optional(),
  noShow: z.enum(['DEPOSIT', 'FULL']).optional(),
  rescheduleHours: z.number().int().min(0).max(720).optional(),
  maxReschedules: z.number().int().min(0).max(10).optional()
});
export type CancellationPolicyInput = z.infer<typeof CancellationPolicyInputSchema>;
export const CancellationPolicySchema = z.object({
  kind: PolicyKindSchema,
  label: z.string(),
  freeHours: z.number().int(),
  partialRefund: z.boolean(),
  partialFromHours: z.number().int(),
  partialPercent: z.number().int(),
  noShow: z.enum(['DEPOSIT', 'FULL']),
  rescheduleHours: z.number().int(),
  maxReschedules: z.number().int(),
  summary: z.string(),
  timeline: z.array(z.object({ label: z.string(), refundPercent: z.number().int() }))
});
export type CancellationPolicy = z.infer<typeof CancellationPolicySchema>;

export const EarningsRowSchema = z.object({
  bookingId: z.string(),
  reference: z.string(),
  customerName: z.string(),
  serviceName: z.string(),
  startAt: z.string(),
  status: BookingStatusSchema,
  statusLabel: z.string(),
  collectedMinor: z.number().int(),
  commissionMinor: z.number().int(),
  cardFeesMinor: z.number().int(),
  lateCancelFeeMinor: z.number().int(),
  netMinor: z.number().int()
});
export type EarningsRow = z.infer<typeof EarningsRowSchema>;
export const ProviderFeeSchema = z.object({
  id: z.string(),
  bookingReference: z.string(),
  amountMinor: z.number().int(),
  currency: z.string(),
  reason: z.string(),
  status: z.enum(['OUTSTANDING', 'COLLECTED']),
  nextAttemptAt: nullableString,
  createdAt: z.string()
});
export type ProviderFee = z.infer<typeof ProviderFeeSchema>;
export const ProviderFeesSchema = z.object({ available: z.boolean(), outstandingMinor: z.number().int(), collectedMinor: z.number().int(), items: z.array(ProviderFeeSchema) });
export type ProviderFees = z.infer<typeof ProviderFeesSchema>;

export const EarningsSchema = z.object({
  currency: z.string(),
  commissionPercent: z.number(),
  payoutDay: z.string(),
  totals: z.object({
    collectedMinor: z.number().int(),
    commissionMinor: z.number().int(),
    cardFeesMinor: z.number().int(),
    lateCancelFeesMinor: z.number().int(),
    netMinor: z.number().int(),
    heldMinor: z.number().int()
  }),
  rows: z.array(EarningsRowSchema),
  providerFees: ProviderFeesSchema
});
export type Earnings = z.infer<typeof EarningsSchema>;

export const SocialLinkNetworkSchema = z.enum(['instagram', 'tiktok', 'facebook', 'youtube', 'whatsapp', 'x', 'linkedin', 'website']);
export type SocialNetworkKey = z.infer<typeof SocialLinkNetworkSchema>;
export const PageLayoutSchema = z.enum(['CLASSIC', 'GALLERY_FIRST', 'STORY']);
export const PublicPageSchema = z.object({
  kind: OrgKindSchema,
  slug: z.string(),
  name: z.string(),
  tagline: z.string(),
  status: z.enum(['DRAFT', 'PUBLISHED']),
  publishedAt: nullableString,
  primaryColor: z.string(),
  accentColor: z.string(),
  layout: PageLayoutSchema,
  header: z.enum(['COVER', 'BAND']),
  buttonShape: z.enum(['ROUNDED', 'SQUARE']),
  gallerySize: z.number().int(),
  gallery: z.array(z.object({ assetId: z.string(), url: z.string(), caption: z.string() })),
  featured: z.array(z.object({ id: z.string(), headline: z.string(), body: z.string(), serviceId: nullableString, published: z.boolean() })),
  social: z.array(z.object({ network: SocialLinkNetworkSchema, url: z.string(), status: z.enum(['PENDING', 'APPROVED', 'REJECTED']) })),
  publicPath: z.string()
});
export type PublicPage = z.infer<typeof PublicPageSchema>;
export const PublicPageInputSchema = z.object({
  name: z.string().min(2).max(80).optional(),
  tagline: z.string().max(80).optional(),
  primaryColor: z.string().regex(/^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/).optional(),
  accentColor: z.string().regex(/^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/).optional(),
  layout: PageLayoutSchema.optional(),
  header: z.enum(['COVER', 'BAND']).optional(),
  buttonShape: z.enum(['ROUNDED', 'SQUARE']).optional(),
  gallerySize: z.number().int().min(0).max(24).optional(),
  featured: z
    .array(z.object({ id: z.string(), headline: z.string().max(80), body: z.string().max(400), serviceId: nullableString.optional(), published: z.boolean() }))
    .max(6)
    .optional(),
  social: z.array(z.object({ network: SocialLinkNetworkSchema, url: z.string() })).optional()
});
export type PublicPageInput = z.infer<typeof PublicPageInputSchema>;
