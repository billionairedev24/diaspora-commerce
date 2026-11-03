import { z } from 'zod';

import { type Requester } from './http';
import { type ResumeUpload, ResumeUploadSchema } from './schemas/careers';
import {
  type MyReview,
  MyReviewSchema,
  type OwnReview,
  OwnReviewSchema,
  type ProductReviewsPage,
  ProductReviewsPageSchema,
  type ReviewInput,
  type ReviewPhotoAsset,
  ReviewPhotoAssetSchema,
  type ReviewReportInput,
  type ReviewReportResult,
  ReviewReportResultSchema,
  type ReviewSort
} from './schemas/reviews';

export interface ReviewListQuery {
  sort?: ReviewSort;
  /** Only reviews with this many stars. */
  stars?: number;
  page?: number;
  size?: number;
}

/**
 * Verified-purchase product reviews of the gateway BFF (S324), spread into createApiClient. Whether a shopper may review
 * is decided by catalog with orders (a delivered order line); the client only reports what the BFF says.
 */
export function reviewMethods(request: Requester) {
  const mine = (atin: string) => `/v1/products/${encodeURIComponent(atin)}/reviews/mine`;
  return {
    /** A product's published reviews with its rating (average, count, 1 to 5 star breakdown), sorted and paged. */
    listProductReviews: (atin: string, q: ReviewListQuery = {}, signal?: AbortSignal): Promise<ProductReviewsPage> =>
      request(`/v1/products/${encodeURIComponent(atin)}/reviews`, ProductReviewsPageSchema, {
        signal,
        query: {
          sort: q.sort,
          stars: q.stars === undefined ? undefined : String(q.stars),
          page: q.page === undefined ? undefined : String(q.page),
          size: q.size === undefined ? undefined : String(q.size)
        }
      }),
    /** Can I review this product (delivered to me, not yet reviewed), and my review in any state. */
    getMyProductReview: (atin: string, signal?: AbortSignal): Promise<MyReview> => request(mine(atin), MyReviewSchema, { signal }),
    writeProductReview: (atin: string, body: ReviewInput): Promise<OwnReview> =>
      request(`/v1/products/${encodeURIComponent(atin)}/reviews`, OwnReviewSchema, { method: 'POST', body }),
    /** Within 30 days of writing it; editing a refused or removed review sends it back to moderation. */
    editMyProductReview: (atin: string, body: ReviewInput): Promise<OwnReview> => request(mine(atin), OwnReviewSchema, { method: 'PUT', body }),
    deleteMyProductReview: (atin: string): Promise<void> => request(mine(atin), voidSchema, { method: 'DELETE' }),
    /** 201 when the report is new, 200 when I had already reported it. */
    reportProductReview: (reviewId: string, body: ReviewReportInput): Promise<ReviewReportResult> =>
      request(`/v1/reviews/${encodeURIComponent(reviewId)}/reports`, ReviewReportResultSchema, { method: 'POST', body }),
    requestReviewPhotoUpload: (input: { fileName: string; contentType: string; size: number }): Promise<ResumeUpload> =>
      request('/v1/review-photos/uploads', ResumeUploadSchema, { method: 'POST', body: input }),
    completeReviewPhotoUpload: (assetId: string): Promise<ReviewPhotoAsset> =>
      request(`/v1/review-photos/uploads/${encodeURIComponent(assetId)}/complete`, ReviewPhotoAssetSchema, { method: 'POST' })
  };
}

const voidSchema = z.undefined();

/**
 * Uploads a review photo through the media service's signed upload: ask the BFF for a URL, PUT the file straight to
 * storage (no BFF credentials), then let media check it. Returns the asset id to send in the review's photos.
 */
export async function uploadReviewPhoto(
  api: {
    requestReviewPhotoUpload: (input: { fileName: string; contentType: string; size: number }) => Promise<ResumeUpload>;
    completeReviewPhotoUpload: (assetId: string) => Promise<ReviewPhotoAsset>;
  },
  file: { name: string; type: string; size: number } & Blob,
  put: (url: string, init: RequestInit) => Promise<Response> = (url, init) => fetch(url, init)
): Promise<ReviewPhotoAsset> {
  const ticket = await api.requestReviewPhotoUpload({ fileName: file.name, contentType: file.type || 'image/jpeg', size: file.size });
  const res = await put(ticket.upload.url, { method: ticket.upload.method, headers: ticket.upload.headers, body: file });
  if (!res.ok) throw new Error(`Upload failed (${res.status})`);
  return api.completeReviewPhotoUpload(ticket.assetId);
}
