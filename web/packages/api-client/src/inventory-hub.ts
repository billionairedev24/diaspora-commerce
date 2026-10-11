/* Inventory hub, AfroTransact Retail (1P) and Seller Center FBA (S281): the BFF routes that mirror inventory-service
   (/v1/staff/inventory/**, /v1/seller/fba/**) and retail-service (/v1/staff/retail/**) — contracts/openapi/
   gateway-bff-staff.yaml (tags Inventory hub, Retail (1P)) and gateway-bff-seller.yaml (tag Inventory & FBA) — their Zod
   schemas, TanStack Query key factories and queryOptions, and the rules the consoles and the mock BFF share (expiry
   required for perishables, whole cases, over-receipt tolerance, FEFO order, expiry buckets). Money is in minor units;
   dates are ISO-8601 (calendar dates for lots). */
import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type ApiClientOptions, joinUrl, type Requester } from './http';

const enc = encodeURIComponent;
const date = z.string().nullish();
const str = z.string().nullish();
const int = z.number().int();
const pageOf = <T extends z.ZodType>(item: T) =>
  z.object({ items: z.array(item), total: z.number().int().default(0), page: z.number().int().default(0), size: z.number().int().default(25) });

/* ── Inventory ───────────────────────────────────────────── */

export const NodeSchema = z.object({ id: z.string(), name: z.string(), kind: str, country: str, timezone: str, temperatureZones: z.array(z.string()).nullish(), active: z.boolean().default(true) });
export type InventoryNode = z.infer<typeof NodeSchema>;
export const NodeListSchema = z.object({ items: z.array(NodeSchema) });

export const InboundStatusSchema = z.enum(['WORKING', 'SHIPPED', 'RECEIVING', 'CLOSED', 'CANCELLED']);
export type InboundStatus = z.infer<typeof InboundStatusSchema>;

export const InboundLineSchema = z.object({
  lineNo: int,
  sku: z.string(),
  atin: str,
  unitLabel: str,
  expectedQty: int,
  receivedGood: int.default(0),
  receivedDamaged: int.default(0),
  shortQty: int.default(0),
  overQty: int.default(0),
  inboundOpen: int.default(0),
  casePack: int.nullish(),
  cases: int.default(0),
  lotNo: str,
  mfgDate: date,
  expDate: date
});
export type InboundLine = z.infer<typeof InboundLineSchema>;

export const InboundBoxSchema = z.object({
  boxNo: int,
  lpn: z.string(),
  contents: z.array(z.object({ sku: z.string(), qty: int })).default([]),
  units: int.default(0),
  weightLb: z.number().nullish(),
  receivedAt: str
});
export type InboundBox = z.infer<typeof InboundBoxSchema>;

export const InboundAddressSchema = z.object({ name: str, line1: str, line2: str, city: str, region: str, postalCode: str, country: str, phone: str });

export const InboundShipmentSchema = z.object({
  id: z.string(),
  number: z.string(),
  source: z.enum(['FBA', 'RETAIL_ASN']).default('FBA'),
  externalRef: str,
  ownerOrg: str,
  nodeId: z.string(),
  assignmentReason: str,
  name: str,
  status: InboundStatusSchema,
  shipFrom: InboundAddressSchema.nullish(),
  shipTo: InboundAddressSchema.nullish(),
  carrier: str,
  trackingNumber: str,
  labelDocumentUrl: str,
  expectedArrival: date,
  expectedUnits: int.default(0),
  receivedUnits: int.default(0),
  receiptCount: int.default(0),
  discrepancy: z.boolean().default(false),
  discrepancies: z.array(z.object({ type: z.string(), sku: str, expectedQty: int.nullish(), receivedQty: int.nullish(), qty: int.nullish() }).loose()).default([]),
  lines: z.array(InboundLineSchema),
  boxes: z.array(InboundBoxSchema).default([]),
  createdBy: str,
  createdAt: str,
  shippedAt: str,
  closedAt: str,
  updatedAt: str,
  version: z.number().int().default(0)
});
export type InboundShipment = z.infer<typeof InboundShipmentSchema>;
export const InboundShipmentPageSchema = pageOf(InboundShipmentSchema);
export type InboundShipmentPage = z.infer<typeof InboundShipmentPageSchema>;

export const LotStatusSchema = z.enum(['SELLABLE', 'QUARANTINE', 'EXPIRED', 'WRITTEN_OFF', 'DAMAGED']);
export type LotStatus = z.infer<typeof LotStatusSchema>;

export const LotSchema = z.object({
  id: z.string(),
  sku: z.string(),
  lotNo: z.string(),
  nodeId: z.string(),
  ownerOrg: str,
  bin: str,
  qtyOnHand: int,
  qtyReserved: int.default(0),
  qtyAvailable: int.default(0),
  mfgDate: date,
  expDate: date,
  receivedAt: str,
  unitLandedCostMinor: int.default(0),
  currency: z.string().default('USD'),
  status: LotStatusSchema,
  reason: str,
  markdownFlag: z.enum(['NONE', 'WITHIN_60', 'WITHIN_30']).default('NONE'),
  receiptRef: str,
  version: z.number().int().default(0)
});
export type Lot = z.infer<typeof LotSchema>;
export const LotPageSchema = pageOf(LotSchema);
export type LotPage = z.infer<typeof LotPageSchema>;
export const LotListSchema = z.object({ items: z.array(LotSchema) });

export const MovementSchema = z.object({
  id: z.number(),
  occurredAt: z.string(),
  type: z.string(),
  sku: z.string(),
  nodeId: z.string(),
  ownerOrg: str,
  lotId: str,
  lotNo: str,
  qty: int,
  reference: str,
  actor: str,
  reason: str
});
export type Movement = z.infer<typeof MovementSchema>;
export const MovementPageSchema = pageOf(MovementSchema);

export const LotTraceSchema = z.object({
  lot: LotSchema,
  movements: z.array(MovementSchema).default([]),
  relatedLots: z.array(LotSchema).default([]),
  orders: z.array(z.object({ orderId: z.string(), qty: int.nullish(), status: str, at: str }).loose()).default([])
});
export type LotTrace = z.infer<typeof LotTraceSchema>;

export const ReceiptResultSchema = z.object({ shipment: InboundShipmentSchema, receiptRef: str, lots: z.array(LotSchema).default([]), replayed: z.boolean().default(false) });
export type InboundReceiptResult = z.infer<typeof ReceiptResultSchema>;
export const TrackingResultSchema = z.object({ shipment: InboundShipmentSchema, trackingRegistered: z.boolean().default(false) });

export const CycleCountReasonSchema = z.enum(['FOUND', 'LOST', 'DAMAGED', 'MISPICK', 'RECEIVING_ERROR', 'THEFT', 'DATA_ENTRY', 'OTHER']);
export type CycleCountReason = z.infer<typeof CycleCountReasonSchema>;
export const CYCLE_COUNT_REASONS: { value: CycleCountReason; label: string }[] = [
  { value: 'FOUND', label: 'Found (wrong bin)' },
  { value: 'LOST', label: 'Lost / missing' },
  { value: 'DAMAGED', label: 'Damaged in storage' },
  { value: 'MISPICK', label: 'Earlier mis-pick' },
  { value: 'RECEIVING_ERROR', label: 'Receiving error' },
  { value: 'THEFT', label: 'Suspected theft' },
  { value: 'DATA_ENTRY', label: 'Data entry' },
  { value: 'OTHER', label: 'Other' }
];

export const CycleCountLineSchema = z.object({
  lineNo: int,
  lotId: z.string(),
  sku: z.string(),
  lotNo: z.string(),
  bin: str,
  expDate: date,
  expectedQty: int,
  countedQty: int.nullish(),
  variance: int.default(0),
  reasonCode: CycleCountReasonSchema.nullish(),
  note: str,
  countedBy: str,
  countedAt: str,
  postedDelta: int.nullish()
});
export type CycleCountLine = z.infer<typeof CycleCountLineSchema>;

export const CycleCountSchema = z.object({
  id: z.string(),
  number: z.string(),
  nodeId: z.string(),
  sku: str,
  binPrefix: str,
  ownerOrg: str,
  blind: z.boolean().default(false),
  status: z.enum(['OPEN', 'COUNTED', 'POSTED', 'CANCELLED']),
  note: str,
  lineCount: int.default(0),
  countedLines: int.default(0),
  varianceLines: int.default(0),
  netVariance: int.default(0),
  lines: z.array(CycleCountLineSchema),
  createdBy: str,
  createdAt: str,
  postedBy: str,
  postedAt: str,
  version: z.number().int().default(0)
});
export type CycleCount = z.infer<typeof CycleCountSchema>;
export const CycleCountPageSchema = pageOf(CycleCountSchema);

export const OverviewSchema = z.object({
  byStatus: z.array(z.object({ status: z.string(), currency: z.string(), units: int, lots: int, valueMinor: int })).default([]),
  expiry: z.array(z.object({ bucket: z.string(), units: int, lots: int })).default([]),
  nodes: z.array(z.object({ nodeId: z.string(), name: str, lots: int, units: int, sellableUnits: int, blockedUnits: int, valueMinor: int, currency: str, reserved: int, inbound: int }).loose()).default([]),
  inbound: z.object({ openShipments: int, expectedUnits: int, receivedUnits: int, withDiscrepancies: int }).nullish(),
  reservations: z.object({ held: int, heldUnits: int }).nullish(),
  alerts: z.array(z.object({ type: z.string(), severity: str, sku: str, nodeId: str, lotId: str, lotNo: str, shipmentId: str, qty: int.nullish(), date: date, message: z.string() })).default([])
});
export type InventoryOverview = z.infer<typeof OverviewSchema>;

export const MarkdownRunSchema = z.object({ items: z.array(LotSchema) });
export const ExpiryRunSchema = z.object({ expired: int.default(0), deferred: int.default(0) }).loose();

export const FlowStageSchema = z.object({
  key: z.string(),
  title: z.string(),
  who: z.string(),
  what: z.string(),
  effect: z.string(),
  count: z.number().int().nullable(),
  countLabel: z.string(),
  href: z.string(),
  unavailable: z.string().optional()
});
export type FlowStage = z.infer<typeof FlowStageSchema>;
export const InventoryFlowSchema = z.object({ stages: z.array(FlowStageSchema), asOf: z.string() });
export type InventoryFlow = z.infer<typeof InventoryFlowSchema>;

export const TraceStepSchema = z.object({ key: z.string(), title: z.string(), status: z.enum(['DONE', 'WAITING', 'BROKEN']), detail: z.string(), href: z.string().optional() });
export type TraceStep = z.infer<typeof TraceStepSchema>;
export const InventoryTraceSchema = z.object({
  atin: z.string().optional(),
  sku: z.string().optional(),
  title: z.string().optional(),
  steps: z.array(TraceStepSchema).default([]),
  availableUnits: z.number().int().nullable(),
  storefrontPath: z.string().optional(),
  badges: z.array(z.string()).default([]),
  deliveryDays: z.number().int().nullable()
});
export type InventoryTrace = z.infer<typeof InventoryTraceSchema>;

export const FbaInventorySchema = z.object({
  items: z.array(z.object({ sku: z.string(), atin: str, nodeId: z.string(), onHand: int, reserved: int, available: int, inbound: int, unfulfillable: int.default(0), stranded: z.boolean().default(false) }).loose()).default([]),
  total: int.default(0)
}).loose();

/* ── Retail (1P) ─────────────────────────────────────────── */

export const SupplierSchema = z.object({
  id: z.string(),
  code: z.string(),
  name: z.string(),
  country: str,
  city: str,
  currency: z.string(),
  paymentTerms: str,
  leadTimeDays: int.default(0),
  status: z.string(),
  canReceivePurchaseOrders: z.boolean().default(true),
  scorecard: z.object({ receipts: int, onTimePercent: z.number().nullish(), defectPercent: z.number().nullish() }).loose().nullish(),
  version: z.number().int().default(0)
});
export type Supplier = z.infer<typeof SupplierSchema>;
export const SupplierPageSchema = pageOf(SupplierSchema);

export const RetailItemSchema = z.object({
  id: z.string(),
  sku: z.string(),
  gtin: z.string(),
  gtin14: str,
  name: z.string(),
  category: str,
  atin: str,
  primarySupplierId: str,
  supplierItems: z.array(z.object({ supplierId: z.string(), supplierSku: z.string(), casePack: int, minOrderQty: int.nullish(), unitCostMinor: int, currency: z.string() })).default([]),
  shelfLifeDays: int.default(0),
  storage: z.string().default('AMBIENT'),
  reorderPoint: int.default(0),
  packSize: str,
  casePack: int.default(1),
  priceMinor: int.default(0),
  currency: z.string().default('USD'),
  listingStatus: z.enum(['DRAFT', 'LIVE', 'PAUSED']),
  version: z.number().int().default(0)
});
export type RetailItem = z.infer<typeof RetailItemSchema>;
export const RetailItemPageSchema = pageOf(RetailItemSchema);

export const PoStatusSchema = z.enum(['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SENT', 'CONFIRMED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED']);
export const PO_STATUSES = PoStatusSchema.options;

export const PoLineSchema = z.object({
  lineNo: int,
  itemId: z.string(),
  sku: z.string(),
  description: z.string(),
  supplierSku: str,
  qtyOrdered: int,
  unitCostMinor: int,
  amountMinor: int,
  qtyReceived: int.default(0),
  qtyDamaged: int.default(0),
  qtyInvoiced: int.default(0),
  openQty: int.default(0)
});
export type PoLine = z.infer<typeof PoLineSchema>;

export const PurchaseOrderSchema = z.object({
  id: z.string(),
  number: z.string(),
  supplierId: z.string(),
  supplierCode: z.string(),
  currency: z.string(),
  fxRate: z.number().nullish(),
  baseCurrency: z.string().default('USD'),
  destinationNodeId: z.string(),
  incoterm: z.string(),
  orderDate: date,
  expectedDate: date,
  status: z.string(),
  approvalTier: str,
  shipmentBooked: z.boolean().default(false),
  totalMinor: int,
  totalBaseMinor: int.default(0),
  lines: z.array(PoLineSchema).default([]),
  history: z.array(z.object({ status: z.string(), at: z.string(), by: str, note: str }).loose()).default([]),
  createdBy: str,
  approvedBy: str,
  createdAt: str,
  version: z.number().int().default(0)
});
export type PurchaseOrder = z.infer<typeof PurchaseOrderSchema>;
export const PurchaseOrderPageSchema = pageOf(PurchaseOrderSchema);

export const DispatchSchema = z.object({ channel: z.string(), status: z.string(), attempts: int.default(0), lastError: str, sentAt: str }).loose();
export const DispatchListSchema = z.object({ items: z.array(DispatchSchema) });

export const RetailShipmentSchema = z.object({
  id: z.string(),
  reference: z.string(),
  poId: z.string(),
  poNumber: str,
  mode: z.string(),
  carrier: str,
  trackingRef: str,
  destinationNodeId: str,
  etd: date,
  eta: date,
  stage: z.string(),
  stageLabel: str,
  asnLocked: z.boolean().default(false),
  lines: z.array(z.object({ lineNo: int, poLineNo: int, itemId: z.string(), sku: z.string(), qty: int, lotNo: str, mfgDate: date, expDate: date })).default([]),
  cartons: z.array(z.object({ sscc: z.string(), asnLineNo: int, qty: int }).loose()).default([]),
  documents: z.array(z.object({ name: z.string(), status: z.string(), reference: str }).loose()).default([]),
  milestones: z.array(z.object({ stage: z.string(), at: z.string(), by: str })).default([]),
  version: z.number().int().default(0)
});
export type RetailShipment = z.infer<typeof RetailShipmentSchema>;
export const RetailShipmentPageSchema = pageOf(RetailShipmentSchema);

export const ReceiptLineSchema = z.object({
  lineNo: int,
  poLineNo: int,
  asnLineNo: int.nullish(),
  itemId: z.string(),
  sku: z.string(),
  expectedQty: int,
  asnLotNo: str,
  lotNo: str,
  mfgDate: date,
  expDate: date,
  qtyGood: int,
  qtyDamaged: int,
  bin: str,
  qa: z.enum(['PENDING', 'PASS', 'FAIL']).default('PENDING'),
  scanned: z.boolean().default(false),
  split: z.boolean().default(false)
});
export type ReceiptLine = z.infer<typeof ReceiptLineSchema>;

export const GoodsReceiptSchema = z.object({
  id: z.string(),
  number: z.string(),
  poId: z.string(),
  poNumber: str,
  shipmentId: str,
  shipmentRef: str,
  nodeId: z.string(),
  dock: str,
  status: z.enum(['OPEN', 'POSTING', 'COMPLETED']),
  lines: z.array(ReceiptLineSchema).default([]),
  overApprovedBy: str,
  discrepancies: z.array(z.object({ claimRef: z.string(), type: z.string(), poLineNo: int, sku: z.string(), expectedQty: int, receivedQty: int, qty: int, status: z.string(), note: str }).loose()).default([]),
  postedLots: z.array(z.object({ lotId: z.string(), sku: z.string(), lotNo: z.string(), qty: int, status: z.string(), unitLandedCostMinor: int })).default([]),
  openedBy: str,
  openedAt: str,
  completedAt: str,
  version: z.number().int().default(0)
});
export type GoodsReceipt = z.infer<typeof GoodsReceiptSchema>;
export const GoodsReceiptPageSchema = pageOf(GoodsReceiptSchema);

export const ReceiptValidationSchema = z.object({
  ok: z.boolean(),
  lines: z.array(z.object({ lineNo: int, errors: z.array(z.string()), warnings: z.array(z.string()), disposition: str, remainingDays: int.nullish() })).default([]),
  variances: z.array(z.object({ poLineNo: int, sku: z.string(), expected: int, received: int, damaged: int, overBlocked: z.boolean() })).default([]),
  receiptErrors: z.array(z.string()).default([]),
  fieldErrors: z.record(z.string(), z.string()).default({})
});
export type ReceiptValidation = z.infer<typeof ReceiptValidationSchema>;

export const SupplierInvoiceSchema = z.object({
  id: z.string(),
  poId: z.string(),
  poNumber: str,
  invoiceNumber: z.string(),
  invoiceDate: z.string(),
  currency: z.string(),
  totalMinor: int,
  lines: z.array(z.object({ poLineNo: int, qty: int, unitPriceMinor: int })).default([]),
  status: z.string(),
  match: z
    .object({
      outcome: z.string(),
      expectedTotalMinor: int,
      invoicedTotalMinor: int,
      totalVariancePercent: z.number().nullish(),
      reasons: z.array(z.string()).default([]),
      lines: z.array(z.object({ poLineNo: int }).loose()).default([])
    })
    .nullish(),
  resolutionNote: str,
  version: z.number().int().default(0)
});
export type SupplierInvoice = z.infer<typeof SupplierInvoiceSchema>;
export const InvoicePageSchema = pageOf(SupplierInvoiceSchema);

/* ── Inputs ──────────────────────────────────────────────── */

export interface InboundPlanLineInput {
  atin: string;
  sku?: string;
  qty: number;
  casePack?: number;
  lotNo?: string;
  mfgDate?: string;
  expDate?: string;
}
export interface InboundPlanInput {
  name?: string;
  nodeId?: string;
  ownerOrg?: string;
  expectedArrival?: string;
  lines: InboundPlanLineInput[];
  boxes?: { contents: { sku: string; qty: number }[]; weightLb?: number }[];
}
export interface ReceiveLineInput {
  sku?: string;
  unitLabel?: string;
  qtyGood: number;
  qtyDamaged: number;
  lotNo?: string;
  mfgDate?: string;
  expDate?: string;
  bin?: string;
  holdReason?: string;
}
export interface CountEntryInput {
  lineNo: number;
  countedQty: number;
  reasonCode?: CycleCountReason;
  note?: string;
}
export interface PoInput {
  supplierId: string;
  destinationNodeId: string;
  incoterm: string;
  expectedDate?: string;
  lines: { itemId: string; qty: number; unitCostMinor?: number }[];
}
export interface ReceiptCountLineInput {
  lineNo: number;
  poLineNo: number;
  lotNo?: string;
  mfgDate?: string;
  expDate?: string;
  qtyGood: number;
  qtyDamaged?: number;
  bin?: string;
  qa?: 'PENDING' | 'PASS' | 'FAIL';
  scanned?: boolean;
}

export interface LotQuery {
  q?: string;
  sku?: string;
  nodeId?: string;
  status?: string;
  page: number;
  size: number;
}

/* ── Rules shared by the consoles and the mock BFF ───────── */

/** Catalog product types that are food (catalog Product.FOOD_PRODUCT_TYPES). */
export const FOOD_PRODUCT_TYPES = ['grocery', 'perishable'];

/**
 * Whether a batch must carry an expiry date: food and perishable product types, chilled or frozen storage, and items
 * whose shelf life is tracked (3 years or less).
 */
export function requiresExpiry(p: { productType?: string | null; storage?: string | null; shelfLifeDays?: number | null }): boolean {
  if (p.productType && FOOD_PRODUCT_TYPES.includes(p.productType.toLowerCase())) return true;
  if (p.storage && ['CHILLED', 'FROZEN'].includes(p.storage.toUpperCase())) return true;
  return typeof p.shelfLifeDays === 'number' && p.shelfLifeDays > 0 && p.shelfLifeDays <= 1095;
}

const LOT_RE = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,58}$/;
const ISO = /^\d{4}-\d{2}-\d{2}$/;

/** Field errors of an inbound line as the forms show them (keys: qty, casePack, lotNo, mfgDate, expDate). */
export function validateInboundLine(
  l: { qty: string | number; casePack?: string | number; lotNo?: string; mfgDate?: string; expDate?: string },
  opts: { expiryRequired: boolean; today: string }
): Record<string, string> {
  const e: Record<string, string> = {};
  const qty = Number(l.qty);
  if (!Number.isInteger(qty) || qty < 1 || qty > 100_000) e.qty = 'Enter 1 to 100,000 units';
  const pack = l.casePack === undefined || l.casePack === '' ? null : Number(l.casePack);
  if (pack !== null) {
    if (!Number.isInteger(pack) || pack < 1 || pack > 10_000) e.casePack = 'Units per case: 1 to 10,000';
    else if (!e.qty && qty % pack !== 0) e.qty = `Send whole cases: a multiple of ${pack}`;
  }
  if (l.lotNo && (!LOT_RE.test(l.lotNo.trim()) || /-DMG$/i.test(l.lotNo.trim()))) e.lotNo = 'Letters, digits, . _ / - (max 59)';
  if (l.mfgDate && !ISO.test(l.mfgDate)) e.mfgDate = 'Use YYYY-MM-DD';
  else if (l.mfgDate && l.mfgDate > opts.today) e.mfgDate = 'Cannot be in the future';
  if (opts.expiryRequired && !l.expDate) e.expDate = 'Expiry date is required for this product';
  else if (l.expDate && !ISO.test(l.expDate)) e.expDate = 'Use YYYY-MM-DD';
  else if (l.expDate && l.expDate <= opts.today) e.expDate = 'This batch has already expired';
  else if (l.expDate && l.mfgDate && l.expDate <= l.mfgDate) e.expDate = 'Must be after the manufacture date';
  if (opts.expiryRequired && !l.lotNo?.trim()) e.lotNo = 'Batch / lot number is required for this product';
  return e;
}

/** Over-receipt tolerance of retail goods receipts: more than 2 % over the expected quantity needs approval. */
export const OVER_RECEIPT_TOLERANCE = 0.02;

export type ReceiptVariance = { kind: 'match' | 'short' | 'over' | 'overBlocked'; diff: number };

/** Counted vs expected for one line: short, over within tolerance, or over beyond it (purchasing approval). */
export function receiptVariance(expected: number, good: number, damaged: number, tolerance = OVER_RECEIPT_TOLERANCE): ReceiptVariance {
  const diff = good + damaged - expected;
  if (diff === 0) return { kind: 'match', diff };
  if (diff < 0) return { kind: 'short', diff };
  return { kind: diff > Math.floor(expected * tolerance) ? 'overBlocked' : 'over', diff };
}

/** Field errors of a cycle count entry: whole units and a reason for every variance. */
export function validateCountEntry(expected: number, counted: string, reason: string | undefined): Record<string, string> {
  const e: Record<string, string> = {};
  const n = Number(counted);
  if (counted.trim() === '' || !Number.isInteger(n) || n < 0) e.countedQty = 'Enter the counted units (0 or more)';
  else if (n !== expected && !reason) e.reasonCode = 'Give a reason for the variance';
  return e;
}

/** Whole days from `today` to `exp` (negative once expired); null without an expiry. */
export function daysToExpiry(exp: string | null | undefined, today: string): number | null {
  if (!exp) return null;
  return Math.round((Date.parse(`${exp}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000);
}

export type ExpiryTone = 'expired' | 'within30' | 'within60' | 'ok' | 'none';

export function expiryTone(days: number | null): ExpiryTone {
  if (days === null) return 'none';
  if (days < 0) return 'expired';
  if (days <= 30) return 'within30';
  if (days <= 60) return 'within60';
  return 'ok';
}

/**
 * FEFO pick order, as inventory-service allocates reservations (DefaultFefoAllocator): sellable lots with units
 * available, earliest expiry first (lots without expiry last), then oldest receipt, then lot number.
 */
export function fefoOrder<T extends Pick<Lot, 'status' | 'qtyAvailable' | 'expDate' | 'receivedAt' | 'lotNo'>>(lots: T[]): T[] {
  return lots
    .filter((l) => l.status === 'SELLABLE' && l.qtyAvailable > 0)
    .sort(
      (a, b) =>
        (a.expDate ?? '9999-12-31').localeCompare(b.expDate ?? '9999-12-31') ||
        (a.receivedAt ?? '').localeCompare(b.receivedAt ?? '') ||
        a.lotNo.localeCompare(b.lotNo)
    );
}

/** Which lots an order of `qty` units would take, in FEFO order. */
export function fefoAllocate<T extends Pick<Lot, 'status' | 'qtyAvailable' | 'expDate' | 'receivedAt' | 'lotNo'>>(lots: T[], qty: number): { lot: T; qty: number }[] {
  const out: { lot: T; qty: number }[] = [];
  let left = qty;
  for (const l of fefoOrder(lots)) {
    if (left <= 0) break;
    const take = Math.min(left, l.qtyAvailable);
    out.push({ lot: l, qty: take });
    left -= take;
  }
  return out;
}

/** The shipment timeline (design): draft → shipped → in transit → arrived → receiving → closed. */
export const INBOUND_TIMELINE = ['Draft', 'Shipped', 'In transit', 'Arrived', 'Receiving', 'Closed'] as const;

/** How far along the timeline a shipment is (index into INBOUND_TIMELINE; -1 when cancelled). */
export function inboundStep(s: Pick<InboundShipment, 'status' | 'shippedAt' | 'expectedArrival' | 'receivedUnits'>, today: string): number {
  switch (s.status) {
    case 'CANCELLED':
      return -1;
    case 'WORKING':
      return 0;
    case 'SHIPPED': {
      const arrived = s.expectedArrival !== null && s.expectedArrival !== undefined && s.expectedArrival <= today;
      return arrived ? 3 : s.shippedAt && s.shippedAt.slice(0, 10) < today ? 2 : 1;
    }
    case 'RECEIVING':
      return 4;
    default:
      return 5;
  }
}

/* ── API ─────────────────────────────────────────────────── */

const ifMatch = (version?: number) => (version === undefined ? undefined : { 'If-Match': `"${version}"` });
const idem = (key?: string) => (key ? { 'Idempotency-Key': key } : undefined);
const qs = (o: Record<string, string | number | undefined | null>) => {
  const out: Record<string, string> = {};
  Object.entries(o).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') out[k] = String(v);
  });
  return out;
};

/** Inventory-service routes, shared by the staff hub (base /v1/staff/inventory) and Seller Center (/v1/seller/fba). */
function inventoryRoutes(request: Requester, base: string) {
  return {
    nodes: (signal?: AbortSignal) => request(`${base}/nodes`, NodeListSchema, { signal }).then((r) => r.items),
    inbound: (q: { status?: string; nodeId?: string; sku?: string; source?: string; page: number; size: number }, signal?: AbortSignal): Promise<InboundShipmentPage> =>
      request(`${base}/inbound`, InboundShipmentPageSchema, { query: qs(q), signal }),
    shipment: (id: string, signal?: AbortSignal): Promise<InboundShipment> => request(`${base}/inbound/${enc(id)}`, InboundShipmentSchema, { signal }),
    createPlan: (body: InboundPlanInput, key?: string): Promise<InboundShipment> => request(`${base}/inbound`, InboundShipmentSchema, { method: 'POST', body, headers: idem(key) }),
    updatePlan: (id: string, body: Pick<InboundPlanInput, 'lines' | 'boxes'>, version?: number): Promise<InboundShipment> =>
      request(`${base}/inbound/${enc(id)}`, InboundShipmentSchema, { method: 'PUT', body, headers: ifMatch(version) }),
    setTracking: (id: string, carrier: string, trackingNumber: string) =>
      request(`${base}/inbound/${enc(id)}/tracking`, TrackingResultSchema, { method: 'PUT', body: { carrier, trackingNumber } }).then((r) => r.shipment),
    ship: (id: string, version?: number): Promise<InboundShipment> => request(`${base}/inbound/${enc(id)}/ship`, InboundShipmentSchema, { method: 'POST', headers: ifMatch(version) }),
    cancel: (id: string): Promise<InboundShipment> => request(`${base}/inbound/${enc(id)}/cancel`, InboundShipmentSchema, { method: 'POST' }),
    lots: (q: LotQuery, signal?: AbortSignal): Promise<LotPage> =>
      request(`${base}/lots`, LotPageSchema, { query: qs({ sku: q.sku ?? (q.q ? q.q.toUpperCase() : undefined), nodeId: q.nodeId, status: q.status, page: q.page, size: q.size }), signal }),
    lot: (id: string, signal?: AbortSignal): Promise<Lot> => request(`${base}/lots/${enc(id)}`, LotSchema, { signal }),
    markdown: (within?: 'WITHIN_30' | 'WITHIN_60', signal?: AbortSignal) => request(`${base}/lots/markdown`, LotListSchema, { query: qs({ within }), signal }).then((r) => r.items)
  };
}

/** Staff console: Inventory hub and AfroTransact Retail (1P). */
export function createInventoryHubApi(request: Requester, options: ApiClientOptions) {
  const I = '/v1/staff/inventory';
  const R = '/v1/staff/retail';
  return {
    ...inventoryRoutes(request, I),
    flow: (signal?: AbortSignal): Promise<InventoryFlow> => request(`${I}/flow`, InventoryFlowSchema, { signal }),
    trace: (q: { atin?: string; sku?: string }, signal?: AbortSignal): Promise<InventoryTrace> => request(`${I}/trace`, InventoryTraceSchema, { query: qs(q), signal }),
    overview: (nodeId?: string, signal?: AbortSignal): Promise<InventoryOverview> => request(`${I}/overview`, OverviewSchema, { query: qs({ nodeId }), signal }),
    receive: (id: string, lines: ReceiveLineInput[], key: string, boxesReceived?: string[]): Promise<InboundReceiptResult> =>
      request(`${I}/inbound/${enc(id)}/receipts`, ReceiptResultSchema, { method: 'POST', body: { lines, boxesReceived }, headers: idem(key) }),
    close: (id: string): Promise<InboundShipment> => request(`${I}/inbound/${enc(id)}/close`, InboundShipmentSchema, { method: 'POST' }),
    labelsUrl: (id: string, kind: 'UNIT' | 'BOX', format: 'PDF' | 'ZPL' = 'PDF') => joinUrl(options.baseUrl, `${I}/inbound/${enc(id)}/labels`, { kind, format }),
    lotTrace: (id: string, signal?: AbortSignal): Promise<LotTrace> => request(`${I}/lots/${enc(id)}/trace`, LotTraceSchema, { signal }),
    adjustLot: (id: string, countedQty: number, reason: string, key: string): Promise<Lot> =>
      request(`${I}/lots/${enc(id)}/adjust`, LotSchema, { method: 'POST', body: { countedQty, reason }, headers: idem(key) }),
    lotAction: (id: string, action: 'quarantine' | 'release', reason: string): Promise<Lot> => request(`${I}/lots/${enc(id)}/${action}`, LotSchema, { method: 'POST', body: { reason } }),
    writeOff: (id: string, qty: number | undefined, reason: string, key: string): Promise<Lot> =>
      request(`${I}/lots/${enc(id)}/write-off`, LotSchema, { method: 'POST', body: { qty, reason }, headers: idem(key) }),
    transfer: (id: string, body: { toNodeId: string; toBin?: string; qty: number; reason: string }, key: string) =>
      request(`${I}/lots/${enc(id)}/transfer`, z.object({ reference: z.string(), from: LotSchema, to: LotSchema }), { method: 'POST', body, headers: idem(key) }),
    expiryRun: () => request(`${I}/lots/expiry-runs`, ExpiryRunSchema, { method: 'POST' }),
    markdownRun: () => request(`${I}/lots/markdown-runs`, MarkdownRunSchema, { method: 'POST' }).then((r) => r.items),
    movements: (q: { sku?: string; lotId?: string; type?: string; page: number; size: number }, signal?: AbortSignal) =>
      request(`${I}/movements`, MovementPageSchema, { query: qs(q), signal }),
    cycleCounts: (q: { status?: string; nodeId?: string; sku?: string; page: number; size: number }, signal?: AbortSignal) =>
      request(`${I}/cycle-counts`, CycleCountPageSchema, { query: qs(q), signal }),
    cycleCount: (id: string, signal?: AbortSignal): Promise<CycleCount> => request(`${I}/cycle-counts/${enc(id)}`, CycleCountSchema, { signal }),
    startCount: (body: { nodeId: string; sku?: string; binPrefix?: string; blind?: boolean; note?: string }): Promise<CycleCount> =>
      request(`${I}/cycle-counts`, CycleCountSchema, { method: 'POST', body }),
    recordCounts: (id: string, lines: CountEntryInput[], version?: number): Promise<CycleCount> =>
      request(`${I}/cycle-counts/${enc(id)}/counts`, CycleCountSchema, { method: 'PUT', body: { lines }, headers: ifMatch(version) }),
    postCount: (id: string, version?: number): Promise<CycleCount> => request(`${I}/cycle-counts/${enc(id)}/post`, CycleCountSchema, { method: 'POST', headers: ifMatch(version) }),
    cancelCount: (id: string, version?: number): Promise<CycleCount> => request(`${I}/cycle-counts/${enc(id)}/cancel`, CycleCountSchema, { method: 'POST', headers: ifMatch(version) }),

    retail: {
      suppliers: (signal?: AbortSignal) => request(`${R}/suppliers`, SupplierPageSchema, { query: { size: '200' }, signal }),
      items: (q: { q?: string; listing?: string; page: number; size: number }, signal?: AbortSignal) => request(`${R}/items`, RetailItemPageSchema, { query: qs(q), signal }),
      item: (id: string, signal?: AbortSignal): Promise<RetailItem> => request(`${R}/items/${enc(id)}`, RetailItemSchema, { signal }),
      linkAtin: (id: string, atin: string, version?: number): Promise<RetailItem> =>
        request(`${R}/items/${enc(id)}/atin`, RetailItemSchema, { method: 'PUT', body: { atin }, headers: ifMatch(version) }),
      setListing: (id: string, status: 'DRAFT' | 'LIVE' | 'PAUSED', version?: number): Promise<RetailItem> =>
        request(`${R}/items/${enc(id)}/listing`, RetailItemSchema, { method: 'PUT', body: { status }, headers: ifMatch(version) }),
      purchaseOrders: (q: { status?: string; page: number; size: number }, signal?: AbortSignal) => request(`${R}/purchase-orders`, PurchaseOrderPageSchema, { query: qs(q), signal }),
      purchaseOrder: (id: string, signal?: AbortSignal): Promise<PurchaseOrder> => request(`${R}/purchase-orders/${enc(id)}`, PurchaseOrderSchema, { signal }),
      createPo: (body: PoInput, key?: string): Promise<PurchaseOrder> => request(`${R}/purchase-orders`, PurchaseOrderSchema, { method: 'POST', body, headers: idem(key) }),
      poAction: (id: string, action: 'submit' | 'approve' | 'send-back' | 'send' | 'confirm' | 'close' | 'cancel', version?: number, body: Record<string, unknown> = {}): Promise<PurchaseOrder> =>
        request(`${R}/purchase-orders/${enc(id)}/${action}`, PurchaseOrderSchema, { method: 'POST', body, headers: ifMatch(version) }),
      dispatches: (id: string, signal?: AbortSignal) => request(`${R}/purchase-orders/${enc(id)}/dispatches`, DispatchListSchema, { signal }).then((r) => r.items),
      poDocumentUrl: (id: string, doc: 'pdf' | 'edi850') => joinUrl(options.baseUrl, `${R}/purchase-orders/${enc(id)}/${doc}`),
      /** Retail shipments of a PO (all of them when poId is empty). */
      shipments: (poId: string, signal?: AbortSignal) => request(`${R}/shipments`, RetailShipmentPageSchema, { query: qs({ poId, size: 200 }), signal }),
      bookShipment: (body: { poId: string; mode: string; carrier?: string; trackingRef?: string; etd?: string; eta?: string; lines?: { poLineNo: number; qty: number; lotNo?: string; mfgDate?: string; expDate?: string }[] }) =>
        request(`${R}/shipments`, RetailShipmentSchema, { method: 'POST', body }),
      advanceShipment: (id: string, version?: number) => request(`${R}/shipments/${enc(id)}/advance`, RetailShipmentSchema, { method: 'POST', headers: ifMatch(version) }),
      setDocument: (id: string, name: string, status: 'MISSING' | 'RECEIVED' | 'CLEARED' | 'HOLD' | string) =>
        request(`${R}/shipments/${enc(id)}/documents`, RetailShipmentSchema, { method: 'POST', body: { name, status } }),
      receipts: (q: { status?: string; poId?: string; page: number; size: number }, signal?: AbortSignal) => request(`${R}/receipts`, GoodsReceiptPageSchema, { query: qs(q), signal }),
      receipt: (id: string, signal?: AbortSignal): Promise<GoodsReceipt> => request(`${R}/receipts/${enc(id)}`, GoodsReceiptSchema, { signal }),
      openReceipt: (body: { shipmentId?: string; poId?: string; dock?: string }): Promise<GoodsReceipt> => request(`${R}/receipts`, GoodsReceiptSchema, { method: 'POST', body }),
      countReceipt: (id: string, lines: ReceiptCountLineInput[], version?: number): Promise<GoodsReceipt> =>
        request(`${R}/receipts/${enc(id)}/lines`, GoodsReceiptSchema, { method: 'PUT', body: { lines }, headers: ifMatch(version) }),
      validateReceipt: (id: string): Promise<ReceiptValidation> => request(`${R}/receipts/${enc(id)}/validate`, ReceiptValidationSchema, { method: 'POST' }),
      approveOverReceipt: (id: string, version?: number): Promise<GoodsReceipt> =>
        request(`${R}/receipts/${enc(id)}/approve-over-receipt`, GoodsReceiptSchema, { method: 'POST', headers: ifMatch(version) }),
      completeReceipt: (id: string): Promise<GoodsReceipt> => request(`${R}/receipts/${enc(id)}/complete`, GoodsReceiptSchema, { method: 'POST' }),
      invoices: (q: { status?: string; poId?: string; page: number; size: number }, signal?: AbortSignal) => request(`${R}/invoices`, InvoicePageSchema, { query: qs(q), signal }),
      registerInvoice: (body: { poId: string; invoiceNumber: string; invoiceDate: string; lines: { poLineNo: number; qty: number; unitPriceMinor: number }[] }) =>
        request(`${R}/invoices`, SupplierInvoiceSchema, { method: 'POST', body }),
      invoiceAction: (id: string, action: 'approve' | 'reject' | 'rematch', note?: string) =>
        request(`${R}/invoices/${enc(id)}/${action}`, SupplierInvoiceSchema, { method: 'POST', body: note ? { note } : {} })
    }
  };
}
export type InventoryHubApi = ReturnType<typeof createInventoryHubApi>;

/** Seller Center: Send to AfroTransact for the active organisation. */
export function createSellerFbaApi(request: Requester, options: ApiClientOptions) {
  const F = '/v1/seller/fba';
  return {
    ...inventoryRoutes(request, F),
    stock: (signal?: AbortSignal) => request(`${F}/inventory`, FbaInventorySchema, { signal }),
    labelsUrl: (id: string, kind: 'UNIT' | 'BOX', format: 'PDF' | 'ZPL' = 'PDF') => joinUrl(options.baseUrl, `${F}/inbound/${enc(id)}/labels`, { kind, format })
  };
}
export type SellerFbaApi = ReturnType<typeof createSellerFbaApi>;

/* ── Query keys and options ──────────────────────────────── */

export const inventoryHubKeys = {
  all: ['inventory-hub'] as const,
  flow: () => [...inventoryHubKeys.all, 'flow'] as const,
  trace: (q: { atin?: string; sku?: string }) => [...inventoryHubKeys.all, 'trace', q] as const,
  overview: (nodeId?: string) => [...inventoryHubKeys.all, 'overview', nodeId ?? ''] as const,
  nodes: () => [...inventoryHubKeys.all, 'nodes'] as const,
  inbound: () => [...inventoryHubKeys.all, 'inbound'] as const,
  inboundPage: (q: object) => [...inventoryHubKeys.inbound(), 'page', q] as const,
  shipment: (id: string) => [...inventoryHubKeys.inbound(), 'one', id] as const,
  lots: () => [...inventoryHubKeys.all, 'lots'] as const,
  lotPage: (q: object) => [...inventoryHubKeys.lots(), 'page', q] as const,
  lot: (id: string) => [...inventoryHubKeys.lots(), 'one', id] as const,
  lotTrace: (id: string) => [...inventoryHubKeys.lots(), 'trace', id] as const,
  markdown: (within?: string) => [...inventoryHubKeys.lots(), 'markdown', within ?? ''] as const,
  movements: (q: object) => [...inventoryHubKeys.all, 'movements', q] as const,
  counts: () => [...inventoryHubKeys.all, 'counts'] as const,
  countPage: (q: object) => [...inventoryHubKeys.counts(), 'page', q] as const,
  count: (id: string) => [...inventoryHubKeys.counts(), 'one', id] as const,
  retail: () => [...inventoryHubKeys.all, 'retail'] as const,
  suppliers: () => [...inventoryHubKeys.retail(), 'suppliers'] as const,
  items: (q: object) => [...inventoryHubKeys.retail(), 'items', q] as const,
  pos: () => [...inventoryHubKeys.retail(), 'pos'] as const,
  poPage: (q: object) => [...inventoryHubKeys.pos(), 'page', q] as const,
  po: (id: string) => [...inventoryHubKeys.pos(), 'one', id] as const,
  poShipments: (id: string) => [...inventoryHubKeys.po(id), 'shipments'] as const,
  retailShipments: () => [...inventoryHubKeys.retail(), 'shipments'] as const,
  poDispatches: (id: string) => [...inventoryHubKeys.po(id), 'dispatches'] as const,
  receipts: () => [...inventoryHubKeys.retail(), 'receipts'] as const,
  receiptPage: (q: object) => [...inventoryHubKeys.receipts(), 'page', q] as const,
  receipt: (id: string) => [...inventoryHubKeys.receipts(), 'one', id] as const,
  invoices: (q: object) => [...inventoryHubKeys.retail(), 'invoices', q] as const,
  fbaStock: () => [...inventoryHubKeys.all, 'fba-stock'] as const
};

type InvRoutes = ReturnType<typeof inventoryRoutes>;

export const inventoryHubQueries = {
  flow: (api: InventoryHubApi) => queryOptions({ queryKey: inventoryHubKeys.flow(), queryFn: ({ signal }) => api.flow(signal), staleTime: 15_000 }),
  trace: (api: InventoryHubApi, q: { atin?: string; sku?: string }) =>
    queryOptions({ queryKey: inventoryHubKeys.trace(q), queryFn: ({ signal }) => api.trace(q, signal), enabled: Boolean(q.atin || q.sku), staleTime: 5_000 }),
  overview: (api: InventoryHubApi, nodeId?: string) => queryOptions({ queryKey: inventoryHubKeys.overview(nodeId), queryFn: ({ signal }) => api.overview(nodeId, signal), staleTime: 15_000 }),
  nodes: (api: Pick<InvRoutes, 'nodes'>) => queryOptions({ queryKey: inventoryHubKeys.nodes(), queryFn: ({ signal }) => api.nodes(signal), staleTime: 300_000 }),
  inbound: (api: Pick<InvRoutes, 'inbound'>, q: Parameters<InvRoutes['inbound']>[0]) =>
    queryOptions({ queryKey: inventoryHubKeys.inboundPage(q), queryFn: ({ signal }) => api.inbound(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  shipment: (api: Pick<InvRoutes, 'shipment'>, id: string) => queryOptions({ queryKey: inventoryHubKeys.shipment(id), queryFn: ({ signal }) => api.shipment(id, signal), staleTime: 5_000 }),
  lots: (api: Pick<InvRoutes, 'lots'>, q: LotQuery) =>
    queryOptions({ queryKey: inventoryHubKeys.lotPage(q), queryFn: ({ signal }) => api.lots(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  lot: (api: Pick<InvRoutes, 'lot'>, id: string) => queryOptions({ queryKey: inventoryHubKeys.lot(id), queryFn: ({ signal }) => api.lot(id, signal), staleTime: 5_000 }),
  lotTrace: (api: InventoryHubApi, id: string) => queryOptions({ queryKey: inventoryHubKeys.lotTrace(id), queryFn: ({ signal }) => api.lotTrace(id, signal), staleTime: 5_000 }),
  markdown: (api: Pick<InvRoutes, 'markdown'>, within?: 'WITHIN_30' | 'WITHIN_60') =>
    queryOptions({ queryKey: inventoryHubKeys.markdown(within), queryFn: ({ signal }) => api.markdown(within, signal), staleTime: 15_000 }),
  movements: (api: InventoryHubApi, q: Parameters<InventoryHubApi['movements']>[0]) =>
    queryOptions({ queryKey: inventoryHubKeys.movements(q), queryFn: ({ signal }) => api.movements(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  counts: (api: InventoryHubApi, q: Parameters<InventoryHubApi['cycleCounts']>[0]) =>
    queryOptions({ queryKey: inventoryHubKeys.countPage(q), queryFn: ({ signal }) => api.cycleCounts(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  count: (api: InventoryHubApi, id: string) => queryOptions({ queryKey: inventoryHubKeys.count(id), queryFn: ({ signal }) => api.cycleCount(id, signal), staleTime: 5_000 }),
  suppliers: (api: InventoryHubApi) => queryOptions({ queryKey: inventoryHubKeys.suppliers(), queryFn: ({ signal }) => api.retail.suppliers(signal), staleTime: 60_000 }),
  items: (api: InventoryHubApi, q: Parameters<InventoryHubApi['retail']['items']>[0]) =>
    queryOptions({ queryKey: inventoryHubKeys.items(q), queryFn: ({ signal }) => api.retail.items(q, signal), placeholderData: keepPreviousData, staleTime: 15_000 }),
  pos: (api: InventoryHubApi, q: Parameters<InventoryHubApi['retail']['purchaseOrders']>[0]) =>
    queryOptions({ queryKey: inventoryHubKeys.poPage(q), queryFn: ({ signal }) => api.retail.purchaseOrders(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  po: (api: InventoryHubApi, id: string) => queryOptions({ queryKey: inventoryHubKeys.po(id), queryFn: ({ signal }) => api.retail.purchaseOrder(id, signal), staleTime: 5_000 }),
  poShipments: (api: InventoryHubApi, id: string) => queryOptions({ queryKey: inventoryHubKeys.poShipments(id), queryFn: ({ signal }) => api.retail.shipments(id, signal), staleTime: 5_000 }),
  retailShipments: (api: InventoryHubApi) =>
    queryOptions({ queryKey: inventoryHubKeys.retailShipments(), queryFn: ({ signal }) => api.retail.shipments('', signal), staleTime: 10_000 }),
  poDispatches: (api: InventoryHubApi, id: string) => queryOptions({ queryKey: inventoryHubKeys.poDispatches(id), queryFn: ({ signal }) => api.retail.dispatches(id, signal), staleTime: 10_000 }),
  receipts: (api: InventoryHubApi, q: Parameters<InventoryHubApi['retail']['receipts']>[0]) =>
    queryOptions({ queryKey: inventoryHubKeys.receiptPage(q), queryFn: ({ signal }) => api.retail.receipts(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  receipt: (api: InventoryHubApi, id: string) => queryOptions({ queryKey: inventoryHubKeys.receipt(id), queryFn: ({ signal }) => api.retail.receipt(id, signal), staleTime: 5_000 }),
  invoices: (api: InventoryHubApi, q: Parameters<InventoryHubApi['retail']['invoices']>[0]) =>
    queryOptions({ queryKey: inventoryHubKeys.invoices(q), queryFn: ({ signal }) => api.retail.invoices(q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  fbaStock: (api: SellerFbaApi) => queryOptions({ queryKey: inventoryHubKeys.fbaStock(), queryFn: ({ signal }) => api.stock(signal), staleTime: 15_000 })
};
