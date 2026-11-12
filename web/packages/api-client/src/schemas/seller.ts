import { z } from 'zod';

import { BookingTakeHomeSchema, SellerNegotiatedRateSchema, SellerRateCardSchema } from '../fee-rates';
import { DeliveryProofSchema } from './shopper';
import { ServiceDeliverySchema } from './shopper';

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
  'onboarding', 'home', 'orders', 'returns', 'disputes', 'listings', 'reviews', 'inventory', 'coupons', 'calendar', 'requests', 'hours', 'services', 'policy',
  'earnings', 'payouts', 'fees', 'billing', 'account-health', 'team'
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

export const PlanCapabilitySchema = z.enum(['bulkUpload', 'imageLibrary', 'coupons', 'aiListingWriter', 'lola']);
export type PlanCapability = z.infer<typeof PlanCapabilitySchema>;

export const SellerSessionSchema = z.object({
  user: SellerUserSchema,
  orgs: z.array(OrgSummarySchema),
  activeOrg: OrgSummarySchema.nullable(),
  portal: PortalSchema.nullable(),
  approved: z.boolean(),
  navGroups: z.array(NavGroupSchema),
  landing: ModuleKeySchema,
  accessNote: z.string(),
  /** What the active organisation's plan includes: anything not listed is not shown (and the BFF refuses it). */
  capabilities: z.array(PlanCapabilitySchema).default([])
});
export type SellerSession = z.infer<typeof SellerSessionSchema>;

export const OnboardingOfferingSchema = z.enum(['PRODUCTS', 'SERVICES', 'BOTH']);
export type OnboardingOffering = z.infer<typeof OnboardingOfferingSchema>;
export const OnboardingEntityTypeSchema = z.enum(['SOLE_PROPRIETOR', 'SINGLE_MEMBER_LLC', 'MULTI_MEMBER_LLC', 'CORPORATION', 'PARTNERSHIP', 'NONPROFIT']);
export type OnboardingEntityType = z.infer<typeof OnboardingEntityTypeSchema>;

export const OnboardingAddressSchema = z.object({
  line1: nullableString,
  line2: nullableString,
  city: nullableString,
  state: nullableString,
  postalCode: nullableString,
  country: nullableString
});
export type OnboardingAddress = z.infer<typeof OnboardingAddressSchema>;

export const OnboardingStepKeySchema = z.enum(['BUSINESS', 'BUSINESS_DETAILS', 'OWNERS', 'STRIPE_VERIFICATION', 'PAYOUTS', 'PLAN', 'CATALOG_COMPLIANCE', 'REVIEW']);
export type OnboardingStepKey = z.infer<typeof OnboardingStepKeySchema>;
/**
 * "Payouts": NOT_STARTED (no Stripe account yet), NEEDS_BANK (finish Stripe verification, where Stripe collects the
 * bank), FAILED (Stripe rejected the bank: change it), VERIFYING (bank on file, payouts not on yet), READY.
 */
export const OnboardingPayoutsStatusSchema = z.enum(['NOT_STARTED', 'NEEDS_BANK', 'FAILED', 'VERIFYING', 'READY']);
export type OnboardingPayoutsStatus = z.infer<typeof OnboardingPayoutsStatusSchema>;
export const OnboardingStepSlugSchema = z.enum(['business', 'business-details', 'owners', 'stripe-verification', 'payouts', 'plan', 'catalog-compliance', 'review']);
export type OnboardingStepSlug = z.infer<typeof OnboardingStepSlugSchema>;
export const OnboardingStepStateSchema = z.enum(['DONE', 'TODO', 'ACTION', 'WAITING', 'BLOCKED']);
export type OnboardingStepState = z.infer<typeof OnboardingStepStateSchema>;

export const OnboardingStepSchema = z.object({
  key: OnboardingStepKeySchema,
  slug: OnboardingStepSlugSchema,
  label: z.string(),
  sub: z.string(),
  state: OnboardingStepStateSchema
});
export type OnboardingStep = z.infer<typeof OnboardingStepSchema>;

export const OnboardingStripeStatusSchema = z.enum(['NOT_STARTED', 'IN_PROGRESS', 'REQUIREMENTS_DUE', 'PENDING', 'VERIFIED', 'REJECTED']);
export type OnboardingStripeStatus = z.infer<typeof OnboardingStripeStatusSchema>;
export const OnboardingStripeSchema = z.object({
  status: OnboardingStripeStatusSchema,
  detailsSubmitted: z.boolean(),
  chargesEnabled: z.boolean(),
  payoutsEnabled: z.boolean(),
  identityStatus: nullableString,
  taxIdStatus: nullableString,
  currentlyDue: z.array(z.string())
});
export type OnboardingStripe = z.infer<typeof OnboardingStripeSchema>;

export const OnboardingOwnerSchema = z.object({
  firstName: nullableString,
  lastName: nullableString,
  title: nullableString,
  email: nullableString,
  ownershipPercent: z.number().nullable(),
  significantControl: z.boolean(),
  dateOfBirth: nullableString,
  address: OnboardingAddressSchema.nullable()
});
export type OnboardingOwner = z.infer<typeof OnboardingOwnerSchema>;

export const OnboardingFirstChargeSchema = z.object({ chargeOn: nullableString, amountCents: z.number().int(), sentence: z.string() });
export type OnboardingFirstCharge = z.infer<typeof OnboardingFirstChargeSchema>;

export const OnboardingFoodPermitTypeSchema = z.enum(['FOOD_ESTABLISHMENT', 'COTTAGE_FOOD', 'FDA_FACILITY', 'FOOD_HANDLER']);
export type OnboardingFoodPermitType = z.infer<typeof OnboardingFoodPermitTypeSchema>;
export const OnboardingFoodPermitSchema = z.object({
  permitType: nullableString,
  permitNumber: nullableString,
  issuingAuthority: nullableString,
  expiresOn: nullableString,
  documentAssetId: nullableString
});
export const OnboardingServiceLicenceSchema = z.object({
  serviceName: z.string(),
  regulated: z.boolean(),
  licenceNumber: nullableString,
  issuingState: nullableString,
  expiresOn: nullableString,
  documentAssetId: nullableString
});
export type OnboardingServiceLicence = z.infer<typeof OnboardingServiceLicenceSchema>;

/** Every answer of the wizard (S286, S289); `prefilled` names the answers taken from the account and still unchanged.
 *  The tax ID comes back masked and the bank account as last 4 only. */
export const OnboardingFormSchema = z.object({
  business: z.object({
    offering: OnboardingOfferingSchema.nullable(),
    entityType: OnboardingEntityTypeSchema.nullable(),
    country: nullableString,
    storeName: nullableString,
    contactEmail: nullableString,
    phone: nullableString,
    /** What the business sells or does: shown on its public page and given to Stripe. */
    description: nullableString,
    /** #rrggbb: the one colour of its public page. */
    brandColor: nullableString,
    logoUrl: nullableString
  }),
  details: z.object({
    legalFirstName: nullableString,
    legalLastName: nullableString,
    legalName: nullableString,
    dba: nullableString,
    taxIdType: z.enum(['SSN', 'ITIN', 'EIN']).nullable(),
    taxIdMasked: nullableString,
    dateOfBirth: nullableString,
    stateOfFormation: nullableString,
    dateOfFormation: nullableString,
    address: OnboardingAddressSchema.nullable(),
    formationDocumentAssetId: nullableString,
    irsDeterminationLetterAssetId: nullableString
  }),
  owners: z.array(OnboardingOwnerSchema),
  /** The payout bank account Stripe holds, read-only: Stripe collects it in its embedded components. */
  payouts: z.object({
    status: OnboardingPayoutsStatusSchema,
    bankName: nullableString,
    accountLast4: nullableString,
    bankStatus: nullableString,
    linked: z.boolean()
  }),
  plan: z.object({
    planCode: nullableString,
    planName: nullableString,
    cadence: z.enum(['MONTHLY', 'QUARTERLY', 'ANNUAL']).nullable(),
    paymentMethodKind: z.enum(['CARD', 'US_BANK_ACCOUNT']).nullable(),
    cardBrand: nullableString,
    cardLast4: nullableString,
    cardExpMonth: z.number().int().nullable(),
    cardExpYear: z.number().int().nullable(),
    firstCharge: OnboardingFirstChargeSchema.nullable(),
    /** S298: the applicant's choice (null keeps the platform default), whether it is offered and the default. */
    planFeeCollection: z.enum(['EARNINGS_FIRST', 'CARD']).nullable().default(null),
    planFeeCollectionAvailable: z.boolean().default(false),
    planFeeCollectionDefault: z.enum(['EARNINGS_FIRST', 'CARD']).default('CARD')
  }),
  compliance: z.object({
    /** Catalog department codes chosen at onboarding (GET /v1/seller/departments). */
    departments: z.array(z.string()),
    foodPermit: OnboardingFoodPermitSchema.nullable(),
    beautyLabellingConfirmed: z.boolean().nullable(),
    services: z.array(OnboardingServiceLicenceSchema),
    insuranceDocumentAssetId: nullableString,
    agreementsAccepted: z.boolean().nullable(),
    w9Certified: z.boolean().nullable(),
    w9SignerName: nullableString,
    /** The legal documents accepted, each with the version in force when accepted. */
    agreements: z.array(z.object({ key: z.string(), version: z.string(), acceptedOn: nullableString }))
  }),
  documents: z.array(z.object({ assetId: z.string(), kind: z.string(), label: z.string() })),
  prefilled: z.array(z.string()),
  editable: z.boolean()
});
export type OnboardingForm = z.infer<typeof OnboardingFormSchema>;

export const OnboardingStatusSchema = z.object({
  orgId: z.string(),
  kind: OrgKindSchema,
  status: z.string(),
  reference: nullableString,
  steps: z.array(OnboardingStepSchema),
  requestedItems: z.array(z.string()),
  canResume: z.boolean(),
  resumeStep: z.string(),
  submittedAt: nullableString,
  stripe: OnboardingStripeSchema,
  timeline: z.array(z.object({ action: z.string(), status: z.string(), note: nullableString, occurredAt: z.string() })),
  form: OnboardingFormSchema
});
export type OnboardingStatus = z.infer<typeof OnboardingStatusSchema>;

/** PUT /v1/seller/onboarding/business. */
export interface OnboardingBusinessInput {
  offering?: OnboardingOffering;
  entityType?: OnboardingEntityType;
  country?: string;
  storeName?: string;
  contactEmail?: string;
  phone?: string;
  description?: string;
  brandColor?: string;
  logoUrl?: string;
}

export interface OnboardingAddressInput {
  line1: string;
  line2?: string;
  city: string;
  state: string;
  postalCode: string;
  country?: string;
}

/** PUT /v1/seller/onboarding/business-details (the whole step). `taxId` is write-only; omit it to keep the stored one. */
export interface OnboardingDetailsInput {
  legalFirstName?: string;
  legalLastName?: string;
  legalName?: string;
  dba?: string;
  taxId?: string;
  dateOfBirth?: string;
  stateOfFormation?: string;
  dateOfFormation?: string;
  address?: OnboardingAddressInput;
  formationDocumentAssetId?: string;
  irsDeterminationLetterAssetId?: string;
}

export interface OnboardingOwnerInput {
  firstName?: string;
  lastName?: string;
  title?: string;
  email?: string;
  ownershipPercent?: number;
  significantControl?: boolean;
  dateOfBirth?: string;
  address?: OnboardingAddressInput;
}

/** PUT /v1/seller/onboarding/owners. */
export interface OnboardingOwnersInput {
  owners: OnboardingOwnerInput[];
}

/** PUT /v1/seller/onboarding/plan. */
export interface OnboardingPlanInput {
  planCode: string;
  cadence: 'MONTHLY' | 'QUARTERLY' | 'ANNUAL';
  paymentMethodKind?: 'CARD';
  stripePaymentMethodId?: string;
  cardBrand?: string;
  cardLast4?: string;
  cardExpMonth?: number;
  cardExpYear?: number;
  /** A trial campaign's promo code (S295); 422 `promoCode` when it isn't valid for this plan. */
  promoCode?: string;
  /** How the plan will be paid (S298); omit for the platform default. */
  planFeeCollection?: 'EARNINGS_FIRST' | 'CARD';
}

/** PUT /v1/seller/onboarding/catalog-compliance. */
export interface OnboardingComplianceInput {
  departments?: string[];
  foodPermit?: { permitType?: OnboardingFoodPermitType; permitNumber?: string; issuingAuthority?: string; expiresOn?: string; documentAssetId?: string };
  beautyLabellingConfirmed?: boolean;
  services?: { serviceName: string; regulated?: boolean; licenceNumber?: string; issuingState?: string; expiresOn?: string; documentAssetId?: string }[];
  insuranceDocumentAssetId?: string;
  agreementsAccepted?: boolean;
  w9Certified?: boolean;
  w9SignerName?: string;
  /** The documents and versions shown; the BFF records the current version of every required document. */
  agreements?: { key: string; version: string }[];
}

export const OnboardingStripeSessionSchema = z.object({
  accountId: z.string(),
  clientSecret: z.string(),
  publishableKey: z.string(),
  expiresAt: nullableString,
  localTest: z.boolean()
});
export type OnboardingStripeSession = z.infer<typeof OnboardingStripeSessionSchema>;

export const OnboardingPlanPriceSchema = z.object({
  cadence: z.enum(['MONTHLY', 'QUARTERLY', 'ANNUAL']),
  months: z.number().int(),
  totalCents: z.number().int(),
  perMonthCents: z.number().int(),
  savingCents: z.number().int(),
  perMonthLabel: z.string(),
  billedLabel: z.string(),
  savingLabel: nullableString,
  firstCharge: OnboardingFirstChargeSchema.nullable()
});
export type OnboardingPlanPrice = z.infer<typeof OnboardingPlanPriceSchema>;
export const OnboardingPlansSchema = z.object({
  publishableKey: z.string(),
  plans: z.array(
    z.object({
      code: z.string(),
      name: z.string(),
      tagline: nullableString,
      monthlyCents: z.number().int(),
      trialDays: z.number().int(),
      features: z.array(z.string()),
      prices: z.array(OnboardingPlanPriceSchema)
    })
  )
});
export type OnboardingPlans = z.infer<typeof OnboardingPlansSchema>;
export type OnboardingPlanOffer = OnboardingPlans['plans'][number];

/** POST /v1/seller/onboarding/uploads (private kyc-document). */
export interface OnboardingUploadRequest {
  fileName: string;
  contentType: 'application/pdf' | 'image/jpeg' | 'image/png' | 'image/webp';
  size: number;
}

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
  /** The payout bank account Stripe holds (changed in Stripe's account management component). */
  payoutBank: z.object({ bankName: nullableString, last4: nullableString, status: nullableString }).nullable(),
  /** component: the Stripe embedded component that collects it (nothing is answered through AfroTransact). */
  items: z.array(
    z.object({ requirement: z.string(), label: z.string(), bucket: z.string(), component: z.enum(['account_onboarding', 'account_management']) })
  )
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
export const ModuleIconSchema = z.enum(['sales', 'orders', 'listings', 'payouts', 'inventory', 'reviews', 'health', 'promotions', 'bookings', 'revenue', 'calendar', 'applications', 'payments', 'trust', 'support', 'catalog', 'platform']);
export type ModuleIcon = z.infer<typeof ModuleIconSchema>;
export const ModuleActionSchema = z.object({ label: z.string(), href: z.string() });
export type ModuleAction = z.infer<typeof ModuleActionSchema>;
export const ModuleSummarySchema = z.object({
  id: z.string(),
  icon: ModuleIconSchema,
  title: z.string(),
  state: z.enum(['ready', 'empty', 'unavailable']),
  headline: z.object({ value: z.string(), label: z.string(), period: z.string().nullish(), tone: z.enum(['neutral', 'positive', 'attention', 'negative']) }).nullish(),
  chart: z
    .object({
      kind: z.enum(['sparkline', 'bars', 'donut']),
      label: z.string(),
      valueFormat: ValueFormatSchema,
      points: z.array(z.object({ label: z.string(), value: z.number(), tone: ChartToneSchema.optional() }))
    })
    .nullish(),
  totalLine: z.string().nullish(),
  actions: z.array(ModuleActionSchema).max(3),
  empty: z.object({ prompt: z.string(), action: ModuleActionSchema, art: z.enum(['listings', 'chart', 'calendar', 'inbox', 'payout', 'star']) }).nullish()
});
export type ModuleSummary = z.infer<typeof ModuleSummarySchema>;
export const DashboardSchema = z.object({
  portal: PortalSchema,
  rangeDays: z.number().int(),
  kpis: z.array(KpiSchema),
  charts: z.array(ChartSpecSchema),
  todo: z.array(TodoItemSchema),
  modules: z.array(ModuleSummarySchema)
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
/** What a seller may set; PICKED_UP is "Shipped" (handed to the carrier, needs a label). */
export const SellerStatusActionSchema = z.enum(['CONFIRMED', 'PACKED', 'READY', 'PICKED_UP', 'DELIVERED']);
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
export const SellerOrderPageSchema = z.object({
  items: z.array(SellerOrderRowSchema),
  nextCursor: nullableString,
  total: z.number().int(),
  /** The applied sort (`<column>,asc|desc`); empty for newest first. */
  sort: z.array(z.string()).default([]),
  /** The search or sort covered the first 5,000 shipments only. */
  partial: z.boolean().default(false)
});
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

export const SuggestedParcelSchema = z.object({ weightLb: z.number(), lengthIn: z.number(), widthIn: z.number(), heightIn: z.number() });
export type SuggestedParcel = z.infer<typeof SuggestedParcelSchema>;
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
  /** A label was bought for this shipment; it can be downloaded again. */
  hasLabel: z.boolean().default(false),
  /** The products' listed package (lb / in), to prefill the label form. */
  suggestedParcel: SuggestedParcelSchema.nullable().default(null),
  /** How our driver handed it over (photo, who received it); null for carrier, pickup and by-hand deliveries. */
  proof: DeliveryProofSchema.nullable().default(null),
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

/** "Customers can pick up their orders here" (a carrier-gateway SELLER pickup location with the address's id). */
export const PickupIssueSchema = z.object({ code: z.enum(['NOT_ENABLED', 'NO_HOURS', 'NOT_LOCATED', 'APPROXIMATE_LOCATION']), message: z.string(), blocking: z.boolean() });
export type PickupIssue = z.infer<typeof PickupIssueSchema>;
export const CustomerPickupSchema = z.object({
  enabled: z.boolean(),
  hours: z.array(PickupWindowSchema),
  readyHours: z.number().int().min(0).max(168),
  /** True when checkout can offer this address (pickup on and opening hours set). */
  ready: z.boolean().optional(),
  /** Why customers do not see this address as a pickup point, or why only some do (plain words, no ids). */
  issues: z.array(PickupIssueSchema).optional(),
  /** When pickup was last switched on (null while it never was). */
  enabledAt: nullableString.optional()
});
export type CustomerPickup = z.infer<typeof CustomerPickupSchema>;
export interface CustomerPickupRequest {
  enabled: boolean;
  hours: PickupWindow[];
  readyHours: number;
  /** The seller's explicit "someone will be there during these hours". Required to switch pickup on (422 PICKUP_HOURS_NOT_CONFIRMED). */
  hoursConfirmed?: boolean;
}

/** The one-time "let customers collect orders here" card: shown to an approved seller with a ship-from address and no pickup place. */
export const PickupPromptAddressSchema = z.object({
  addressId: z.string(),
  name: z.string(),
  line1: z.string(),
  line2: z.string(),
  city: z.string(),
  state: z.string(),
  postalCode: z.string(),
  phone: z.string()
});
export type PickupPromptAddress = z.infer<typeof PickupPromptAddressSchema>;
export const PickupPromptSchema = z.object({
  eligible: z.boolean(),
  hasPickupPlace: z.boolean(),
  show: z.boolean(),
  nudge: z.boolean(),
  state: z.enum(['NONE', 'SNOOZED', 'NEVER']),
  snoozedUntil: nullableString,
  address: PickupPromptAddressSchema.nullable()
});
export type PickupPrompt = z.infer<typeof PickupPromptSchema>;
export type PickupPromptAnswer = 'NOT_NOW' | 'DONT_ASK_AGAIN';

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
  }),
  /** Customers can collect their orders here (checkout pickup). */
  customerPickup: CustomerPickupSchema.optional()
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

export const SellerCouponSchema = z.object({
  id: z.string(),
  code: z.string(),
  description: z.string(),
  type: z.enum(['PERCENT', 'FIXED', 'FREE_SHIPPING']),
  value: z.number().int(),
  currency: z.string(),
  scope: z.string(),
  scopeRef: z.string().optional(),
  minSpendMinor: z.number().int(),
  status: z.enum(['ACTIVE', 'PAUSED', 'EXPIRED']),
  funding: z.enum(['PLATFORM', 'SELLER']),
  editable: z.boolean(),
  version: z.number().int()
});
export type SellerCoupon = z.infer<typeof SellerCouponSchema>;
export const SellerCouponListSchema = z.object({ items: z.array(SellerCouponSchema) });
export type SellerCouponList = z.infer<typeof SellerCouponListSchema>;

export const ReturnStatusSchema = z.enum(['REQUESTED', 'APPROVED', 'AUTO_APPROVED', 'LABEL_ISSUED', 'IN_TRANSIT', 'RECEIVED', 'INSPECTED', 'REFUNDED', 'REJECTED', 'CANCELLED']);
export type ReturnStatus = z.infer<typeof ReturnStatusSchema>;
export const SellerReturnLineSchema = z.object({ title: z.string(), quantity: z.number().int(), reason: z.string(), outcome: z.string().optional() });
export const SellerReturnSchema = z.object({
  number: z.string(),
  orderNumber: z.string(),
  title: z.string(),
  status: ReturnStatusSchema,
  statusLabel: z.string(),
  method: z.string(),
  refundMinor: z.number().int(),
  currency: z.string(),
  refundState: z.string(),
  items: z.array(SellerReturnLineSchema),
  carrier: z.string().nullish(),
  trackingNumber: z.string().nullish(),
  dropOffBy: z.string().nullish(),
  createdAt: z.string().nullish()
});
export type SellerReturn = z.infer<typeof SellerReturnSchema>;
export const SellerReturnListSchema = z.object({ items: z.array(SellerReturnSchema), total: z.number().int() });

export const DisputeStatusSchema = z.enum(['AWAITING_SELLER', 'UNDER_REVIEW', 'ESCALATED', 'RESOLVED_REFUND', 'RESOLVED_DENIED', 'WITHDRAWN']);
export type DisputeStatus = z.infer<typeof DisputeStatusSchema>;
export const SellerDisputeSchema = z.object({
  reference: z.string(),
  type: z.string(),
  typeLabel: z.string(),
  status: DisputeStatusSchema,
  statusLabel: z.string(),
  orderNumber: z.string(),
  amountMinor: z.number().int(),
  currency: z.string(),
  description: z.string(),
  responded: z.boolean(),
  sellerResponse: z.string().nullish(),
  respondBy: z.string().nullish(),
  deadlineState: z.enum(['ON_TRACK', 'AT_RISK', 'BREACHED']).nullish(),
  escalated: z.boolean(),
  outcome: z.string().nullish(),
  refundMinor: z.number().int(),
  openedAt: z.string().nullish()
});
export type SellerDispute = z.infer<typeof SellerDisputeSchema>;
export const SellerDisputeListSchema = z.object({ items: z.array(SellerDisputeSchema), total: z.number().int() });
export const CreateSellerCouponSchema = z.object({
  code: z.string(),
  description: z.string().optional(),
  type: z.enum(['PERCENT', 'FIXED', 'FREE_SHIPPING']),
  value: z.number().int(),
  currency: z.string().optional(),
  scope: z.enum(['CART', 'CATEGORY', 'PRODUCT', 'SELLER']),
  scopeRef: z.string().optional(),
  minSpendMinor: z.number().int().optional(),
  totalLimit: z.number().int().optional(),
  perCustomerLimit: z.number().int().optional()
});
export type CreateSellerCoupon = z.infer<typeof CreateSellerCouponSchema>;
export interface OfferFulfilmentRequest {
  fulfilment: FulfilmentKind;
  shipFromLocationId?: string | null;
}
/** GET/PUT /v1/seller/business-page: how the organisation's plain public page (/b/{slug}) looks. */
export const BusinessPageSettingsSchema = z.object({
  name: z.string(),
  /** The page's address; null until the page is first made. */
  slug: nullableString,
  description: nullableString,
  /** #rrggbb */
  brandColor: nullableString,
  logoUrl: nullableString,
  contactEmail: nullableString,
  /** False for members who may only look (the owner and admins change the page). */
  canEdit: z.boolean()
});
export type BusinessPageSettings = z.infer<typeof BusinessPageSettingsSchema>;
export interface BusinessPageSettingsInput {
  description: string;
  brandColor: string;
  logoUrl: string;
}
export const ShipFromListSchema = z.object({ items: z.array(ShipFromAddressSchema) });

/* ── Delivery run pick lists (S264): when the driver comes and what to pack ── */

export const RunPickItemSchema = z.object({ atin: z.string(), name: z.string(), units: z.number().int() });
export type RunPickItem = z.infer<typeof RunPickItemSchema>;
export const RunOrderBagSchema = z.object({ orderNumber: z.string(), collected: z.boolean(), units: z.number().int(), lines: z.array(RunPickItemSchema) });
export type RunOrderBag = z.infer<typeof RunOrderBagSchema>;
export const SellerRunPickupSchema = z.object({
  runId: z.string(),
  town: z.string(),
  state: z.string(),
  timeZone: z.string(),
  runStatus: z.enum(['CONFIRMED', 'COLLECTING', 'OUT_FOR_DELIVERY', 'COMPLETED']),
  windowStart: z.string(),
  windowEnd: z.string(),
  pickupAt: z.string().nullable(),
  dropMode: z.enum(['DOORSTEP', 'PICKUP_POINT']),
  orders: z.number().int(),
  units: z.number().int(),
  collectedOrders: z.number().int(),
  items: z.array(RunPickItemSchema),
  bags: z.array(RunOrderBagSchema)
});
export type SellerRunPickup = z.infer<typeof SellerRunPickupSchema>;
export const SellerRunPickupListSchema = z.object({ items: z.array(SellerRunPickupSchema) });
export type SellerRunPickupList = z.infer<typeof SellerRunPickupListSchema>;
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
export const ListingPageSchema = z.object({
  items: z.array(ListingRowSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  sort: z.array(z.string()).default([]),
  partial: z.boolean().default(false)
});
export type ListingPage = z.infer<typeof ListingPageSchema>;

/** The tax gate of a category: sellers publish only into ACTIVE ones; the others show a plain message and what happens next. */
export const CategoryTaxStatusSchema = z.enum(['ACTIVE', 'NEEDS_APPROVAL', 'BLOCKED_BUNDLE', 'REFINE_CODE', 'CONFLICT_OPEN']);
export type CategoryTaxStatus = z.infer<typeof CategoryTaxStatusSchema>;
export interface CategoryNode {
  id: string;
  /** The category's stable code (dairy-eggs); the listing rules are asked for by the code of the category above a product type. */
  code: string;
  name: string;
  level: string;
  productType: string | null;
  path: string;
  /** Category nodes only: the gate of the category's tax profile (absent on departments and product types). */
  taxStatus?: CategoryTaxStatus | null | undefined;
  children: CategoryNode[];
}
export const CategoryNodeSchema: z.ZodType<CategoryNode> = z.lazy(() =>
  z.object({ id: z.string(), code: z.string().default(''), name: z.string(), level: z.string(), productType: nullableString, path: z.string(), taxStatus: CategoryTaxStatusSchema.nullish(), children: z.array(CategoryNodeSchema) })
);

/** BRAND: chosen from the brand registry of the category; its options are the no-brand choices (UNBRANDED, HANDMADE). */
export const AttributeKindSchema = z.enum(['TEXT', 'MULTILINE', 'ENUM', 'NUMBER', 'BRAND', 'BOOLEAN', 'DECIMAL', 'INTEGER', 'PERCENT', 'FILE']);
export type AttributeKind = z.infer<typeof AttributeKindSchema>;
export const AttributeSectionSchema = z.enum(['DETAILS', 'FOOD', 'REGULATED', 'TAX']);
export type AttributeSection = z.infer<typeof AttributeSectionSchema>;
/** A show-when / required-when expression over fact ids: {all}, {any}, {not} or a leaf {op, fact, value|values}; null = always. */
export type FactExpression = { all: FactExpression[] } | { any: FactExpression[] } | { not: FactExpression } | { op: 'isTrue' | 'isFalse' | 'eq' | 'in' | 'gt' | 'gte' | 'lt' | 'lte'; fact: string; value?: string | number | boolean; values?: Array<string | number | boolean> };
export const FactExpressionSchema: z.ZodType<FactExpression> = z.lazy(() =>
  z.union([
    z.object({ all: z.array(FactExpressionSchema) }),
    z.object({ any: z.array(FactExpressionSchema) }),
    z.object({ not: FactExpressionSchema }),
    z.object({ op: z.enum(['isTrue', 'isFalse', 'eq', 'in', 'gt', 'gte', 'lt', 'lte']), fact: z.string(), value: z.union([z.string(), z.number(), z.boolean()]).optional(), values: z.array(z.union([z.string(), z.number(), z.boolean()])).optional() })
  ])
);
export const AttributeDefSchema = z.object({
  key: z.string(),
  label: z.string(),
  options: z.array(z.string()),
  required: z.boolean(),
  multiline: z.boolean(),
  kind: AttributeKindSchema,
  units: z.array(z.string()),
  section: AttributeSectionSchema,
  help: nullableString,
  /** Tax facts (section TAX): plain-word questions whose answers decide how the item is taxed. */
  taxFact: z.boolean().default(false),
  /** VARIANT facts are answered per variant; OFFER facts are the seller's own behaviour, part of "Your listing". */
  owner: z.enum(['VARIANT', 'OFFER']).nullish(),
  unit: nullableString.optional(),
  showWhen: FactExpressionSchema.nullish(),
  requiredWhen: FactExpressionSchema.nullish(),
  /** A FILE fact that is proof for an exemption claim (uploaded for each item once it is saved). */
  evidence: z.boolean().default(false),
  /** The plain label of each option value of a choice question. */
  optionLabels: z.record(z.string(), z.string()).default({})
});
export type AttributeDef = z.infer<typeof AttributeDefSchema>;
/** The unit of a size chart's values; the other unit is shown by conversion. */
export const SizeChartUnitSchema = z.enum(['CM', 'IN']);
export type SizeChartUnit = z.infer<typeof SizeChartUnitSchema>;
/** How the sizes fit; shown beside the size chart. */
export const FitNoteSchema = z.enum(['RUNS_SMALL', 'TRUE_TO_SIZE', 'RUNS_LARGE']);
export type FitNote = z.infer<typeof FitNoteSchema>;
/** A measurement a sized category suggests for the seller's size chart (bust, waist, foot length ...). */
export const SuggestedMeasurementSchema = z.object({
  key: z.string(),
  label: z.string(),
  unit: SizeChartUnitSchema.nullish(),
  /** One line telling the seller how to measure it. */
  help: nullableString.optional()
});
export type SuggestedMeasurement = z.infer<typeof SuggestedMeasurementSchema>;
/** Staff mark a product type as sized; sellers then write size charts and their own size labels. */
export const SizingTraitSchema = z.object({
  /** A listing needs a size chart whose rows cover every size it sells. */
  sizeChartRequired: z.boolean(),
  /** The variation dimension that holds the size (Size). Its options are suggestions; custom values are allowed. */
  sizeDimension: z.string(),
  suggestedMeasurements: z.array(SuggestedMeasurementSchema)
});
export type SizingTrait = z.infer<typeof SizingTraitSchema>;
export const ProductTypeTraitsSchema = z.object({
  food: z.boolean(),
  perishable: z.boolean(),
  regulated: z.boolean(),
  preorder: z.boolean(),
  /** Set for sized product types (fashion): a size chart section and seller-written sizes; null for the others. */
  sizing: SizingTraitSchema.nullable().default(null)
});
export type ProductTypeTraits = z.infer<typeof ProductTypeTraitsSchema>;
/** One axis of a variation (Size, Color). `options` are the category's values: the only allowed ones, or only suggestions
 *  (chips) when `allowCustomValues`, in which case the form also offers an "Add size" / "Add colour" input. */
export const VariationDimensionSchema = z.object({
  name: z.string(),
  options: z.array(z.string()),
  allowCustomValues: z.boolean().default(false)
});
export type VariationDimension = z.infer<typeof VariationDimensionSchema>;
export const ProductTypeSchemaSchema = z.object({
  code: z.string(),
  label: z.string(),
  identifierRule: z.enum(['gtin', 'isbn', 'none']),
  attributes: z.array(AttributeDefSchema),
  themes: z.array(z.object({ name: z.string(), dimensions: z.array(VariationDimensionSchema) })),
  traits: ProductTypeTraitsSchema
});
export type ProductTypeSchema = z.infer<typeof ProductTypeSchemaSchema>;

/* ── Listing rules: what a category asks of a listing (GET /v1/seller/catalog/listing-rules), the one source for the product
   form, the API validation and the bulk template. ── */
export const FieldStatusSchema = z.enum(['required', 'required_or_exemption', 'document', 'optional', 'hidden']);
export type FieldStatus = z.infer<typeof FieldStatusSchema>;
export const ListingKindSchema = z.enum(['PKG', 'PER', 'SFD', 'BBY', 'BEA', 'HLT', 'FSH', 'JWL', 'ELC', 'HOM', 'KID', 'PET', 'BOK', 'DIG', 'GFT', 'SRV']);
export type ListingKind = z.infer<typeof ListingKindSchema>;
export const ListingRuleFieldSchema = z.object({
  id: z.string(),
  group: z.string(),
  label: z.string(),
  status: FieldStatusSchema,
  note: z.string().default(''),
  control: z.string(),
  attributeKey: nullableString.optional(),
  options: z.array(z.string()).default([]),
  units: z.array(z.string()).default([]),
  perVariant: z.boolean().default(false)
});
export type ListingRuleField = z.infer<typeof ListingRuleFieldSchema>;
export const ApprovalLevelSchema = z.enum(['open', 'approval', 'brand_gated', 'conditional_approval', 'provider_application', 'blocked']);
export type ApprovalLevel = z.infer<typeof ApprovalLevelSchema>;
export const ListingRulesSchema = z.object({
  categoryCode: z.string(),
  categoryName: z.string(),
  department: z.string(),
  kind: ListingKindSchema,
  kindName: z.string(),
  approval: ApprovalLevelSchema,
  note: z.string().default(''),
  provisional: z.boolean().default(false),
  fields: z.array(ListingRuleFieldSchema),
  /** Product-type detail keys the rules own: the form shows the rule field instead and ignores these attributes. */
  replacedAttributeKeys: z.array(z.string()).default([])
});
export type ListingRules = z.infer<typeof ListingRulesSchema>;

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

/** What a department asks the seller to have on file (data on the catalog department node). */
export const DepartmentRequirementSchema = z.enum(['FOOD_PERMIT', 'BEAUTY_LABELLING']);
export type DepartmentRequirement = z.infer<typeof DepartmentRequirementSchema>;
/** GET /v1/seller/departments: the catalog's departments onboarding offers. */
export const SellerDepartmentSchema = z.object({ code: z.string(), name: z.string(), requires: z.array(DepartmentRequirementSchema) });
export type SellerDepartment = z.infer<typeof SellerDepartmentSchema>;
export const SellerDepartmentRequestSchema = z.object({
  id: z.string().optional(),
  departmentCode: z.string(),
  departmentName: z.string().optional(),
  status: z.enum(['PENDING', 'APPROVED', 'REJECTED']),
  needs: z.array(DepartmentRequirementSchema),
  createdAt: z.string().optional(),
  decisionNote: nullableString.optional()
});
export type SellerDepartmentRequest = z.infer<typeof SellerDepartmentRequestSchema>;
export const SellerDepartmentRequestInputSchema = z.object({ departmentCode: z.string().min(1), note: z.string().max(500).optional() });
export type SellerDepartmentRequestInput = z.infer<typeof SellerDepartmentRequestInputSchema>;
/** GET /v1/seller/catalog-scope: the departments the organisation may sell in. */
export const SellerCatalogScopeSchema = z.object({
  exempt: z.boolean(),
  departments: z.array(z.object({ id: z.string(), code: z.string(), name: z.string(), hasSizing: z.boolean() })),
  hasSizing: z.boolean(),
  requests: z.array(z.object({ departmentCode: z.string(), departmentName: z.string(), status: z.enum(['PENDING', 'APPROVED', 'REJECTED']), needs: z.array(z.string()), createdAt: z.string().optional() }))
});
export type SellerCatalogScope = z.infer<typeof SellerCatalogScopeSchema>;

/* ---- Selling approvals (docs/runbooks/selling-approvals.md) ---- */
export const SellingLevelSchema = z.enum(['SELL', 'APPLY', 'NOT_AVAILABLE']);
export type SellingLevel = z.infer<typeof SellingLevelSchema>;
export const SellingScopeTypeSchema = z.enum(['CATEGORY', 'PRODUCT_TYPE', 'BRAND', 'BRAND_IN_CATEGORY', 'PRODUCT', 'CAPABILITY']);
export type SellingScopeType = z.infer<typeof SellingScopeTypeSchema>;
export const SellingDocumentKindSchema = z.enum(['PURCHASE_INVOICE', 'PHOTOS_ALL_SIDES', 'BRAND_AUTHORIZATION', 'SAFETY_CERTIFICATE', 'PERMIT_LICENCE', 'LIABILITY_INSURANCE']);
export type SellingDocumentKind = z.infer<typeof SellingDocumentKindSchema>;
export const SellingApprovalStateSchema = z.enum(['NOT_APPLIED', 'APPLIED', 'NEEDS_ATTENTION', 'APPROVED', 'EXPIRED', 'NOT_AVAILABLE']);
export type SellingApprovalState = z.infer<typeof SellingApprovalStateSchema>;
/** A document a rule asks for, with how the form asks for it (expiry date, invoice date, additional insured). */
export const SellingDocumentSlotSchema = z.object({
  kind: SellingDocumentKindSchema,
  label: z.string(),
  optional: z.boolean(),
  expiryRequired: z.boolean(),
  dated: z.boolean(),
  namesAfroTransact: z.boolean()
});
export type SellingDocumentSlot = z.infer<typeof SellingDocumentSlotSchema>;
export const SellingDocumentOnFileSchema = z.object({
  id: z.string(),
  kind: z.string(),
  label: z.string(),
  assetId: z.string(),
  fileName: nullableString.optional(),
  issuedOn: nullableString.optional(),
  expiresOn: nullableString.optional(),
  namesAfroTransact: z.boolean().optional(),
  note: nullableString.optional()
});
export type SellingDocumentOnFile = z.infer<typeof SellingDocumentOnFileSchema>;
/** One scope in the way of a listing: what to apply for, or why it cannot be sold. */
export const SellingMissingSchema = z.object({
  scope: z.object({ type: SellingScopeTypeSchema, key: z.string() }),
  label: z.string(),
  level: z.enum(['APPLY', 'NOT_AVAILABLE']),
  state: SellingApprovalStateSchema,
  message: z.string(),
  canApply: z.boolean(),
  reapplyAfter: nullableString.optional(),
  documents: z.array(z.object({ kind: z.string(), optional: z.boolean() })),
  applicationId: nullableString.optional(),
  applicationStatus: nullableString.optional()
});
export type SellingMissing = z.infer<typeof SellingMissingSchema>;
/** GET /v1/seller/selling-status: the product form's chip ("Sell this product", "Apply to sell", "Not available"). */
export const SellerSellingStatusSchema = z.object({
  level: SellingLevelSchema,
  reason: nullableString.optional(),
  missing: z.array(SellingMissingSchema),
  pendingFacts: z.array(z.string())
});
export type SellerSellingStatus = z.infer<typeof SellerSellingStatusSchema>;
export const SellingApprovalRowSchema = z.object({
  scopeType: SellingScopeTypeSchema,
  scopeKey: z.string(),
  label: z.string(),
  level: z.enum(['APPLY', 'NOT_AVAILABLE']),
  state: SellingApprovalStateSchema,
  statusWords: z.string(),
  message: nullableString.optional(),
  canApply: z.boolean(),
  approvedAt: nullableString.optional(),
  expiresAt: nullableString.optional(),
  daysToExpiry: z.number().nullable().optional(),
  reapplyAfter: nullableString.optional(),
  documents: z.array(SellingDocumentSlotSchema),
  onFile: z.array(SellingDocumentOnFileSchema),
  applicationId: nullableString.optional(),
  applicationStatus: nullableString.optional(),
  note: nullableString.optional(),
  listingsPaused: z.boolean()
});
export type SellingApprovalRow = z.infer<typeof SellingApprovalRowSchema>;
export const SellingCapabilitiesSchema = z.object({
  foodService: z.object({ approved: z.boolean(), state: z.string(), expiresAt: nullableString.optional() })
});
export type SellingCapabilities = z.infer<typeof SellingCapabilitiesSchema>;
export const SellingApprovalsSchema = z.object({
  approvals: z.array(SellingApprovalRowSchema),
  notices: z.array(z.object({ severity: z.enum(['action', 'warning', 'urgent']), scopeKey: z.string().optional(), scopeLabel: z.string().optional(), message: z.string() })),
  capabilities: SellingCapabilitiesSchema
});
export type SellingApprovals = z.infer<typeof SellingApprovalsSchema>;
export const SellingApplicationSchema = z.object({
  id: z.string(),
  reference: z.string(),
  scopeType: z.string(),
  scopeKey: z.string(),
  scopeLabel: z.string(),
  kind: z.enum(['NEW', 'RENEWAL']),
  status: z.enum(['PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED', 'APPROVED', 'REJECTED']),
  statusWords: z.string(),
  message: z.string(),
  requestedItems: z.array(z.string()),
  disqualified: z.boolean(),
  reapplyAfter: nullableString.optional(),
  documents: z.array(SellingDocumentOnFileSchema),
  required: z.array(SellingDocumentSlotSchema),
  submittedAt: nullableString.optional(),
  updatedAt: z.string()
});
export type SellingApplication = z.infer<typeof SellingApplicationSchema>;
export const SellingDocumentInputSchema = z.object({
  kind: SellingDocumentKindSchema,
  assetId: z.string().min(1),
  fileName: z.string().max(200).optional(),
  issuedOn: z.string().optional(),
  expiresOn: z.string().optional(),
  namesAfroTransact: z.boolean().optional(),
  note: z.string().max(500).optional()
});
export type SellingDocumentInput = z.infer<typeof SellingDocumentInputSchema>;
export const ApplyToSellSchema = z.object({
  scopeType: SellingScopeTypeSchema,
  scopeKey: z.string().min(1).max(200),
  note: z.string().max(1000).optional(),
  documents: z.array(SellingDocumentInputSchema).max(20).optional()
});
export type ApplyToSell = z.infer<typeof ApplyToSellSchema>;
export const ResubmitSellingSchema = z.object({ note: z.string().max(1000).optional(), documents: z.array(SellingDocumentInputSchema).max(20).optional() });
export type ResubmitSelling = z.infer<typeof ResubmitSellingSchema>;
/** What the product form asks the status of: the listing's facts so far. */
export interface SellingFacts {
  categoryCode?: string;
  brand?: string;
  productTypeCode?: string;
  material?: string;
  gtin?: string;
}

/** The box as shipped: inches and pounds, within the carrier limits. */
export const PackageDimensionsSchema = z.object({
  lengthIn: z.number().positive().max(108),
  widthIn: z.number().positive().max(108),
  heightIn: z.number().positive().max(108),
  weightLb: z.number().positive().max(150)
});

export const ProductImageInputSchema = z.object({
  url: z.string().min(1),
  width: z.number().int().min(0).optional(),
  height: z.number().int().min(0).optional(),
  bytes: z.number().int().min(0).optional(),
  contentType: z.string(),
  /** Optional alternative text, stored with the image. */
  alt: z.string().max(200).nullable().optional(),
  /** LABEL for the label photo (ingredients and nutrition panel, Supplement Facts, Drug Facts); absent for a product picture. */
  role: z.literal('LABEL').nullable().optional()
});
export type ProductImageInput = z.infer<typeof ProductImageInputSchema>;
/** An answer to a tax question: yes/no, a number, or the chosen option value. */
export const FactValueSchema = z.union([z.boolean(), z.number(), z.string()]);
export type FactValue = z.infer<typeof FactValueSchema>;
export const VariantInputSchema = z.object({
  options: z.record(z.string(), z.string()),
  /** Optional: when empty the catalog generates a stable, organisation-prefixed SKU. */
  sellerSku: z.string().max(64).optional(),
  /** The existing variant's ATIN: matches it when the SKU and barcode are absent. */
  atin: z.string().optional(),
  /** The category's product questions for this variant (net weight, servings ...), by fact id. */
  taxFacts: z.record(z.string(), FactValueSchema).optional(),
  gtin: z.string().optional(),
  gtinExempt: z.boolean().optional(),
  priceMinor: z.number().int().min(1),
  compareAtMinor: z.number().int().min(1).nullable().optional(),
  quantity: z.number().int().min(0).max(100_000),
  images: z.array(ProductImageInputSchema).max(5).optional(),
  /** The box of this variant as shipped; required for every physical variant, a variant without one starts from the listing's. */
  packaging: PackageDimensionsSchema.nullable().optional()
});
export type VariantInput = z.infer<typeof VariantInputSchema>;
export const ItemConditionSchema = z.enum(['NEW', 'RENEWED', 'USED_LIKE_NEW', 'USED_VERY_GOOD', 'USED_GOOD', 'USED_ACCEPTABLE']);
export type ItemCondition = z.infer<typeof ItemConditionSchema>;
export const ListingFulfilmentSchema = z.enum(['SELLER', 'AFROTRANSACT']);
export type ListingFulfilment = z.infer<typeof ListingFulfilmentSchema>;
export type PackageDimensions = z.infer<typeof PackageDimensionsSchema>;
/** A document uploaded on the product form; the approval engine reviews it (kind is the rule field id). */
export const ListingDocumentSchema = z.object({
  kind: z.enum(['permit', 'brand_authorization', 'safety_certificate', 'liability_insurance', 'authenticity_invoice']),
  url: z.string().min(1),
  fileName: nullableString.optional(),
  contentType: z.string(),
  bytes: z.number().int().min(0).optional(),
  expiresOn: nullableString.optional(),
  status: z.enum(['PENDING_REVIEW', 'APPROVED', 'REJECTED']).optional()
});
export type ListingDocument = z.infer<typeof ListingDocumentSchema>;
export const ListingDetailsInputSchema = z.object({
  description: z.string().max(5000).optional(),
  condition: ItemConditionSchema.optional(),
  keywords: z.array(z.string().max(40)).max(20).optional(),
  fulfilment: ListingFulfilmentSchema.optional(),
  packaging: PackageDimensionsSchema.nullable().optional(),
  handlingDays: z.number().int().min(1).max(30).optional(),
  shipFromId: nullableString.optional(),
  /** One of the organisation's size charts; required when the product type's sizing trait says so. */
  sizeChartId: nullableString.optional(),
  /** How the sizes fit; defaults to the chart's own. */
  fitNote: FitNoteSchema.nullable().optional(),
  /** "Your listing": how this seller sells the item (sold hot, heated by you, utensils included), by fact id. */
  sellerBehaviour: z.record(z.string(), FactValueSchema).optional(),
  /** The dangerous goods answer where the category asks it; a yes marks the listing for fulfilment and carrier checks. */
  dangerousGoods: z.boolean().nullable().optional(),
  /** Documents uploaded on the form, reviewed by the approval engine; they never block the listing. */
  documents: z.array(ListingDocumentSchema).max(20).optional(),
  /** Rule field id to the reason for a "required or exemption" field the seller cannot provide. */
  exemptions: z.record(z.string(), z.string().max(300)).optional()
});
export type ListingDetailsInput = z.infer<typeof ListingDetailsInputSchema>;
export const ProductDraftSchema = z.object({
  categoryId: z.string().optional(),
  categoryRequestId: z.string().optional(),
  title: z.string().min(10).max(200),
  attributes: z.record(z.string(), z.string()),
  images: z.array(ProductImageInputSchema).min(1).max(10),
  variationTheme: z.string().max(60),
  variants: z.array(VariantInputSchema).min(1).max(200),
  preorderEligible: z.boolean().optional(),
  listing: ListingDetailsInputSchema.optional(),
  draftId: z.string().optional()
});
export type ProductDraft = z.infer<typeof ProductDraftSchema>;
export type ProductUpdate = ProductDraft & { version?: number };

export const ProductVariantSchema = z.object({
  atin: z.string(),
  sellerSku: z.string(),
  gtin: nullableString,
  gtinExempt: z.boolean(),
  options: z.record(z.string(), z.string()),
  priceMinor: z.number().int(),
  compareAtMinor: z.number().int().nullable(),
  quantity: z.number().int(),
  images: z.array(ProductImageInputSchema),
  /** True when the catalog generated the SKU because the seller left it empty. */
  sellerSkuGenerated: z.boolean().default(false),
  taxFacts: z.record(z.string(), FactValueSchema).default({}),
  taxStatus: z.enum(['READY', 'MISSING_FACTS', 'PENDING_REVIEW', 'BLOCKED', 'NEEDS_APPROVAL', 'REFINE_CODE', 'CONFLICT_OPEN', 'REJECTED']).default('MISSING_FACTS'),
  missingTaxFacts: z.array(z.object({ factId: z.string(), label: nullableString.optional(), message: z.string() })).default([]),
  /** This variant's box as shipped; null for items that ship nothing and for listings saved before packages were per variant. */
  packaging: PackageDimensionsSchema.nullish()
});
export type ProductVariant = z.infer<typeof ProductVariantSchema>;
export const ListingDetailsSchema = z.object({
  description: nullableString,
  condition: ItemConditionSchema,
  keywords: z.array(z.string()),
  fulfilment: ListingFulfilmentSchema,
  packaging: PackageDimensionsSchema.nullable(),
  handlingDays: z.number().int(),
  shipFromId: nullableString,
  sizeChartId: nullableString.default(null),
  fitNote: FitNoteSchema.nullable().default(null),
  sellerBehaviour: z.record(z.string(), FactValueSchema).default({}),
  dangerousGoods: z.boolean().nullish(),
  documents: z.array(ListingDocumentSchema).optional(),
  exemptions: z.record(z.string(), z.string()).optional()
});
export type ListingDetails = z.infer<typeof ListingDetailsSchema>;

/** The tax publish gate of a product, per variant, in plain words (GET /v1/seller/products/{id}/tax-readiness). */
export const VariantTaxStatusSchema = z.enum(['READY', 'MISSING_FACTS', 'PENDING_REVIEW', 'BLOCKED', 'NEEDS_APPROVAL', 'REFINE_CODE', 'CONFLICT_OPEN', 'REJECTED']);
export type VariantTaxStatus = z.infer<typeof VariantTaxStatusSchema>;
export const ProductTaxReadinessSchema = z.object({
  productId: z.string(),
  status: VariantTaxStatusSchema,
  categoryCode: nullableString.optional(),
  categoryStatus: nullableString.optional(),
  categoryMessage: nullableString.optional(),
  ruleVersion: nullableString.optional(),
  variants: z.array(z.object({
    atin: z.string(),
    sellerSku: z.string(),
    status: VariantTaxStatusSchema,
    missingTaxFacts: z.array(z.object({ factId: z.string(), label: nullableString.optional(), message: z.string() })),
    reason: nullableString.optional()
  }))
});
export type ProductTaxReadiness = z.infer<typeof ProductTaxReadinessSchema>;
/** A proof file stored for an exemption fact of a variant. */
export const VariantTaxEvidenceSchema = z.object({
  id: z.string(),
  atin: z.string(),
  factId: z.string(),
  version: z.number().int(),
  fileName: z.string(),
  contentType: z.string(),
  bytes: z.number().int(),
  url: z.string(),
  uploadedAt: z.string()
});
export type VariantTaxEvidence = z.infer<typeof VariantTaxEvidenceSchema>;

/* ── Size charts (sellers write them; the category only suggests sizes and measurements) ── */

export const SizeChartColumnSchema = z.object({ key: z.string(), label: z.string(), unit: SizeChartUnitSchema });
export type SizeChartColumn = z.infer<typeof SizeChartColumnSchema>;
/** One measurement of one size, in the chart's unit: a single value (max equals min) or a range. */
export const SizeChartValueSchema = z.object({ min: z.number(), max: z.number() });
export type SizeChartValue = z.infer<typeof SizeChartValueSchema>;
export const SizeChartRowSchema = z.object({
  size: z.string(),
  values: z.record(z.string(), SizeChartValueSchema),
  /** The same values converted to the chart's `alternateUnit`, one decimal. */
  alternateValues: z.record(z.string(), SizeChartValueSchema)
});
export type SizeChartRow = z.infer<typeof SizeChartRowSchema>;
export const SizeChartSchema = z.object({
  id: z.string(),
  ownerOrgId: z.string(),
  name: z.string(),
  unitSystem: SizeChartUnitSchema,
  alternateUnit: SizeChartUnitSchema,
  productTypeCode: nullableString,
  categoryId: nullableString,
  columns: z.array(SizeChartColumnSchema),
  rows: z.array(SizeChartRowSchema),
  fitNote: FitNoteSchema.nullable(),
  howToMeasure: nullableString,
  retired: z.boolean(),
  retiredAt: nullableString,
  /** Products that use this chart. */
  productCount: z.number().int(),
  createdAt: z.string(),
  updatedAt: z.string(),
  /** Send back as `version` when saving. */
  version: z.number().int()
});
export type SizeChart = z.infer<typeof SizeChartSchema>;
export const SizeChartPageSchema = z.object({ items: z.array(SizeChartSchema), total: z.number().int(), page: z.number().int(), size: z.number().int() });
export type SizeChartPage = z.infer<typeof SizeChartPageSchema>;

/** A size chart as the seller writes it (POST; PUT adds the `version` the editor loaded). */
export const SizeChartInputSchema = z.object({
  name: z.string().trim().min(2, 'Name the chart').max(80),
  unitSystem: SizeChartUnitSchema,
  productTypeCode: z.string().max(40).nullish(),
  /** A product-type (third level) category node; implies the product type. */
  categoryId: z.string().nullish(),
  columns: z
    .array(
      z.object({
        /** Derived from the label when absent (Foot length becomes foot_length). */
        key: z.string().max(40).optional(),
        label: z.string().trim().min(1, 'Name the measurement').max(40),
        unit: SizeChartUnitSchema.optional()
      })
    )
    .min(1, 'Add a measurement')
    .max(12),
  rows: z
    .array(
      z.object({
        size: z.string().trim().min(1, 'Name the size').max(40),
        /** A value for every column key: omit `max` for a single value. */
        values: z.record(z.string(), z.object({ min: z.number().positive().max(1000), max: z.number().positive().max(1000).nullish() }))
      })
    )
    .min(1, 'Add a size')
    .max(60),
  fitNote: FitNoteSchema.nullish(),
  howToMeasure: z.string().max(1000).nullish(),
  version: z.number().int().min(0).nullish()
});
export type SizeChartInput = z.input<typeof SizeChartInputSchema>;

/** What a category suggests for the chart editor and the size input: sizes and measurement columns. */
export const SizeChartSuggestionsSchema = z.object({
  productTypeCode: z.string(),
  productTypeLabel: z.string(),
  /** false: this product type has no sizes and no size chart. */
  sized: z.boolean(),
  sizeChartRequired: z.boolean(),
  sizeDimension: z.string(),
  sizes: z.array(z.string()),
  sizesAllowCustomValues: z.boolean(),
  unitSystem: SizeChartUnitSchema,
  measurements: z.array(SuggestedMeasurementSchema)
});
export type SizeChartSuggestions = z.infer<typeof SizeChartSuggestionsSchema>;

export const SizeChartImportMessageSchema = z.object({
  /** chart_name, unit, size, fit_note, how_to_measure or `m:<measurement key>`; null for the row. */
  column: nullableString,
  columnIndex: z.number().int(),
  severity: z.enum(['ERROR', 'WARNING']),
  message: z.string()
});
export const SizeChartImportRowSchema = z.object({
  rowNumber: z.number().int(),
  chartName: z.string(),
  size: z.string(),
  status: z.enum(['VALID', 'WARNING', 'ERROR']),
  messages: z.array(SizeChartImportMessageSchema),
  cells: z.array(z.string())
});
export type SizeChartImportRow = z.infer<typeof SizeChartImportRowSchema>;
export const SizeChartImportChartSchema = z.object({
  name: z.string(),
  /** What was done, or with `dryRun` what would be done. */
  action: z.enum(['CREATE', 'UPDATE', 'UNCHANGED', 'FAILED']),
  chartId: nullableString,
  rows: z.array(z.number().int()),
  message: nullableString
});
export type SizeChartImportChart = z.infer<typeof SizeChartImportChartSchema>;
/** The row-level result of a size chart upload; `applied` is false for a dry run (nothing saved). */
export const SizeChartImportReportSchema = z.object({
  applied: z.boolean(),
  fileName: z.string(),
  fileWarnings: z.array(z.string()),
  chartCount: z.number().int(),
  created: z.number().int(),
  updated: z.number().int(),
  unchanged: z.number().int(),
  failed: z.number().int(),
  errorRows: z.number().int(),
  warningRows: z.number().int(),
  charts: z.array(SizeChartImportChartSchema),
  rows: z.array(SizeChartImportRowSchema)
});
export type SizeChartImportReport = z.infer<typeof SizeChartImportReportSchema>;
export const SellerProductDetailSchema = z.object({
  productId: z.string(),
  atin: z.string(),
  status: ListingStatusSchema,
  statusLabel: z.string(),
  moderationNote: nullableString,
  categoryId: nullableString,
  categoryRequestId: nullableString,
  categoryPath: z.string(),
  productType: z.string(),
  title: z.string(),
  attributes: z.record(z.string(), z.string()),
  images: z.array(ProductImageInputSchema),
  variationTheme: z.string(),
  variants: z.array(ProductVariantSchema),
  preorderEligible: z.boolean(),
  listing: ListingDetailsSchema,
  currency: z.string(),
  version: z.number().int(),
  updatedAt: z.string()
});
export type SellerProductDetail = z.infer<typeof SellerProductDetailSchema>;

export const ProductDraftSummarySchema = z.object({
  id: z.string(),
  productId: nullableString,
  title: z.string(),
  categoryPath: nullableString,
  updatedAt: z.string(),
  version: z.number().int(),
  /** How many of the five basics (category, title, a photo, a price, a description) the draft still lacks; 0 = ready. */
  todo: z.number().int().min(0)
});
export type ProductDraftSummary = z.infer<typeof ProductDraftSummarySchema>;
export const ProductDraftListSchema = z.object({ items: z.array(ProductDraftSummarySchema), total: z.number().int(), page: z.number().int(), size: z.number().int() });
export type ProductDraftList = z.infer<typeof ProductDraftListSchema>;
export const SavedProductDraftSchema = ProductDraftSummarySchema.extend({ payload: z.record(z.string(), z.unknown()) });
export type SavedProductDraft = z.infer<typeof SavedProductDraftSchema>;
export interface SaveProductDraftRequest {
  productId?: string;
  title?: string;
  categoryPath?: string;
  payload: Record<string, unknown>;
  version?: number;
}

export const ListingContextSchema = z.object({
  aiListingWriter: z.object({ available: z.boolean(), reason: z.enum(['PLAN', 'ROLE']).nullable() }),
  moderated: z.boolean(),
  importLimits: z.object({
    maxFileBytes: z.number().int(),
    maxRows: z.number().int(),
    maxImagesPerProduct: z.number().int(),
    maxVariantsPerProduct: z.number().int()
  })
});
export type ListingContext = z.infer<typeof ListingContextSchema>;
export interface ListingWriterRequest {
  productType: string;
  title: string;
  attributes?: Record<string, string>;
  tone?: 'warm' | 'concise' | 'premium';
}
export const ListingWriterDraftSchema = z.object({ title: z.string(), description: z.string(), bullets: z.array(z.string()) });
export type ListingWriterDraft = z.infer<typeof ListingWriterDraftSchema>;

/* ── Bulk upload (S308) ─────────────────────────────────── */

export const SpreadsheetFormatSchema = z.enum(['xlsx', 'csv']);
export type SpreadsheetFormat = z.infer<typeof SpreadsheetFormatSchema>;
export const ImportModeSchema = z.enum(['CREATE', 'UPDATE']);
export type ImportMode = z.infer<typeof ImportModeSchema>;
export const ImportStatusSchema = z.enum(['VALIDATED', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED']);
export type ImportStatus = z.infer<typeof ImportStatusSchema>;
export const ImportRowStatusSchema = z.enum(['VALID', 'WARNING', 'ERROR']);
export type ImportRowStatus = z.infer<typeof ImportRowStatusSchema>;
export const ImportRowOutcomeSchema = z.enum(['PENDING', 'CREATED', 'UPDATED', 'FAILED', 'SKIPPED']);
export type ImportRowOutcome = z.infer<typeof ImportRowOutcomeSchema>;
export const ProductImportSchema = z.object({
  id: z.string(),
  reference: z.string(),
  fileName: z.string(),
  format: z.enum(['XLSX', 'CSV']),
  mode: ImportModeSchema,
  productType: nullableString,
  status: ImportStatusSchema,
  validOnly: z.boolean(),
  totalRows: z.number().int(),
  validRows: z.number().int(),
  warningRows: z.number().int(),
  errorRows: z.number().int(),
  productCount: z.number().int(),
  processedRows: z.number().int(),
  createdProducts: z.number().int(),
  updatedProducts: z.number().int(),
  failedRows: z.number().int(),
  header: z.array(z.string()),
  fileWarnings: z.array(z.string()),
  uploadedByName: nullableString,
  createdAt: z.string(),
  startedAt: nullableString,
  finishedAt: nullableString,
  reportUrl: z.string()
});
export type ProductImport = z.infer<typeof ProductImportSchema>;
export const ProductImportPageSchema = z.object({
  items: z.array(ProductImportSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int()
});
export type ProductImportPage = z.infer<typeof ProductImportPageSchema>;
export const ImportRowMessageSchema = z.object({
  column: nullableString,
  columnIndex: z.number().int(),
  severity: z.enum(['ERROR', 'WARNING']),
  message: z.string()
});
export type ImportRowMessage = z.infer<typeof ImportRowMessageSchema>;
export const ImageSlotSchema = z.enum(['MAIN', 'PT01', 'PT02', 'PT03', 'PT04', 'PT05', 'PT06', 'PT07', 'PT08', 'SWCH']);
export type ImageSlot = z.infer<typeof ImageSlotSchema>;
/** One image of a bulk-upload row: found in the image library, a link copied at import, held, missing or a bad link. */
export const ImportRowImageSchema = z.object({
  slot: ImageSlotSchema,
  source: z.enum(['LINK', 'FILE', 'SKU']),
  ref: z.string(),
  state: z.enum(['FOUND', 'LINK', 'HELD', 'MISSING', 'BAD_LINK']),
  url: nullableString,
  message: nullableString
});
export type ImportRowImage = z.infer<typeof ImportRowImageSchema>;
/** One image of the organisation's image library with the SKUs of the listings that use it (S310). */
export const ImageLibraryItemSchema = z.object({
  assetId: z.string(),
  fileName: z.string(),
  sku: nullableString,
  slot: ImageSlotSchema.nullable(),
  status: z.enum(['READY', 'FLAGGED', 'REJECTED']),
  url: nullableString,
  width: z.number().int(),
  height: z.number().int(),
  bytes: z.number().int(),
  flags: z.array(z.string()),
  createdAt: z.string(),
  usedBy: z.array(z.string()),
  skuKnown: z.boolean(),
  /** What the image rules decided and why, by code (the same messages as the product form). */
  verdict: z.enum(['PASS', 'WARN', 'HOLD', 'REJECT']).nullish(),
  checks: z.array(z.object({ code: z.string(), level: z.enum(['INFO', 'WARN', 'HOLD', 'REJECT']), params: z.record(z.string(), z.unknown()).default({}), message: z.string() })).default([])
});
export type ImageLibraryItem = z.infer<typeof ImageLibraryItemSchema>;
export const ImageLibraryPageSchema = z.object({
  items: z.array(ImageLibraryItemSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int()
});
export type ImageLibraryPage = z.infer<typeof ImageLibraryPageSchema>;
export interface ImageLibraryQuery {
  q?: string;
  status?: ImageLibraryItem['status'];
  matched?: boolean;
  page: number;
  size: number;
}
export const AttachLibraryImagesResultSchema = z.object({
  productsUpdated: z.number().int(),
  imagesAttached: z.number().int(),
  skipped: z.array(z.object({ sku: z.string(), reason: z.string() }))
});
export type AttachLibraryImagesResult = z.infer<typeof AttachLibraryImagesResultSchema>;
/** One brand choice: OWN (typed in or used by this organisation) or CATALOG; PENDING ones await review (author only). */
export const BrandSchema = z.object({
  id: z.string(),
  name: z.string(),
  source: z.enum(['OWN', 'CATALOG']),
  status: z.enum(['APPROVED', 'PENDING']),
  ownProducts: z.number().int(),
  products: z.number().int()
});
export type Brand = z.infer<typeof BrandSchema>;
/** The brands of one category: its department heads the group ("Brands in Fashion"); never other departments' brands. */
export const BrandListSchema = z.object({ departmentId: z.string().nullable(), departmentName: z.string().nullable(), brands: z.array(BrandSchema) });
export type BrandList = z.infer<typeof BrandListSchema>;
/** Typing a brand in: the existing match (created false) or a new pending brand. */
export const CreatedBrandSchema = z.object({ brand: BrandSchema, created: z.boolean() });
export type CreatedBrand = z.infer<typeof CreatedBrandSchema>;
/** Which category (node id or code) or product type the brands are for. */
export interface BrandScope {
  category?: string;
  productType?: string;
}
/** The no-brand choices a BRAND attribute may offer (its options). */
export const UNBRANDED_REASONS = ['UNBRANDED', 'HANDMADE'] as const;
export type UnbrandedReason = (typeof UNBRANDED_REASONS)[number];
export const ProductImportRowSchema = z.object({
  rowNumber: z.number().int(),
  groupKey: z.string(),
  sku: z.string(),
  status: ImportRowStatusSchema,
  outcome: ImportRowOutcomeSchema,
  outcomeNote: nullableString,
  productId: nullableString,
  messages: z.array(ImportRowMessageSchema),
  cells: z.array(z.string()),
  images: z.array(ImportRowImageSchema).default([])
});
export type ProductImportRow = z.infer<typeof ProductImportRowSchema>;
export const ProductImportRowPageSchema = z.object({
  items: z.array(ProductImportRowSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int()
});
export type ProductImportRowPage = z.infer<typeof ProductImportRowPageSchema>;

export const UploadRequestSchema = z.object({
  purpose: z.enum(['product-image', 'storefront']),
  fileName: z.string().min(1).max(200),
  contentType: z.enum(['image/jpeg', 'image/png', 'image/webp']),
  size: z.number().int().min(1).max(8 * 1024 * 1024),
  /** product-image only: also add it to the image library (matched to SKUs by file name). */
  library: z.boolean().optional(),
  /** product-image only: what the picture is for; MAIN is judged by the strict main-image rules (default OTHER). */
  slot: z.enum(['MAIN', 'OTHER', 'SWATCH', 'LABEL']).optional(),
  /** What the AI check compares the picture with, and the product type (apparel may show a model). */
  title: z.string().max(200).optional(),
  brand: z.string().max(80).optional(),
  productTypeCode: z.string().max(80).optional()
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
  status: z.enum(['PENDING', 'READY', 'FLAGGED', 'REJECTED']),
  /** Product images: what media's image rules decided (PASS and WARN publish, HOLD waits for review, REJECT is deleted) and why, by code. */
  slot: z.enum(['MAIN', 'OTHER', 'SWATCH', 'LABEL']).nullish(),
  verdict: z.enum(['PASS', 'WARN', 'HOLD', 'REJECT']).nullish(),
  checks: z.array(z.object({ code: z.string(), level: z.enum(['INFO', 'WARN', 'HOLD', 'REJECT']), params: z.record(z.string(), z.unknown()).default({}), message: z.string() })).default([])
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
  /** Plan fees paid from sales (S298) not yet deducted from a transfer; negative while one is owed back. */
  planFeesMinor: z.number().int().default(0),
  minimumPayoutMinor: z.number().int(),
  canRequestToday: z.boolean(),
  payoutsPaused: z.boolean(),
  payoutsPausedReason: z.enum(['AUTO_PAST_DUE', 'MANUAL', 'DEAUTHORIZED', 'CHARGES_DISABLED']).nullable(),
  payoutsPausedMessage: nullableString,
  /** Payments has no Stripe Connect account for the organisation: earnings stay on hold until it is registered. */
  payoutAccountMissing: z.boolean().default(false),
  /** Held funds per order: `orderNumber` is what screens show (null when payments' id is not among the recent shipments). */
  holds: z.array(z.object({ orderId: z.string(), orderNumber: nullableString, amountMinor: z.number().int(), releaseAt: nullableString }))
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
/** A plan invoice paid from sales (S298): "Plan fee (invoice …)", deducted from the next transfers. */
export const PlanFeeDeductionSchema = z.object({
  invoiceId: z.string(),
  invoiceNumber: nullableString,
  label: z.string(),
  status: z.enum(['WITHHELD', 'DECLINED', 'RELEASED']),
  requestedMinor: z.number().int(),
  withheldMinor: z.number().int(),
  releasedMinor: z.number().int(),
  outstandingMinor: z.number().int(),
  createdAt: z.string(),
  transfers: z.array(z.object({ orderId: z.string(), orderNumber: nullableString, amountMinor: z.number().int(), at: z.string(), label: z.string() }))
});
export type PlanFeeDeduction = z.infer<typeof PlanFeeDeductionSchema>;
export const PayoutsViewSchema = z.object({
  balance: PayoutBalanceSchema,
  payouts: z.array(PayoutSchema),
  historyAvailable: z.boolean(),
  planFeeDeductions: z.array(PlanFeeDeductionSchema).default([])
});
export type PayoutsView = z.infer<typeof PayoutsViewSchema>;

/* ── Sales tax statement (tax collected on the seller's behalf, by state and month) ── */

export const SellerTaxStateRowSchema = z.object({
  jurisdiction: z.string(),
  state: z.string(),
  orders: z.number().int(),
  grossSalesMinor: z.number().int(),
  taxableSalesMinor: z.number().int(),
  taxCollectedMinor: z.number().int(),
  taxRefundedMinor: z.number().int(),
  netTaxMinor: z.number().int()
});
export type SellerTaxStateRow = z.infer<typeof SellerTaxStateRowSchema>;
export const SellerTaxStatementSchema = z.object({
  month: z.string(),
  currency: z.string(),
  rows: z.array(SellerTaxStateRowSchema),
  totals: z.object({
    orders: z.number().int(),
    grossSalesMinor: z.number().int(),
    taxableSalesMinor: z.number().int(),
    taxCollectedMinor: z.number().int(),
    taxRefundedMinor: z.number().int(),
    netTaxMinor: z.number().int()
  }),
  months: z.array(z.string())
});
export type SellerTaxStatement = z.infer<typeof SellerTaxStatementSchema>;

/* ── Fees (read-only; rates in basis points, 1250 = 12.50 %) ── */

export const SellerFeeCategorySchema = z.object({
  category: z.string(),
  label: z.string(),
  commissionBps: z.number().int(),
  /** OVERRIDE: negotiated (until negotiatedUntil); PLAN: the rate of the organisation's plan. */
  source: z.enum(['OVERRIDE', 'PLAN', 'CATEGORY', 'DEFAULT', 'RETAIL']),
  planCode: nullableString.default(null),
  planName: nullableString.default(null),
  negotiatedUntil: nullableString.default(null)
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
  changes: z.array(SellerFeeChangeSchema),
  planCode: nullableString.default(null),
  planName: nullableString.default(null),
  negotiated: z.array(SellerNegotiatedRateSchema).default([]),
  /** What service bookings pay (connection fees), apart from product commission. */
  connectionFees: SellerRateCardSchema.nullable().default(null)
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
/** What a plan allows: the live-listing cap and the capabilities it includes (a capability that is off is not offered). */
export const PlanEntitlementsSchema = z.object({
  listings: z.number().int().nullable(),
  listingsUnlimited: z.boolean(),
  aiListingWriter: z.boolean(),
  lolaTier: z.enum(['NONE', 'ESSENTIALS', 'GROWTH', 'PRO']),
  bulkUpload: z.boolean().default(false),
  imageLibrary: z.boolean().default(false),
  coupons: z.boolean().default(false)
});
export type PlanEntitlements = z.infer<typeof PlanEntitlementsSchema>;
export const PlanSchema = z.object({
  code: z.string(),
  name: z.string(),
  tagline: z.string(),
  monthlyMinor: z.number().int(),
  trialDays: z.number().int(),
  features: z.array(z.string()),
  prices: z.array(PlanPriceSchema),
  current: z.boolean(),
  /** What the plan charges, in words (free months and conditions). */
  chargeTerms: z.string().default(''),
  entitlements: PlanEntitlementsSchema.nullable().default(null)
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
export const InvoiceLineSchema = z.object({
  kind: z.enum(['PLAN', 'USAGE', 'COUPON', 'PRORATION', 'WAIVER']),
  description: z.string(),
  amountMinor: z.number().int()
});
export type InvoiceLine = z.infer<typeof InvoiceLineSchema>;
/** A plan invoice AfroTransact issued: settled by credit, then sales (if chosen), then the saved card or bank. */
export const InvoiceSchema = z.object({
  id: z.string(),
  number: z.string(),
  periodStart: nullableString,
  periodEnd: nullableString,
  subtotalMinor: z.number().int(),
  discountMinor: z.number().int(),
  amountDueMinor: z.number().int(),
  amountPaidMinor: z.number().int(),
  currency: z.string(),
  status: z.enum(['OPEN', 'PAID', 'VOID', 'UNCOLLECTIBLE']),
  createdAt: z.string(),
  paidAt: nullableString,
  nextAttemptAt: nullableString,
  lastError: nullableString,
  lines: z.array(InvoiceLineSchema),
  paidFromCreditMinor: z.number().int(),
  paidFromSalesMinor: z.number().int(),
  paidByCardMinor: z.number().int(),
  waivedMinor: z.number().int(),
  canRetry: z.boolean()
});
export type Invoice = z.infer<typeof InvoiceSchema>;
/** A condition staff put on plan charges, with the seller's progress. */
export const ChargeGateSchema = z.object({
  mode: z.enum(['START_AFTER', 'WAIVE_WHILE', 'WAIVE_UNTIL']),
  metric: z.enum(['GROSS_SALES', 'COMPLETED_ORDERS', 'COMPLETED_BOOKINGS', 'ACTIVE_LISTINGS']).nullable(),
  threshold: z.number().int().nullable(),
  progress: z.number().int(),
  until: nullableString,
  metAt: nullableString,
  label: z.string()
});
export type ChargeGate = z.infer<typeof ChargeGateSchema>;
/** How the plan is paid (S298): platform credit first, then from sales if the seller chose that, then the card. */
export const PlanFeeCollectionModeSchema = z.enum(['EARNINGS_FIRST', 'CARD']);
export type PlanFeeCollectionMode = z.infer<typeof PlanFeeCollectionModeSchema>;
export const PlanFeeCollectionViewSchema = z.object({
  /** The platform offers the choice; false hides it (every invoice is charged to the card). The seller opts in. */
  available: z.boolean(),
  mode: PlanFeeCollectionModeSchema,
  effective: PlanFeeCollectionModeSchema,
  source: z.enum(['DEFAULT', 'ONBOARDING', 'SELLER', 'STAFF']),
  platformDefault: PlanFeeCollectionModeSchema,
  /** A change applies from the invoice of this date (the next renewal). */
  appliesFromInvoiceAt: nullableString,
  appliesFromLabel: z.string(),
  inProgressInvoiceId: nullableString,
  changedAt: nullableString,
  /** Owner and finance roles may change it. */
  canChange: z.boolean()
});
export type PlanFeeCollectionView = z.infer<typeof PlanFeeCollectionViewSchema>;
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
  dunning: nullableString,
  /** Plan version (prices) the organisation pays (S292). */
  planVersion: z.number().int().default(1),
  /** Platform credit the next invoices use first (rewards, staff credits). */
  creditBalanceMinor: z.number().int().default(0),
  trialSource: z.enum(['PLAN', 'CAMPAIGN', 'OVERRIDE', 'NONE']).nullable().default(null),
  chargeGate: ChargeGateSchema.nullable().default(null),
  entitlements: PlanEntitlementsSchema.nullable().default(null)
});
export type Subscription = z.infer<typeof SubscriptionSchema>;
export const BillingViewSchema = z.object({
  subscription: SubscriptionSchema,
  plans: z.array(PlanSchema),
  paymentMethods: z.array(BillingPaymentMethodSchema),
  invoices: z.array(InvoiceSchema),
  canManage: z.boolean(),
  planFeeCollection: PlanFeeCollectionViewSchema.nullable().default(null)
});
export type BillingView = z.infer<typeof BillingViewSchema>;
export const ChangePlanResultSchema = z.object({ applied: z.enum(['IMMEDIATE_UPGRADE', 'AT_RENEWAL', 'NO_CHANGE']), billing: BillingViewSchema });
export type ChangePlanResult = z.infer<typeof ChangePlanResultSchema>;

/* Rewards (S296): what the organisation can still earn, with progress, and what it received. */
export const RewardProgressSchema = z.object({
  ruleId: z.string(),
  name: z.string(),
  description: nullableString.default(null),
  metric: z.enum(['GROSS_SALES', 'ACTIVE_LISTINGS', 'COMPLETED_ORDERS', 'COMPLETED_BOOKINGS', 'DAYS_SINCE_APPROVAL']),
  /** Cents for GROSS_SALES, a count otherwise. */
  value: z.number().int(),
  threshold: z.number().int(),
  percent: z.number().int().min(0).max(100),
  qualifies: z.boolean(),
  label: z.string(),
  rewardLabel: z.string(),
  conditionLabel: z.string(),
  endsAt: nullableString.default(null),
  /** S299: LESS_THAN is low-sales relief (no progress bar: decided once, at checkAt). */
  comparison: z.enum(['AT_LEAST', 'LESS_THAN']).default('AT_LEAST'),
  windowAnchor: z.enum(['APPROVAL', 'SUBSCRIPTION_START']).default('APPROVAL'),
  /** The window's length: the first N days. */
  windowDays: z.number().int().nullable().default(null),
  /** Decided once at window end and the check is still to come. */
  pending: z.boolean().default(false),
  checkAt: nullableString.default(null),
  windowClosesAt: nullableString.default(null)
});
export type RewardProgress = z.infer<typeof RewardProgressSchema>;
export const EarnedRewardSchema = z.object({
  id: z.string(),
  source: z.enum(['RULE', 'MANUAL']),
  type: z.enum(['FREE_DAYS', 'FREE_MONTHS', 'CREDIT', 'PERCENT_OFF', 'WAIVE_INVOICE', 'TRIAL_END', 'CHARGE_GATE']),
  summary: z.string(),
  reason: nullableString.default(null),
  status: z.enum(['PENDING', 'APPLIED', 'FAILED']),
  creditMinor: z.number().int().nullable().default(null),
  freeDays: z.number().int().nullable().default(null),
  createdAt: z.string(),
  appliedAt: nullableString.default(null),
  /** The reward rule's comparison: LESS_THAN is low-sales relief (S299). */
  comparison: z.enum(['AT_LEAST', 'LESS_THAN']).nullable().default(null),
  /** Free days: the new trial end, when the card is charged next. */
  nextChargeAt: nullableString.default(null)
});
export type EarnedReward = z.infer<typeof EarnedRewardSchema>;
export const SellerRewardsViewSchema = z.object({ available: z.array(RewardProgressSchema), earned: z.array(EarnedRewardSchema) });
export type SellerRewardsView = z.infer<typeof SellerRewardsViewSchema>;

/** GET /v1/seller/billing/plan/preview: what a plan or cadence change would do; nothing changes. */
export const BillingPlanChangePreviewSchema = z.object({
  change: z.enum(['IMMEDIATE_UPGRADE', 'AT_RENEWAL', 'NO_CHANGE']),
  planCode: z.string(),
  planName: z.string(),
  cadence: CadenceSchema,
  effectiveAt: nullableString.default(null),
  /** Invoiced now: the proration of an upgrade (0 in a trial or for a change at renewal). */
  amountDueNowMinor: z.number().int(),
  nextChargeMinor: z.number().int(),
  nextChargeAt: nullableString.default(null),
  trialing: z.boolean()
});
export type BillingPlanChangePreview = z.infer<typeof BillingPlanChangePreviewSchema>;

/** POST /v1/seller/billing/setup-intents: confirm with the Payment Element; `localTest` when no Stripe key is configured. */
export const BillingSetupIntentSchema = z.object({ setupIntentId: z.string(), clientSecret: z.string(), publishableKey: z.string(), localTest: z.boolean() });
export type BillingSetupIntent = z.infer<typeof BillingSetupIntentSchema>;

/** POST /v1/seller/billing/payment-methods: the method the SetupIntent confirmed (display fields only). */
export interface AddBillingPaymentMethodInput {
  stripePaymentMethodId: string;
  kind: 'CARD' | 'US_BANK_ACCOUNT';
  brand?: string;
  last4: string;
  expMonth?: number;
  expYear?: number;
  bankName?: string;
  accountType?: string;
  makeDefault?: boolean;
}

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
  allowedActions: z.array(BookingActionSchema),
  serviceCategory: nullableString.default(null),
  /** Booking requests: "You keep $X after the 8% connection fee". */
  takeHome: BookingTakeHomeSchema.nullable().default(null)
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
  takeHome: TakeHomeSchema,
  category: z.string().default(''),
  delivery: ServiceDeliverySchema.default('AT_PROVIDER'),
  imageUrl: z.string().default('')
});
export type ServiceOffer = z.infer<typeof ServiceOfferSchema>;
export const ServiceInputSchema = z
  .object({
    name: z.string().trim().min(3, '3–120 characters').max(120, '3–120 characters'),
    description: z.string().max(2000),
    durationMinutes: z.number().int().min(5, 'At least 5 minutes').max(1440, 'At most 24 hours'),
    priceMinor: z.number().int().min(1, 'Price must be above zero'),
    depositMinor: z.number().int().min(0),
    active: z.boolean(),
    /** One of the staff-set service categories (the service-area view lists them). */
    category: z.string().max(60).optional(),
    delivery: ServiceDeliverySchema.optional(),
    imageUrl: z.string().max(500).optional()
  })
  .refine((s) => s.depositMinor <= s.priceMinor, { path: ['depositMinor'], message: 'Deposit must be between 0 and the price' });
export type ServiceInput = z.infer<typeof ServiceInputSchema>;

/* ── Service area (where a provider works) ───────────────────────── */

export const ServiceAreaCitySchema = z.object({ city: z.string(), state: z.string() });
export type ServiceAreaCity = z.infer<typeof ServiceAreaCitySchema>;
export const ServiceAreaNamedSchema = z.object({ kind: z.enum(['CITY', 'ZIP', 'ZONE']), value: z.string(), state: z.string().optional() });
export type ServiceAreaNamed = z.infer<typeof ServiceAreaNamedSchema>;
export const ServiceAreaRadiusSchema = z.object({ defaultMiles: z.number().int(), minMiles: z.number().int(), maxMiles: z.number().int() });
export type ServiceAreaRadius = z.infer<typeof ServiceAreaRadiusSchema>;
export const ServiceAreaViewSchema = z.object({
  area: z
    .object({
      baseZip: z.string(),
      baseCity: z.string(),
      baseState: z.string(),
      radiusMiles: z.number().int(),
      extraAreas: z.array(ServiceAreaNamedSchema),
      servedCities: z.array(ServiceAreaCitySchema),
      zone: z.string().optional()
    })
    .nullable(),
  /** How far this provider may travel to customers: the bounds for their service categories (staff-set). */
  bounds: ServiceAreaRadiusSchema,
  zones: z.array(z.object({ key: z.string(), name: z.string(), cities: z.array(ServiceAreaCitySchema) })),
  categories: z.array(z.object({ category: z.string(), rule: ServiceAreaRadiusSchema }))
});
export type ServiceAreaView = z.infer<typeof ServiceAreaViewSchema>;
export const ServiceAreaInputSchema = z.object({
  baseZip: z.string().regex(/^\d{5}$/, 'Enter the 5-digit ZIP code of your base location'),
  radiusMiles: z.number().int().min(1).max(150).nullable().optional(),
  extraAreas: z.array(ServiceAreaNamedSchema).max(20).optional()
});
export type ServiceAreaInput = z.infer<typeof ServiceAreaInputSchema>;
export const ServiceAreaPreviewSchema = z.object({ baseCity: z.string(), baseState: z.string(), zone: z.string().optional(), cities: z.array(ServiceAreaCitySchema) });
export type ServiceAreaPreview = z.infer<typeof ServiceAreaPreviewSchema>;

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
  providerFees: ProviderFeesSchema,
  /** The provider's connection fee now, per service category, with its source. */
  connectionFee: SellerRateCardSchema.nullable().default(null)
});
export type Earnings = z.infer<typeof EarningsSchema>;

/* ── Lola for sellers (plan tiers) ─────────────────────── */

export const AssistantTierSchema = z.enum(['ESSENTIALS', 'GROWTH', 'PRO']);
export type AssistantTier = z.infer<typeof AssistantTierSchema>;
export const AssistantStatusSchema = z.object({
  tier: AssistantTierSchema,
  limits: z.object({
    label: z.string(),
    dailyMessages: z.number().int(),
    maxToolRounds: z.number().int(),
    history: z.number().int(),
    tools: z.array(z.string()),
    canPropose: z.boolean()
  }),
  used: z.number().int(),
  remaining: z.number().int(),
  upgrade: z.object({ planCode: z.string(), label: z.string(), gains: z.string() }).nullable()
});
export type AssistantStatus = z.infer<typeof AssistantStatusSchema>;
/** A change Lola prepared; nothing happens until the seller taps it. */
export const AssistantProposalSchema = z.object({
  kind: z.enum(['SET_STOCK', 'NEXT_STEP']),
  label: z.string(),
  sku: z.string().optional(),
  quantity: z.number().int().optional(),
  orderNumber: z.string().optional(),
  shipmentId: z.string().optional(),
  step: z.enum(['CONFIRMED', 'PACKED', 'READY']).optional()
});
export type AssistantProposal = z.infer<typeof AssistantProposalSchema>;
export const AssistantReplySchema = z.object({
  answer: z.string(),
  steps: z.array(z.object({ tool: z.string(), label: z.string() })),
  proposals: z.array(AssistantProposalSchema),
  status: AssistantStatusSchema
});
export type AssistantReply = z.infer<typeof AssistantReplySchema>;
export interface AssistantChatRequest {
  message: string;
  history?: { role: 'user' | 'assistant'; content: string }[];
  language?: string;
}
