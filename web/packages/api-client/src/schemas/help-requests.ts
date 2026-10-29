import { z } from 'zod';

import { MinorSchema } from './common';

/* Help requests — the customer side of disputes (contracts/openapi/gateway-bff.yaml, tag "Help requests").
   A delivered order has no self-service refund or return: the customer describes the problem, adds photos and staff
   resolve it. Refunds, if any, go back to whoever paid. */

export const HELP_REQUEST_TYPES = ['ITEM_NOT_RECEIVED', 'DAMAGED_ITEM', 'WRONG_ITEM', 'NOT_AS_DESCRIBED', 'OTHER'] as const;
export const HelpRequestTypeSchema = z.enum(HELP_REQUEST_TYPES);
export type HelpRequestType = z.infer<typeof HelpRequestTypeSchema>;

/** Limits enforced by the BFF (and mirrored in the form). */
export const HELP_EVIDENCE_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/heic'] as const;
export const HELP_EVIDENCE_MAX_BYTES = 10 * 1024 * 1024;
export const HELP_EVIDENCE_MAX_FILES = 5;
export const HELP_DESCRIPTION_MIN = 10;
export const HELP_DESCRIPTION_MAX = 4000;

export const HelpEvidenceUploadSchema = z.object({
  assetId: z.string(),
  maxBytes: z.number().int(),
  upload: z.object({
    method: z.string(),
    url: z.string(),
    headers: z.record(z.string(), z.string()),
    expiresAt: z.string().nullable().optional()
  })
});
export type HelpEvidenceUpload = z.infer<typeof HelpEvidenceUploadSchema>;

export const HelpEvidenceAssetSchema = z.object({ assetId: z.string(), contentType: z.string(), bytes: z.number().int(), status: z.literal('READY') });
export type HelpEvidenceAsset = z.infer<typeof HelpEvidenceAssetSchema>;

export const HELP_STATUSES = ['RECEIVED', 'IN_REVIEW', 'RESOLVED', 'CLOSED', 'WITHDRAWN'] as const;
export const HelpStatusSchema = z.enum(HELP_STATUSES);
export type HelpStatus = z.infer<typeof HelpStatusSchema>;

export const HelpResolutionSchema = z.object({
  outcome: z.enum(['REFUNDED', 'NOT_REFUNDED']),
  note: z.string(),
  refundMinor: MinorSchema.nullable(),
  decidedAt: z.string().nullable()
});
export type HelpResolution = z.infer<typeof HelpResolutionSchema>;

export const HelpRequestSchema = z.object({
  id: z.string(),
  reference: z.string(),
  orderNumber: z.string(),
  type: HelpRequestTypeSchema,
  status: HelpStatusSchema,
  statusLabel: z.string(),
  description: z.string(),
  evidenceCount: z.number().int().min(0),
  claimedMinor: MinorSchema,
  currency: z.string(),
  resolution: HelpResolutionSchema.nullable(),
  createdAt: z.string(),
  updatedAt: z.string()
});
export type HelpRequest = z.infer<typeof HelpRequestSchema>;

export const HelpRequestPageSchema = z.object({ items: z.array(HelpRequestSchema), nextCursor: z.string().nullable() });
export type HelpRequestPage = z.infer<typeof HelpRequestPageSchema>;

/** The body of POST /v1/me/help-requests (the Idempotency-Key travels as a header). */
export const CreateHelpRequestSchema = z.object({
  orderNumber: z.string().min(1),
  shipmentId: z.string().min(1).optional(),
  type: HelpRequestTypeSchema,
  description: z.string().trim().min(HELP_DESCRIPTION_MIN, 'Describe the problem in a few words').max(HELP_DESCRIPTION_MAX),
  evidenceAssetIds: z.array(z.string().min(1)).max(HELP_EVIDENCE_MAX_FILES)
});
export type CreateHelpRequest = z.infer<typeof CreateHelpRequestSchema>;
