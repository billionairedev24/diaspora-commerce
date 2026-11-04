import { z } from 'zod';

/* Verified-purchase product reviews (S324) — contracts/openapi/gateway-bff.yaml, tag Reviews. Only a customer with a
   delivered order line can review; a review shows as "Verified purchase" with the first name and last initial. */

export const REVIEW_SORTS = ['recent', 'highest', 'lowest'] as const;
export type ReviewSort = (typeof REVIEW_SORTS)[number];

export const REVIEW_REASON_CODES = ['ABUSIVE', 'PERSONAL_INFORMATION', 'SPAM', 'OFF_TOPIC', 'CONFLICT_OF_INTEREST', 'OTHER'] as const;
export type ReviewReasonCode = (typeof REVIEW_REASON_CODES)[number];

export const REVIEW_STATUSES = ['PUBLISHED', 'PENDING', 'REJECTED', 'REMOVED'] as const;

/** Limits enforced by the BFF and catalog, mirrored in the form. */
export const REVIEW_TITLE_MAX = 120;
export const REVIEW_TEXT_MAX = 4000;
export const REVIEW_PHOTOS_MAX = 5;
export const REVIEW_PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;
export const REVIEW_PHOTO_MAX_BYTES = 8 * 1024 * 1024;
export const SELLER_REPLY_MAX = 1000;

export const ReviewRatingSchema = z.object({
  average: z.number(),
  count: z.number().int(),
  /** Reviews that gave 1, 2, 3, 4 and 5 stars, in that order. */
  breakdown: z.array(z.number().int()).length(5)
});
export type ReviewRating = z.infer<typeof ReviewRatingSchema>;

export const ReviewPhotoSchema = z.object({ url: z.string() });
export type ReviewPhoto = z.infer<typeof ReviewPhotoSchema>;

export const ReviewReplySchema = z.object({ text: z.string(), at: z.string() });
export type ReviewReply = z.infer<typeof ReviewReplySchema>;

export const PublicReviewSchema = z.object({
  id: z.string(),
  /** First name and last initial, for example "Adaeze N.". */
  reviewerName: z.string(),
  rating: z.number().int(),
  title: z.string().nullable(),
  text: z.string().nullable(),
  photos: z.array(ReviewPhotoSchema),
  verifiedPurchase: z.boolean(),
  createdAt: z.string(),
  reply: ReviewReplySchema.nullable()
});
export type PublicReview = z.infer<typeof PublicReviewSchema>;

export const ProductReviewsPageSchema = z.object({
  rating: ReviewRatingSchema,
  items: z.array(PublicReviewSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int()
});
export type ProductReviewsPage = z.infer<typeof ProductReviewsPageSchema>;

export const ReviewModerationSchema = z.object({
  reasonCode: z.enum(REVIEW_REASON_CODES),
  /** The reason in words for the shopper. */
  reason: z.string(),
  note: z.string().nullable()
});

export const OwnReviewSchema = z.object({
  id: z.string(),
  reviewerName: z.string(),
  rating: z.number().int(),
  title: z.string().nullable(),
  text: z.string().nullable(),
  photos: z.array(ReviewPhotoSchema),
  /** PENDING waits for moderation; REJECTED / REMOVED say why in `moderation`. */
  status: z.enum(REVIEW_STATUSES),
  createdAt: z.string(),
  updatedAt: z.string(),
  editableUntil: z.string(),
  editable: z.boolean(),
  moderation: ReviewModerationSchema.nullable(),
  reply: ReviewReplySchema.nullable()
});
export type OwnReview = z.infer<typeof OwnReviewSchema>;

export const MyReviewSchema = z.object({
  /** The product was delivered to me and I have not reviewed it. */
  eligible: z.boolean(),
  reason: z.enum(['not-purchased', 'already-reviewed']).nullable(),
  review: OwnReviewSchema.nullable()
});
export type MyReview = z.infer<typeof MyReviewSchema>;

/** A new upload (assetId) or a photo the review already has (url). */
export const ReviewPhotoInputSchema = z.object({ assetId: z.string().optional(), url: z.string().optional() });
export type ReviewPhotoInput = z.infer<typeof ReviewPhotoInputSchema>;

/** The review form: stars are required; the words are optional but bounded. */
export const ReviewInputSchema = z.object({
  rating: z.number().int().min(1, 'Choose 1 to 5 stars').max(5, 'Choose 1 to 5 stars'),
  title: z.string().max(REVIEW_TITLE_MAX).optional(),
  text: z.string().max(REVIEW_TEXT_MAX).optional(),
  photos: z.array(ReviewPhotoInputSchema).max(REVIEW_PHOTOS_MAX).optional()
});
export type ReviewInput = z.infer<typeof ReviewInputSchema>;

export const ReviewReportInputSchema = z.object({
  reasonCode: z.enum(REVIEW_REASON_CODES),
  details: z.string().max(500).optional()
});
export type ReviewReportInput = z.infer<typeof ReviewReportInputSchema>;

export const ReviewReportResultSchema = z.object({ created: z.boolean() });
export type ReviewReportResult = z.infer<typeof ReviewReportResultSchema>;

export const ReviewPhotoAssetSchema = z.object({
  assetId: z.string(),
  url: z.string().nullable(),
  /** READY when media published the photo. */
  status: z.string()
});
export type ReviewPhotoAsset = z.infer<typeof ReviewPhotoAssetSchema>;

/* Seller Center (gateway-bff-seller.yaml, tag Reviews): the customer is never identified. */

export const SellerReviewSchema = z.object({
  id: z.string(),
  atin: z.string(),
  productTitle: z.string(),
  reviewerName: z.string(),
  rating: z.number().int(),
  title: z.string().nullable(),
  text: z.string().nullable(),
  photos: z.array(ReviewPhotoSchema),
  verifiedPurchase: z.boolean(),
  createdAt: z.string(),
  reportCount: z.number().int(),
  reply: ReviewReplySchema.nullable()
});
export type SellerReview = z.infer<typeof SellerReviewSchema>;

export const SellerReviewPageSchema = z.object({
  items: z.array(SellerReviewSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int()
});
export type SellerReviewPage = z.infer<typeof SellerReviewPageSchema>;
