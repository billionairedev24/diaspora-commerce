import { z } from 'zod';

/* Zod schemas for the staff console Delivery module (contracts/openapi/gateway-bff-staff.yaml, tag Delivery;
   docs/engineering/delivery-fulfilment.md): AfroTransact staff run AfroTransact-delivered orders end to end without the
   driver app. Names and cities, never ids (shipmentId is only the handle an action is sent to). */

const nullableString = z.string().nullable();

export const DeliveryModeSchema = z.enum(['AFROTRANSACT_DELIVERY', 'FULFILLED_BY_AFROTRANSACT']);
export type DeliveryMode = z.infer<typeof DeliveryModeSchema>;

export const DeliveryStatusSchema = z.enum(['placed', 'confirmed', 'packed', 'ready', 'picked_up', 'in_transit', 'out_for_delivery', 'delivery_failed', 'delivered']);
export type DeliveryStatus = z.infer<typeof DeliveryStatusSchema>;

/** The statuses the queue shows by default (AfroTransact holds them); delivered and preparing ones are other tabs. */
export const ACTIVE_DELIVERY_STATUSES = ['ready', 'picked_up', 'in_transit', 'out_for_delivery', 'delivery_failed'] as const satisfies readonly DeliveryStatus[];

export const DeliveryRowSchema = z.object({
  shipmentId: z.string(),
  orderNumber: z.string(),
  shipmentLabel: z.string(),
  mode: DeliveryModeSchema,
  status: DeliveryStatusSchema,
  sellerName: z.string(),
  originCity: z.string(),
  customerName: z.string(),
  customerCity: z.string(),
  customerState: z.string(),
  customerPostalCode: z.string(),
  units: z.number().int(),
  perishable: z.boolean(),
  promisedBy: nullableString,
  assigneeName: nullableString,
  assigneeMe: z.boolean(),
  attempts: z.number().int(),
  version: z.number().int(),
  proofAttached: z.boolean(),
  updatedAt: nullableString,
  deliveredAt: nullableString
});
export type DeliveryRow = z.infer<typeof DeliveryRowSchema>;

export const DeliveryListSchema = z.object({
  items: z.array(DeliveryRowSchema),
  page: z.number().int(),
  size: z.number().int(),
  total: z.number().int(),
  counts: z.record(z.string(), z.number().int()),
  canManage: z.boolean()
});
export type DeliveryList = z.infer<typeof DeliveryListSchema>;

export const DeliveryCourierSchema = z.object({ id: z.string(), name: z.string(), me: z.boolean() });
export type DeliveryCourier = z.infer<typeof DeliveryCourierSchema>;
export const DeliveryCourierListSchema = z.array(DeliveryCourierSchema);

export const DeliveryDestinationSchema = z.object({
  name: z.string(),
  line1: nullableString,
  line2: nullableString,
  city: z.string(),
  state: z.string(),
  postalCode: nullableString,
  phone: nullableString,
  instructions: nullableString,
  handToCustomer: z.boolean()
});
export type DeliveryDestination = z.infer<typeof DeliveryDestinationSchema>;

export const DeliveryJobSchema = z.object({
  assigneeName: nullableString,
  assignedAt: nullableString,
  assignedBy: nullableString,
  attempts: z.number().int(),
  lastFailure: nullableString,
  version: z.number().int()
});
export type DeliveryJob = z.infer<typeof DeliveryJobSchema>;

export const DeliveryProofViewSchema = z.object({
  photoAssetIds: z.array(z.string()),
  photoUrls: z.array(z.string()),
  recipientName: nullableString,
  note: nullableString,
  latitude: z.number().nullable(),
  longitude: z.number().nullable(),
  deliveredAt: z.string(),
  recordedByName: z.string(),
  correctedAt: nullableString,
  correctedByName: nullableString,
  corrections: z.number().int(),
  version: z.number().int()
});
export type DeliveryProofView = z.infer<typeof DeliveryProofViewSchema>;

export const DeliveryAuditLineSchema = z.object({
  action: z.enum(['TAKE', 'ASSIGN', 'REASSIGN', 'PICKED_UP', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED', 'REATTEMPT', 'PROOF_CORRECTED', 'DATA_FIX']),
  fromStatus: z.string(),
  toStatus: z.string(),
  actorName: z.string(),
  note: z.string(),
  at: z.string()
});
export type DeliveryAuditLine = z.infer<typeof DeliveryAuditLineSchema>;

export const DeliveryDetailSchema = z.object({
  row: DeliveryRowSchema,
  destination: DeliveryDestinationSchema,
  items: z.array(z.object({ title: z.string(), quantity: z.number().int() })),
  job: DeliveryJobSchema,
  proof: DeliveryProofViewSchema.nullable(),
  audit: z.array(DeliveryAuditLineSchema),
  nextStatuses: z.array(z.string()),
  canManage: z.boolean()
});
export type DeliveryDetail = z.infer<typeof DeliveryDetailSchema>;

export const DeliveryUploadTicketSchema = z.object({
  assetId: z.string(),
  upload: z.object({ method: z.enum(['PUT', 'POST']), url: z.string(), headers: z.record(z.string(), z.string()) })
});
export type DeliveryUploadTicket = z.infer<typeof DeliveryUploadTicketSchema>;

/** Query of the queue: server-driven filters, search, sort and page. */
export interface DeliveryQuery {
  status?: readonly DeliveryStatus[];
  mode?: DeliveryMode;
  q?: string;
  /** `me`, `unassigned` or a staff id. */
  assignee?: string;
  city?: string;
  seller?: string;
  sort?: 'promised' | 'order' | 'seller' | 'city' | 'status' | 'updated';
  desc?: boolean;
  page?: number;
  size?: number;
}

export interface DeliveryActionInput {
  /** The job version the console saw (409 when stale). */
  version?: number;
  note?: string;
}

/** The proof of a delivery (delivered) or its correction (with a reason). */
export interface DeliveryProofInput {
  photoAssetIds: string[];
  recipientName?: string;
  note?: string;
  latitude?: number;
  longitude?: number;
  reason?: string;
  version?: number;
}

export const DELIVERY_PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;
export const DELIVERY_PHOTO_MAX_BYTES = 10 * 1024 * 1024;
export const DELIVERY_MAX_PHOTOS = 5;

/** Why a file cannot be a proof photo, in plain words (null when it can). Checked in the browser before the upload; the server checks again. */
export function deliveryPhotoProblem(file: { type: string; size: number }): string | null {
  if (!(DELIVERY_PHOTO_TYPES as readonly string[]).includes(file.type)) return 'Use a JPEG, PNG or WebP photo';
  if (file.size <= 0) return 'That file is empty';
  if (file.size > DELIVERY_PHOTO_MAX_BYTES) return 'Photos up to 10 MB';
  return null;
}
