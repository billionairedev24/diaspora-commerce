import { z } from 'zod';

import { AccessLevelSchema, ChartSpecSchema, KpiSchema } from './seller';

/* Zod schemas for contracts/openapi/gateway-bff-staff.yaml (staff console, the platform back office). Every response
   is validated at runtime; test/staff-contract.test.ts keeps them assignable to the generated contract types. */

const nullableString = z.string().nullable();

/* ── Shell ─────────────────────────────────────────────── */

export const StaffModuleKeySchema = z.enum([
  'dash', 'exports', 'orders', 'catalog', 'inventory', 'retail', 'dispatch', 'apps', 'support', 'rbac', 'payouts', 'fees', 'marketing', 'disputes',
  'audit', 'markets', 'siteCfg', 'dev'
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

export const StaffDashboardSchema = z.object({
  rangeDays: z.number().int(),
  generatedAt: z.string(),
  kpis: z.array(KpiSchema),
  charts: z.array(ChartSpecSchema)
});
export type StaffDashboard = z.infer<typeof StaffDashboardSchema>;

/* ── Resources (server-driven tables and detail sheets) ── */

export const ResourceKeySchema = z.enum([
  'users', 'roles', 'applications', 'orders', 'products', 'moderation', 'claims', 'returns', 'payouts', 'inventory', 'purchase-orders', 'routes',
  'support-cases', 'audit', 'markets', 'site-settings', 'legal-documents', 'announcements', 'placements', 'identity-providers', 'fee-schedules', 'tax-rates'
]);
export type ResourceKey = z.infer<typeof ResourceKeySchema>;

export const RecordToneSchema = z.enum(['good', 'warn', 'bad', 'info', 'brand']);
export type RecordTone = z.infer<typeof RecordToneSchema>;
export const CellValueSchema = z.union([z.string(), z.number(), z.null()]);
export type CellValue = z.infer<typeof CellValueSchema>;
export const ColumnKindSchema = z.enum(['text', 'strong', 'mono', 'money', 'number', 'date', 'datetime', 'status', 'percent']);
export type ColumnKind = z.infer<typeof ColumnKindSchema>;
export const ResourceColumnSchema = z.object({ key: z.string(), label: z.string(), kind: ColumnKindSchema, width: z.number().int().min(1).max(100) });
export type ResourceColumn = z.infer<typeof ResourceColumnSchema>;
export const ResourceFacetSchema = z.object({ value: z.string(), label: z.string() });
export type ResourceFacet = z.infer<typeof ResourceFacetSchema>;
export const ResourceRowSchema = z.object({ id: z.string(), cells: z.record(z.string(), CellValueSchema), tone: RecordToneSchema.nullable() });
export type ResourceRow = z.infer<typeof ResourceRowSchema>;

export const FieldValueSchema = z.union([z.string(), z.number(), z.boolean(), z.array(z.string()), z.null()]);
export type FieldValue = z.infer<typeof FieldValueSchema>;
export const FieldTypeSchema = z.enum(['text', 'textarea', 'number', 'money', 'select', 'multiselect', 'toggle', 'date', 'datetime', 'email', 'password']);
export type FieldType = z.infer<typeof FieldTypeSchema>;
export const FormFieldSchema = z.object({
  key: z.string(),
  label: z.string(),
  type: FieldTypeSchema,
  required: z.boolean(),
  options: z.array(z.object({ value: z.string(), label: z.string() })).optional(),
  min: z.number().optional(),
  max: z.number().optional(),
  maxLength: z.number().int().optional(),
  pattern: z.string().optional(),
  help: z.string().optional(),
  placeholder: z.string().optional(),
  value: FieldValueSchema.optional()
});
export type FormField = z.infer<typeof FormFieldSchema>;
export const StaffFormSchema = z.object({ title: z.string(), submitLabel: z.string(), fields: z.array(FormFieldSchema) });
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
  exportable: z.boolean()
});
export type ResourcePage = z.infer<typeof ResourcePageSchema>;

export const DetailFieldSchema = z.object({
  label: z.string(),
  value: z.string(),
  kind: z.enum(['text', 'mono', 'money', 'date', 'datetime', 'status', 'link']),
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
export const RecordLinkSchema = z.object({ label: z.string(), href: z.string() });
export type RecordLink = z.infer<typeof RecordLinkSchema>;

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
  version: nullableString
});
export type RecordDetail = z.infer<typeof RecordDetailSchema>;

export type RecordValues = Record<string, FieldValue>;

/* ── Exports ───────────────────────────────────────────── */

export const ExportFormatSchema = z.enum(['csv', 'xlsx']);
export type ExportFormat = z.infer<typeof ExportFormatSchema>;
export const ExportStatusSchema = z.enum(['QUEUED', 'RUNNING', 'READY', 'FAILED', 'EXPIRED']);
export type ExportStatus = z.infer<typeof ExportStatusSchema>;
export const ExportJobSchema = z.object({
  id: z.string(),
  resource: ResourceKeySchema,
  format: ExportFormatSchema,
  status: ExportStatusSchema,
  rows: z.number().int(),
  async: z.boolean(),
  fileName: z.string(),
  createdAt: z.string(),
  finishedAt: nullableString,
  expiresAt: nullableString,
  downloadUrl: nullableString,
  error: nullableString
});
export type ExportJob = z.infer<typeof ExportJobSchema>;
export const ExportJobListSchema = z.object({ items: z.array(ExportJobSchema) });
export type ExportJobList = z.infer<typeof ExportJobListSchema>;
export interface ExportRequest {
  resource: ResourceKey;
  format: ExportFormat;
  q?: string;
  status?: string;
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
  maxOverrides: z.number().int()
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
  reverseOnRefundHelp: z.string()
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
  note: string;
}
