/* Verified-purchase product reviews (S324), view-model of the "Rate this item" sheet. Whether a shopper may review is
   decided by the backend (a delivered order line); the app only offers the button on delivered shipments. */
import { type ReviewInput } from '@afrotransact/api-client';

import { stepIndex } from './tracking';

export const REVIEW_TITLE_MAX = 120;
export const REVIEW_TEXT_MAX = 4000;
export const MIN_STARS = 1;
export const MAX_STARS = 5;

/** "Rate this item" is offered once the shipment is delivered (the backend checks the delivered order line again). */
export function canRate(shipmentStatus: string): boolean {
  return stepIndex(shipmentStatus) >= 4;
}

export interface ReviewDraft {
  rating: number;
  title: string;
  text: string;
}

export const EMPTY_DRAFT: ReviewDraft = { rating: 0, title: '', text: '' };

/** Keys of the messages to show for what is wrong with a draft (stars are required, the words are bounded). */
export function draftErrors(d: ReviewDraft): Partial<Record<keyof ReviewDraft, 'starsRequired' | 'titleTooLong' | 'textTooLong'>> {
  const errors: ReturnType<typeof draftErrors> = {};
  if (!Number.isInteger(d.rating) || d.rating < MIN_STARS || d.rating > MAX_STARS) errors.rating = 'starsRequired';
  if (d.title.trim().length > REVIEW_TITLE_MAX) errors.title = 'titleTooLong';
  if (d.text.trim().length > REVIEW_TEXT_MAX) errors.text = 'textTooLong';
  return errors;
}

/** The request body: empty words are left out. */
export function toRequest(d: ReviewDraft): ReviewInput {
  const title = d.title.trim();
  const text = d.text.trim();
  return { rating: d.rating, ...(title ? { title } : {}), ...(text ? { text } : {}) };
}

/** The draft of an existing review, to edit it. */
export function draftOf(review: { rating: number; title: string | null; text: string | null } | null): ReviewDraft {
  return review ? { rating: review.rating, title: review.title ?? '', text: review.text ?? '' } : EMPTY_DRAFT;
}

export type ReviewProblem = 'notVerified' | 'alreadyReviewed' | 'windowClosed' | 'invalid' | 'generic';

/** Which message explains a refused review (problem codes of the gateway BFF). */
export function problemKind(status: number | undefined, code: string | undefined): ReviewProblem {
  if (code === 'REVIEW_NOT_VERIFIED_PURCHASE') return 'notVerified';
  if (code === 'REVIEW_ALREADY_EXISTS') return 'alreadyReviewed';
  if (code === 'REVIEW_EDIT_WINDOW_CLOSED') return 'windowClosed';
  if (status === 422) return 'invalid';
  return 'generic';
}

export type ReviewState = 'PUBLISHED' | 'PENDING' | 'REJECTED' | 'REMOVED';

/** What to tell the shopper after saving: a flagged review waits for a check by the team. */
export function savedKind(status: ReviewState, edited: boolean): 'moderation' | 'updated' | 'thanks' {
  if (status === 'PENDING') return 'moderation';
  return edited ? 'updated' : 'thanks';
}
