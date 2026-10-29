import { z } from 'zod';

import { type FeeConnectionDraft, FeeConnectionFeeSchema, type FeeDraftPlanRate, FeePlanRateSchema, FeePlanSchema } from '../fee-rates';
import { type ExportDataset, type ExportFormat, type TableExportQuery } from './exports';
import { AccessLevelSchema, ChartSpecSchema, KpiSchema } from './seller';

/* Zod schemas for contracts/openapi/gateway-bff-staff.yaml (staff console, the platform back office). Every response
   is validated at runtime; test/staff-contract.test.ts keeps them assignable to the generated contract types. */

const nullableString = z.string().nullable();

/* ── Shell ─────────────────────────────────────────────── */

export const StaffModuleKeySchema = z.enum([
  'dash', 'exports', 'orders', 'catalog', 'inventory', 'retail', 'dispatch', 'orgs', 'apps', 'support', 'rbac', 'payouts', 'fees', 'marketing', 'disputes',
  'audit', 'markets', 'siteCfg', 'dev', 'identity', 'notifications', 'preorders', 'plans'
]);
export type StaffModuleKey = z.infer<typeof StaffModuleKeySchema>;

export const StaffNavGroupKeySchema = z.enum(['overview', 'commerce', 'partners', 'finance', 'growth', 'trust', 'settings']);
export type StaffNavGroupKey = z.infer<typeof StaffNavGroupKeySchema>;

export const StaffUserSchema = z.object({ id: z.string(), firstName: z.string(), lastName: z.string(), email: z.string(), initials: z.string() });
export type StaffUser = z.infer<typeof StaffUserSchema>;
export const StaffRoleSchema = z.object({ key: z.string(), label: z.string() });
export type StaffRole = z.infer<typeof StaffRoleSchema>;

export const StaffNavModuleSchema = z.object({
  key: StaffModuleKeySchema,
  label: z.string(),
  description: z.string(),
  level: AccessLevelSchema,
  badge: z.number().int().nullable()
});
export type StaffNavModule = z.infer<typeof StaffNavModuleSchema>;
export const StaffNavGroupSchema = z.object({ key: StaffNavGroupKeySchema, label: z.string(), modules: z.array(StaffNavModuleSchema) });
export type StaffNavGroup = z.infer<typeof StaffNavGroupSchema>;

export const StaffSessionSchema = z.object({
  user: StaffUserSchema,
  roles: z.array(StaffRoleSchema),
  roleLabel: z.string(),
  permissions: z.array(z.string()),
  navGroups: z.array(StaffNavGroupSchema),
  landing: StaffModuleKeySchema,
  accessNote: z.string(),
  refundLimitMinor: z.number().int().nullable()
});
export type StaffSession = z.infer<typeof StaffSessionSchema>;

/* ── Dashboard ─────────────────────────────────────────── */

/** Lola in the staff console: read-only, one level for every staff member. */
export const StaffAssistantStatusSchema = z.object({
  dailyMessages: z.number().int(),
  used: z.number().int(),
  remaining: z.number().int(),
  tools: z.array(z.string())
});
export type StaffAssistantStatus = z.infer<typeof StaffAssistantStatusSchema>;
export const StaffAssistantReplySchema = z.object({
  answer: z.string(),
  steps: z.array(z.object({ tool: z.string(), label: z.string() })),
  /** Records she points at; the console opens one when the member taps its button. */
  links: z.array(z.object({ label: z.string(), resource: z.string(), id: z.string() })),
  status: StaffAssistantStatusSchema
});
export type StaffAssistantReply = z.infer<typeof StaffAssistantReplySchema>;
export interface StaffAssistantChatRequest {
  message: string;
  history?: { role: 'user' | 'assistant'; content: string }[];
  language?: string;
}

export const StaffDashboardSchema = z.object({
  rangeDays: z.number().int(),
  generatedAt: z.string(),
  kpis: z.array(KpiSchema),
  charts: z.array(ChartSpecSchema)
});
export type StaffDashboard = z.infer<typeof StaffDashboardSchema>;

/* ── Resources (server-driven tables and detail sheets) ── */

export const ResourceKeySchema = z.enum([
  'users', 'roles', 'applications', 'organisations', 'connect-accounts', 'orders', 'products', 'category-requests', 'moderation', 'product-reviews', 'claims', 'returns', 'payouts', 'inventory', 'purchase-orders', 'routes',
  'support-cases', 'audit', 'markets', 'site-settings', 'legal-documents', 'help-articles', 'privacy-requests', 'announcements', 'placements', 'identity-providers', 'fee-schedules', 'tax-rates', 'tax-codes', 'tax-rules', 'tax-review',
  'dispatch-shipments', 'sos-alerts', 'vehicle-defects', 'driver-events', 'pickup-locations', 'coupons',
  'reconciliation', 'reconciliation-runs', 'ledger-adjustments', 'recoveries', 'credit-adjustments', 'gift-cards', 'credit-liabilities', 'money-settings', 'membership-tiers', 'memberships', 'membership-settings', 'membership-report', 'referrals', 'referral-settings', 'stripe-lookup', 'payment-settings', 'tax-settings', 'maintenance', 'tax-filings', 'tax-nexus',
  'oauth-clients', 'identity-sessions', 'signing-keys', 'auth-settings', 'exchange-rates'
]);
export type ResourceKey = z.infer<typeof ResourceKeySchema>;

export const RecordToneSchema = z.enum(['good', 'warn', 'bad', 'info', 'brand']);
export type RecordTone = z.infer<typeof RecordToneSchema>;
export const CellValueSchema = z.union([z.string(), z.number(), z.null()]);
export type CellValue = z.infer<typeof CellValueSchema>;
export const ColumnKindSchema = z.enum(['text', 'strong', 'mono', 'money', 'number', 'date', 'datetime', 'status', 'percent']);
export type ColumnKind = z.infer<typeof ColumnKindSchema>;
export const ResourceColumnSchema = z.object({ key: z.string(), label: z.string(), kind: ColumnKindSchema, width: z.number().int().min(1).max(100), sortable: z.boolean().optional() });
export type ResourceColumn = z.infer<typeof ResourceColumnSchema>;
export const ResourceFacetSchema = z.object({ value: z.string(), label: z.string() });
export type ResourceFacet = z.infer<typeof ResourceFacetSchema>;
export const ResourceRowSchema = z.object({ id: z.string(), cells: z.record(z.string(), CellValueSchema), tone: RecordToneSchema.nullable() });
export type ResourceRow = z.infer<typeof ResourceRowSchema>;

export const FieldValueSchema = z.union([z.string(), z.number(), z.boolean(), z.array(z.string()), z.null()]);
export type FieldValue = z.infer<typeof FieldValueSchema>;
export const FieldTypeSchema = z.enum(['text', 'textarea', 'number', 'money', 'select', 'multiselect', 'toggle', 'date', 'datetime', 'email', 'password']);
export type FieldType = z.infer<typeof FieldTypeSchema>;
/** The searchable pickers of forms: staff choose a driver or a seller by name, the form sends the id. */
export const LOOKUP_KINDS = ['drivers', 'sellers'] as const;
export const LookupKindSchema = z.enum(LOOKUP_KINDS);
export type LookupKind = z.infer<typeof LookupKindSchema>;
export const LookupResultSchema = z.object({ kind: LookupKindSchema, items: z.array(z.object({ id: z.string(), name: z.string(), detail: z.string().optional() })) });
export type LookupResult = z.infer<typeof LookupResultSchema>;
export type LookupHit = LookupResult['items'][number];

export const FormFieldSchema = z.object({
  key: z.string(),
  label: z.string(),
  type: FieldTypeSchema,
  required: z.boolean(),
  /** A text field that is a picker (GET /v1/staff/lookups/{kind}). */
  lookup: LookupKindSchema.optional(),
  options: z.array(z.object({ value: z.string(), label: z.string() })).optional(),
  min: z.number().optional(),
  max: z.number().optional(),
  maxLength: z.number().int().optional(),
  pattern: z.string().optional(),
  help: z.string().optional(),
  placeholder: z.string().optional(),
  value: FieldValueSchema.optional(),
  /** The group the field belongs to: the form shows a heading when it changes from one field to the next. */
  section: z.string().optional()
});
export type FormField = z.infer<typeof FormFieldSchema>;
export const StaffFormSchema = z.object({ title: z.string(), submitLabel: z.string(), intro: z.string().optional(), fields: z.array(FormFieldSchema), changeKeys: z.array(z.string()).optional() });
export type StaffForm = z.infer<typeof StaffFormSchema>;

export const ResourcePageSchema = z.object({
  resource: ResourceKeySchema,
  title: z.string(),
  columns: z.array(ResourceColumnSchema),
  rows: z.array(ResourceRowSchema),
  page: z.number().int(),
  size: z.number().int(),
  total: z.number().int().nullable(),
  nextCursor: nullableString,
  hasNext: z.boolean(),
  facets: z.array(ResourceFacetSchema),
  searchable: z.boolean(),
  searchPlaceholder: z.string().optional(),
  canCreate: z.boolean(),
  createForm: StaffFormSchema.nullable(),
  exportable: z.boolean(),
  /** The applied sort (`<column>,asc|desc`); empty for the upstream order. */
  sort: z.array(z.string()).default([]),
  /** The search or sort covered the first 5,000 rows of a bigger table. */
  partial: z.boolean().default(false)
});
export type ResourcePage = z.infer<typeof ResourcePageSchema>;

export const DetailFieldSchema = z.object({
  label: z.string(),
  value: z.string(),
  kind: z.enum(['text', 'mono', 'money', 'date', 'datetime', 'status', 'link', 'image']),
  href: z.string().optional()
});
export type DetailField = z.infer<typeof DetailFieldSchema>;
export const DetailSectionSchema = z.object({ title: z.string(), fields: z.array(DetailFieldSchema) });
export type DetailSection = z.infer<typeof DetailSectionSchema>;
export const DetailTableSchema = z.object({ title: z.string(), columns: z.array(ResourceColumnSchema), rows: z.array(ResourceRowSchema) });
export type DetailTable = z.infer<typeof DetailTableSchema>;
export const ActivityEntrySchema = z.object({ at: z.string(), actor: z.string(), text: z.string() });
export type ActivityEntry = z.infer<typeof ActivityEntrySchema>;
export const RecordNoteSchema = z.object({ id: z.string(), at: z.string(), author: z.string(), text: z.string() });
export type RecordNote = z.infer<typeof RecordNoteSchema>;
export const RecordActionSchema = z.object({
  key: z.string(),
  label: z.string(),
  tone: z.enum(['primary', 'neutral', 'danger']),
  allowed: z.boolean(),
  reason: nullableString,
  permission: z.string(),
  confirm: nullableString,
  form: StaffFormSchema.nullable()
});
export type RecordAction = z.infer<typeof RecordActionSchema>;
export const RecordLinkSchema = z.object({ label: z.string(), href: z.string(), external: z.boolean().optional() });
export type RecordLink = z.infer<typeof RecordLinkSchema>;

/** A secret shown once, right after it was created (a new OAuth client secret): never cached, stored or logged. */
export const OneTimeSecretSchema = z.object({ label: z.string(), value: z.string(), note: z.string() });
export type OneTimeSecret = z.infer<typeof OneTimeSecretSchema>;

export const RecordDetailSchema = z.object({
  resource: ResourceKeySchema,
  id: z.string(),
  title: z.string(),
  subtitle: z.string(),
  status: nullableString,
  tone: RecordToneSchema.nullable(),
  sections: z.array(DetailSectionSchema),
  tables: z.array(DetailTableSchema),
  activity: z.array(ActivityEntrySchema),
  notes: z.array(RecordNoteSchema),
  notesEnabled: z.boolean(),
  actions: z.array(RecordActionSchema),
  editForm: StaffFormSchema.nullable(),
  links: z.array(RecordLinkSchema),
  version: nullableString,
  removed: z.boolean().optional(),
  reveal: OneTimeSecretSchema.optional()
});
export type RecordDetail = z.infer<typeof RecordDetailSchema>;

export type RecordValues = Record<string, FieldValue>;

/* ── Identity & access (S291): token inspector and published documents ─────────────── */

export const IdentityTokenInspectionSchema = z.object({
  wellFormed: z.boolean(),
  header: z.record(z.string(), z.unknown()).default({}),
  claims: z.record(z.string(), z.unknown()).default({}),
  scopes: z.array(z.string()).default([]),
  keyId: nullableString.optional(),
  issuedAt: nullableString.optional(),
  expiresAt: nullableString.optional(),
  expired: z.boolean().nullable().optional(),
  signatureValid: z.boolean(),
  issuerValid: z.boolean(),
  valid: z.boolean(),
  problems: z.array(z.string())
});
export type IdentityTokenInspection = z.infer<typeof IdentityTokenInspectionSchema>;

export const IdentityDocumentsSchema = z.object({ discovery: z.record(z.string(), z.unknown()), jwks: z.record(z.string(), z.unknown()) });
export type IdentityDocuments = z.infer<typeof IdentityDocumentsSchema>;

/* ── Exports (shared schemas in ./exports) ─────────────────── */

/** POST /v1/staff/exports: a resource (or `table` + `parent`) with the table's search, sort, columns and selection, or a client-side dataset. */
export interface ExportRequest extends Partial<TableExportQuery> {
  format: ExportFormat;
  resource?: ResourceKey;
  table?: 'preorder-orders';
  parent?: string;
  dataset?: ExportDataset;
}

/* ── Support console ───────────────────────────────────── */

export const CustomerHitSchema = z.object({ id: z.string(), name: z.string(), email: z.string(), enabled: z.boolean(), principalType: z.string() });
export type CustomerHit = z.infer<typeof CustomerHitSchema>;
export const CustomerSearchSchema = z.object({ items: z.array(CustomerHitSchema) });
export type CustomerSearch = z.infer<typeof CustomerSearchSchema>;

export const SupportShipmentSchema = z.object({
  id: z.string(),
  label: z.string(),
  status: z.string(),
  sellerName: z.string(),
  carrier: nullableString,
  trackingNumber: nullableString,
  cancellable: z.boolean(),
  totalMinor: z.number().int()
});
export type SupportShipment = z.infer<typeof SupportShipmentSchema>;
export const SupportOrderSchema = z.object({
  id: z.string(),
  orderNumber: z.string(),
  status: z.string(),
  placedAt: z.string(),
  totalMinor: z.number().int(),
  currency: z.string(),
  shipments: z.array(SupportShipmentSchema)
});
export type SupportOrder = z.infer<typeof SupportOrderSchema>;
export const SupportClaimSchema = z.object({
  id: z.string(),
  reference: z.string(),
  type: z.string(),
  status: z.string(),
  orderNumber: z.string(),
  amountMinor: z.number().int(),
  refundMinor: z.number().int().nullable(),
  open: z.boolean()
});
export type SupportClaim = z.infer<typeof SupportClaimSchema>;
export const SupportReturnSchema = z.object({
  id: z.string(),
  number: z.string(),
  status: z.string(),
  orderNumber: z.string(),
  refundState: nullableString,
  estimateMinor: z.number().int()
});
export type SupportReturn = z.infer<typeof SupportReturnSchema>;
export const SupportNotificationSchema = z.object({
  id: z.string(),
  channel: z.string(),
  templateKey: z.string(),
  subject: nullableString,
  status: z.string(),
  address: z.string(),
  createdAt: z.string()
});
export type SupportNotification = z.infer<typeof SupportNotificationSchema>;
export const SupportCaseSummarySchema = z.object({ id: z.string(), reference: z.string(), reason: z.string(), summary: z.string(), status: z.string(), openedAt: z.string() });
export type SupportCaseSummary = z.infer<typeof SupportCaseSummarySchema>;
export const SupportBookingSchema = z.object({ id: z.string(), service: z.string(), status: z.string(), startsAt: z.string() });
export type SupportBooking = z.infer<typeof SupportBookingSchema>;
export const AuditLineSchema = z.object({ at: z.string(), actor: z.string(), action: z.string(), summary: z.string() });
export type AuditLine = z.infer<typeof AuditLineSchema>;

export const Customer360Schema = z.object({
  customer: z.object({ id: z.string(), name: z.string(), email: z.string(), enabled: z.boolean(), emailVerified: z.boolean(), roles: z.array(z.string()) }),
  /** The customer's membership (S323): priority support; null when not a member. */
  membership: z.object({ tierName: z.string(), status: z.enum(['TRIALING', 'ACTIVE', 'PAST_DUE']), prioritySupport: z.boolean() }).nullable(),
  orders: z.array(SupportOrderSchema),
  bookings: z.array(SupportBookingSchema),
  claims: z.array(SupportClaimSchema),
  returns: z.array(SupportReturnSchema),
  notifications: z.array(SupportNotificationSchema),
  cases: z.array(SupportCaseSummarySchema),
  audit: z.array(AuditLineSchema),
  refundLimitMinor: z.number().int().nullable(),
  can: z.object({ refund: z.boolean(), resend: z.boolean(), cancelShipment: z.boolean() }),
  unavailable: z.array(z.string())
});
export type Customer360 = z.infer<typeof Customer360Schema>;

export const SupportActionResultSchema = z.object({ ok: z.boolean(), message: z.string(), reference: nullableString, audited: z.boolean() });
export type SupportActionResult = z.infer<typeof SupportActionResultSchema>;

/** Refund request typed by the support agent (validated in the browser before it is sent). */
export const SupportRefundFormSchema = z
  .object({
    via: z.enum(['CLAIM', 'ORDER']),
    orderNumber: z.string().min(1, 'Choose an order'),
    claimId: z.string().optional(),
    amountMinor: z.number().int().min(1, 'Enter an amount above zero'),
    reason: z.string().trim().min(3, 'Give a reason (3 characters or more)').max(500, 'Keep the reason under 500 characters'),
    caseId: z.string().optional()
  })
  .refine((v) => v.via !== 'CLAIM' || !!v.claimId, { message: 'Choose the claim to refund', path: ['claimId'] });
export type SupportRefundForm = z.infer<typeof SupportRefundFormSchema>;
export interface SupportRefundRequest extends SupportRefundForm {
  customerId: string;
}

/* ── Fees & commissions (fee-schedule editor; rates in basis points, 1250 = 12.50 %) ── */

export const FeeScheduleStatusSchema = z.enum(['ACTIVE', 'SCHEDULED', 'SUPERSEDED', 'CANCELLED']);
export type FeeScheduleStatus = z.infer<typeof FeeScheduleStatusSchema>;
export const FeeLimitsSchema = z.object({
  commissionMaxBps: z.number().int(),
  cardPctMaxBps: z.number().int(),
  cardFixedMaxMinor: z.number().int(),
  fbaPerUnitMaxMinor: z.number().int(),
  noteMinLength: z.number().int(),
  noteMaxLength: z.number().int(),
  maxCategoryRates: z.number().int(),
  maxOverrides: z.number().int(),
  deliveryMaxRadiusMiles: z.number(),
  deliveryAmountMaxMinor: z.number().int(),
  deliveryMaxBands: z.number().int(),
  maxDeliveryOverrides: z.number().int()
});
export type FeeLimits = z.infer<typeof FeeLimitsSchema>;
export const FeeCategorySchema = z.object({ key: z.string(), label: z.string(), source: z.enum(['TAXONOMY', 'SCHEDULE', 'PLATFORM']) });
export type FeeCategory = z.infer<typeof FeeCategorySchema>;
export const FeeCategoryRateSchema = z.object({ category: z.string(), label: z.string(), commissionBps: z.number().int() });
export type FeeCategoryRate = z.infer<typeof FeeCategoryRateSchema>;
export const FeeOverrideSchema = z.object({
  orgId: z.string(),
  orgName: nullableString,
  category: nullableString,
  categoryLabel: z.string(),
  commissionBps: z.number().int()
});
export type FeeOverride = z.infer<typeof FeeOverrideSchema>;

/* AfroTransact Delivery from the seller's store (S255): amounts in minor units, distances in road miles. */
export const DeliveryTariffModeSchema = z.enum(['FLAT', 'DISTANCE', 'PER_WEIGHT']);
export type DeliveryTariffMode = z.infer<typeof DeliveryTariffModeSchema>;
export const DeliveryBandSchema = z.object({ fromMiles: z.number(), toMiles: z.number(), feeMinor: z.number().int() });
export type DeliveryBand = z.infer<typeof DeliveryBandSchema>;
const nullableInt = z.number().int().nullable();
export const DeliveryTariffSchema = z.object({
  mode: DeliveryTariffModeSchema,
  flatPerPackageMinor: nullableInt,
  baseMinor: nullableInt,
  perMileMinor: nullableInt,
  perLbMinor: nullableInt.optional(),
  bands: z.array(DeliveryBandSchema),
  minMinor: nullableInt,
  maxMinor: nullableInt
});
export type DeliveryTariff = z.infer<typeof DeliveryTariffSchema>;
export const DeliveryFeeOverrideSchema = z.object({ orgId: z.string(), orgName: nullableString, sellerFee: DeliveryTariffSchema });
export type DeliveryFeeOverride = z.infer<typeof DeliveryFeeOverrideSchema>;
export const DeliveryPricingSchema = z.object({
  enabled: z.boolean(),
  maxRadiusMiles: z.number(),
  customerCharge: DeliveryTariffSchema.nullable(),
  sellerFee: DeliveryTariffSchema.nullable(),
  sellerOverrides: z.array(DeliveryFeeOverrideSchema)
});
export type DeliveryPricing = z.infer<typeof DeliveryPricingSchema>;
/** The delivery section of a draft (null on the draft = carried over from the latest live version). */
export interface DeliveryPricingDraft {
  enabled: boolean;
  maxRadiusMiles: number | null;
  customerCharge: DeliveryTariff | null;
  sellerFee: DeliveryTariff | null;
  sellerOverrides: { orgId: string; sellerFee: DeliveryTariff }[];
}
export const DeliveryUnavailableReasonSchema = z.enum(['NOT_CONFIGURED', 'BEYOND_MAX_RADIUS']);
export const DeliveryQuoteSchema = z.object({
  available: z.boolean(),
  unavailableReason: DeliveryUnavailableReasonSchema.nullable(),
  customerChargeMinor: z.number().int(),
  sellerFeeMinor: z.number().int(),
  sellerFeeSource: z.enum(['DEFAULT', 'OVERRIDE', 'RETAIL']).nullable(),
  scheduleVersion: z.number().int().nullable(),
  maxRadiusMiles: z.number().nullable(),
  currency: z.string()
});
export type DeliveryQuote = z.infer<typeof DeliveryQuoteSchema>;
export interface DeliveryQuoteRequest {
  sellerOrgId: string;
  distanceMiles: number;
  packages?: number;
  at?: string | null;
}

export const TaxRatesImportResultSchema = z.object({
  statesUpserted: z.number().int().nonnegative(),
  localsUpserted: z.number().int().nonnegative(),
  warnings: z.array(z.string())
});
export type TaxRatesImportResult = z.infer<typeof TaxRatesImportResultSchema>;

export const FeeScheduleViewSchema = z.object({
  id: z.string(),
  version: z.number().int(),
  status: FeeScheduleStatusSchema,
  effectiveFrom: z.string(),
  currency: z.string(),
  defaultCommissionBps: z.number().int(),
  categoryRates: z.array(FeeCategoryRateSchema),
  overrides: z.array(FeeOverrideSchema),
  cardPctBps: z.number().int(),
  cardFixedMinor: z.number().int(),
  fbaPerUnitMinor: z.number().int(),
  reverseCommissionOnRefund: z.boolean(),
  deliveryPricing: DeliveryPricingSchema.nullable(),
  /** Product commission per plan, after negotiated rates and before category rates. */
  planRates: z.array(FeePlanRateSchema).default([]),
  /** FBA fee per unit of the plans that have their own; the others pay fbaPerUnitMinor. */
  planFba: z.array(z.object({ planCode: z.string(), planName: z.string(), feeMinor: z.number().int() })).default([]),
  /** Connection fees on service bookings; null for versions created before they existed. */
  connectionFee: FeeConnectionFeeSchema.nullable().default(null),
  note: nullableString,
  createdBy: z.string(),
  createdAt: nullableString,
  cancelledAt: nullableString,
  cancelledBy: nullableString,
  cancelReason: nullableString,
  cancellable: z.boolean()
});
export type FeeScheduleView = z.infer<typeof FeeScheduleViewSchema>;
export const FeeRetailSchema = z.object({ orgId: z.string(), name: z.string(), note: z.string() });
export type FeeRetail = z.infer<typeof FeeRetailSchema>;
export const FeesOverviewSchema = z.object({
  generatedAt: z.string(),
  currency: z.string(),
  canConfigure: z.boolean(),
  current: FeeScheduleViewSchema.nullable(),
  base: FeeScheduleViewSchema.nullable(),
  minEffectiveFrom: z.string(),
  immediateAllowed: z.boolean(),
  schedules: z.array(FeeScheduleViewSchema),
  categories: z.array(FeeCategorySchema),
  taxonomyAvailable: z.boolean(),
  orgNamesAvailable: z.boolean(),
  retail: FeeRetailSchema,
  limits: FeeLimitsSchema,
  exampleSaleMinor: z.number().int(),
  reverseOnRefundHelp: z.string(),
  /** The plan catalogue with what each plan advertises against the configured rates. */
  plans: z.array(FeePlanSchema).default([]),
  plansAvailable: z.boolean().default(false),
  planWarnings: z.array(z.string()).default([]),
  serviceCategories: z.array(FeeCategorySchema).default([])
});
export type FeesOverview = z.infer<typeof FeesOverviewSchema>;
export const FeeOrgHitSchema = z.object({
  orgId: z.string(),
  name: z.string(),
  kind: z.string(),
  status: z.string(),
  retail: z.boolean(),
  selectable: z.boolean(),
  reason: nullableString
});
export type FeeOrgHit = z.infer<typeof FeeOrgHitSchema>;
export const FeeOrgSearchSchema = z.object({ items: z.array(FeeOrgHitSchema) });
export type FeeOrgSearch = z.infer<typeof FeeOrgSearchSchema>;

/** POST /v1/staff/fees/schedules body (the editor converts its percentages to basis points). */
export interface FeeScheduleDraft {
  effectiveFrom: string | null;
  immediate: boolean;
  defaultCommissionBps: number;
  categoryRates: { category: string; commissionBps: number }[];
  overrides: { orgId: string; category: string | null; commissionBps: number }[];
  cardPctBps: number;
  cardFixedMinor: number;
  fbaPerUnitMinor: number;
  reverseCommissionOnRefund: boolean;
  /** Omitted or null = carried over from the latest live version. */
  deliveryPricing?: DeliveryPricingDraft | null;
  /** Omitted or null = carried over from the latest live version; [] = no plan rates. */
  planRates?: FeeDraftPlanRate[] | null;
  /** FBA fee per unit per plan; plans left out pay fbaPerUnitMinor. Omitted or null = carried over; [] = none. */
  planFba?: { planCode: string; feeMinor: number }[] | null;
  /** Omitted or null = carried over from the latest live version. */
  connectionFee?: FeeConnectionDraft | null;
  note: string;
}

/* ── Dispatch: seller pickups of AfroTransact Delivery from the seller's store (S255) ── */

export const PickupRowSchema = z.object({
  shipmentId: z.string(),
  orderNumber: z.string(),
  sellerOrgId: z.string(),
  sellerName: z.string(),
  city: z.string(),
  state: z.string(),
  postalCode: z.string(),
  status: z.string(),
  routeId: nullableString,
  perishable: z.boolean(),
  units: z.number().int(),
  promisedBy: nullableString,
  createdAt: nullableString
});
export type PickupRow = z.infer<typeof PickupRowSchema>;
export const PickupListSchema = z.object({ items: z.array(PickupRowSchema), canAssign: z.boolean() });
export type PickupList = z.infer<typeof PickupListSchema>;
export const DriverSuggestionSchema = z.object({
  driverId: z.string(),
  driverName: z.string(),
  routeId: z.string(),
  routeName: z.string(),
  zone: z.string(),
  routeStatus: z.string(),
  straightLineMiles: z.number(),
  roadMiles: z.number().nullable(),
  durationMinutes: z.number().nullable(),
  distanceMethod: z.string(),
  positionAt: nullableString,
  packagesOnRoute: z.number().int(),
  capacityLeft: z.number().int()
});
export type DriverSuggestion = z.infer<typeof DriverSuggestionSchema>;
export const DriverSuggestionsSchema = z.object({ shipmentId: z.string(), drivers: z.array(DriverSuggestionSchema), canAssign: z.boolean() });
export type DriverSuggestions = z.infer<typeof DriverSuggestionsSchema>;
export interface PickupAssignRequest {
  routeId: string;
  reason: string;
}
export const PickupAssignResultSchema = z.object({
  shipmentId: z.string(),
  routeId: z.string(),
  routeName: nullableString,
  driverId: nullableString,
  driverName: nullableString,
  status: z.string()
});
export type PickupAssignResult = z.infer<typeof PickupAssignResultSchema>;
export const PickupAssignmentSchema = z.object({
  id: z.string(),
  shipmentId: z.string(),
  routeId: z.string(),
  routeName: nullableString,
  driverId: nullableString,
  driverName: nullableString,
  mode: z.enum(['AUTO_NEAREST', 'DISPATCH_OVERRIDE']),
  reason: nullableString,
  previousRouteId: nullableString,
  previousRouteName: nullableString,
  straightMiles: z.number().nullable(),
  roadMiles: z.number().nullable(),
  distanceMethod: nullableString,
  actor: nullableString,
  createdAt: nullableString
});
export type PickupAssignment = z.infer<typeof PickupAssignmentSchema>;
export const PickupAssignmentListSchema = z.object({ items: z.array(PickupAssignmentSchema) });
export type PickupAssignmentList = z.infer<typeof PickupAssignmentListSchema>;

/* ── Dispatch: delivery runs (S264) — a closed pre-order window's paid orders, planned into routes ── */

export const RUN_DISPATCH_STATUSES = ['PENDING', 'PLANNED', 'CANCELLED'] as const;
export const RunDispatchStatusSchema = z.enum(RUN_DISPATCH_STATUSES);
export type RunDispatchStatus = z.infer<typeof RunDispatchStatusSchema>;
/** The run's lifecycle in delivery-runs, in the order a run moves through it. */
export const RUN_LIFECYCLE = ['CONFIRMED', 'COLLECTING', 'OUT_FOR_DELIVERY', 'COMPLETED'] as const;
export const RunLifecycleSchema = z.enum([...RUN_LIFECYCLE, 'CANCELLED']);
export type RunLifecycle = z.infer<typeof RunLifecycleSchema>;
export const RunPickupPointSchema = z.object({
  name: z.string(),
  line1: z.string(),
  city: z.string(),
  state: z.string(),
  postalCode: z.string(),
  hours: z.string(),
  notes: z.string()
});
export type RunPickupPoint = z.infer<typeof RunPickupPointSchema>;
export const RunDispatchProgressSchema = z.object({
  orders: z.number().int(),
  bags: z.number().int(),
  pickupStops: z.number().int(),
  pickupsDone: z.number().int(),
  bagsCollected: z.number().int(),
  dropStops: z.number().int(),
  dropsDone: z.number().int(),
  dropsFailed: z.number().int(),
  bagsDelivered: z.number().int(),
  unrouted: z.number().int()
});
export type RunDispatchProgress = z.infer<typeof RunDispatchProgressSchema>;
export const RunOrderProgressSchema = z.object({
  orders: z.number().int(),
  pending: z.number().int(),
  collected: z.number().int(),
  outForDelivery: z.number().int(),
  failed: z.number().int(),
  delivered: z.number().int(),
  returned: z.number().int(),
  lastDeliveredAt: nullableString
});
export type RunOrderProgress = z.infer<typeof RunOrderProgressSchema>;
export const RunDispatchStopSchema = z.object({
  id: z.string(),
  sequence: z.number().int(),
  kind: z.enum(['PICKUP', 'DROPOFF']),
  name: z.string(),
  status: z.enum(['PENDING', 'ARRIVED', 'DONE', 'FAILED', 'RETRY', 'SKIPPED', 'REASSIGNED', 'CANCELLED']),
  orderNumber: nullableString,
  addressLine: nullableString,
  handoverRequired: z.boolean(),
  note: nullableString
});
export type RunDispatchStop = z.infer<typeof RunDispatchStopSchema>;
export const RunDispatchRouteSchema = z.object({
  id: z.string(),
  name: z.string(),
  status: z.enum(['PLANNED', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED']),
  driverId: nullableString,
  driverName: nullableString,
  vehicleId: nullableString,
  blocked: z.boolean(),
  packages: z.number().int(),
  bagsCollected: z.number().int(),
  bagsDelivered: z.number().int(),
  pickupStops: z.number().int(),
  pickupsDone: z.number().int(),
  dropStops: z.number().int(),
  dropsDone: z.number().int(),
  dropsFailed: z.number().int(),
  stops: z.array(RunDispatchStopSchema)
});
export type RunDispatchRoute = z.infer<typeof RunDispatchRouteSchema>;
export const RunDispatchSchema = z.object({
  runId: z.string(),
  town: z.string(),
  state: z.string(),
  timeZone: z.string(),
  serviceDate: z.string(),
  windowStart: z.string(),
  windowEnd: z.string(),
  sellerPickupAt: nullableString,
  dropMode: z.enum(['DOORSTEP', 'PICKUP_POINT']),
  pickupPoint: RunPickupPointSchema.nullable(),
  status: RunDispatchStatusSchema,
  runStatus: RunLifecycleSchema.nullable(),
  planError: nullableString,
  plannedAt: nullableString,
  progress: RunDispatchProgressSchema,
  orders: RunOrderProgressSchema.nullable(),
  routes: z.array(RunDispatchRouteSchema),
  canManage: z.boolean()
});
export type RunDispatch = z.infer<typeof RunDispatchSchema>;
export const RunDispatchListSchema = z.object({ items: z.array(RunDispatchSchema), canManage: z.boolean() });
export type RunDispatchList = z.infer<typeof RunDispatchListSchema>;
export type RunAdvanceStep = Exclude<(typeof RUN_LIFECYCLE)[number], 'CONFIRMED'>;
export interface RunAdvanceRequest {
  to: RunAdvanceStep;
}
export const StaffPickupWindowSchema = z.object({
  day: z.enum(['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']),
  opens: z.string(),
  closes: z.string()
});
export const ShipFromLocationSchema = z.object({
  id: z.string(),
  orgId: z.string(),
  label: z.string(),
  line1: z.string(),
  line2: z.string(),
  city: z.string(),
  state: z.string(),
  postalCode: z.string(),
  country: z.string(),
  position: z.object({ lat: z.number(), lng: z.number(), precision: z.enum(['ADDRESS', 'ZIP', 'POINT']) }).nullable(),
  pickupEnabled: z.boolean(),
  pickupHours: z.array(StaffPickupWindowSchema),
  zoneName: nullableString
});
export type ShipFromLocation = z.infer<typeof ShipFromLocationSchema>;

/* ── Notifications: templates (S268) ─────────────────────── */

export const NotificationChannelSchema = z.enum(['email', 'sms', 'push', 'chat']);
export type NotificationChannel = z.infer<typeof NotificationChannelSchema>;
export const NotificationCategorySchema = z.enum(['security', 'transactional', 'reminder', 'marketing']);
export type NotificationCategory = z.infer<typeof NotificationCategorySchema>;

export const NotificationTemplateSummarySchema = z.object({
  key: z.string(),
  category: NotificationCategorySchema,
  channels: z.array(NotificationChannelSchema),
  locales: z.array(z.string()),
  latestVersion: z.number().int(),
  updatedBy: nullableString,
  updatedAt: nullableString
});
export type NotificationTemplateSummary = z.infer<typeof NotificationTemplateSummarySchema>;
export const NotificationTemplateListSchema = z.object({ items: z.array(NotificationTemplateSummarySchema), total: z.number().int(), canEdit: z.boolean() });
export type NotificationTemplateList = z.infer<typeof NotificationTemplateListSchema>;

export const NotificationTemplateVersionSchema = z.object({
  locale: z.string(),
  channel: NotificationChannelSchema,
  version: z.number().int(),
  category: NotificationCategorySchema,
  subject: z.string(),
  bodyHtml: z.string(),
  bodyText: z.string(),
  createdBy: nullableString,
  createdAt: nullableString
});
export type NotificationTemplateVersion = z.infer<typeof NotificationTemplateVersionSchema>;
export const NotificationTemplateVariantSchema = z.object({
  locale: z.string(),
  channel: NotificationChannelSchema,
  latestVersion: z.number().int(),
  versions: z.array(NotificationTemplateVersionSchema)
});
export type NotificationTemplateVariant = z.infer<typeof NotificationTemplateVariantSchema>;
export const NotificationSampleSchema = z.object({
  source: z.enum(['event', 'request', 'sample', 'none']),
  area: z.enum(['account', 'orders', 'seller', 'payouts', 'bookings', 'trust', 'retail', 'marketing', 'security', 'other']),
  eventTypes: z.array(z.string()),
  data: z.record(z.string(), z.unknown())
});
export type NotificationSample = z.infer<typeof NotificationSampleSchema>;
export const NotificationTemplateDetailSchema = z.object({
  key: z.string(),
  category: NotificationCategorySchema,
  variants: z.array(NotificationTemplateVariantSchema),
  sample: NotificationSampleSchema,
  canEdit: z.boolean(),
  canTestSend: z.boolean()
});
export type NotificationTemplateDetail = z.infer<typeof NotificationTemplateDetailSchema>;

export interface NotificationDraft {
  category?: NotificationCategory;
  subject?: string;
  bodyHtml?: string;
  bodyText: string;
}
export interface NotificationPreviewRequest {
  channel: NotificationChannel;
  locale?: string;
  version?: number;
  data?: Record<string, unknown>;
  draft?: NotificationDraft;
}
export const SmsSegmentsSchema = z.object({
  encoding: z.enum(['GSM-7', 'UCS-2']),
  characters: z.number().int(),
  units: z.number().int(),
  segments: z.number().int(),
  perSegment: z.number().int()
});
export type SmsSegments = z.infer<typeof SmsSegmentsSchema>;
export const NotificationPreviewSchema = z.object({
  subject: z.string(),
  html: z.string(),
  text: z.string(),
  version: z.number().int(),
  category: NotificationCategorySchema,
  sms: SmsSegmentsSchema.nullable()
});
export type NotificationPreview = z.infer<typeof NotificationPreviewSchema>;
export interface NotificationVersionDraft {
  locale: string;
  channel: NotificationChannel;
  category: NotificationCategory;
  subject?: string;
  bodyHtml?: string;
  bodyText: string;
}
export const NotificationTestSendResultSchema = z.object({ to: z.string(), channel: NotificationChannelSchema, provider: nullableString });
export type NotificationTestSendResult = z.infer<typeof NotificationTestSendResultSchema>;
