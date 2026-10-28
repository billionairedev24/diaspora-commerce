/* Mock BFF for verified-purchase product reviews (S324): the same routes, shapes and refusals as
   contracts/openapi/gateway-bff.yaml, tag Reviews. A shopper may review a product only when one of their orders has a
   delivered line for it (the demo account has received the shea butter and the cookbook, not the ofada rice). One review
   per shopper and product, editable for 30 days, deletable; text with a link, an e-mail address, a phone number or
   profanity waits for moderation instead of being published. State lives on the mock database (resetDb() restores it). */
import { http } from 'msw';
import { z } from 'zod';

import {
  type MyReview,
  type OwnReview,
  type ProductReviewsPage,
  type PublicReview,
  REVIEW_PHOTOS_MAX,
  REVIEW_REASON_CODES,
  REVIEW_TEXT_MAX,
  REVIEW_TITLE_MAX,
  type ReviewRating
} from '../schemas/reviews';
import { getDb } from './db';
import { atinOf } from './fixtures/catalog';
import { json, requireUser, route } from './http-kit';
import { MockProblem } from './service';
import { account } from './shopper-state';

const DAY = 86_400_000;
const EDIT_WINDOW_DAYS = 30;
/** Where a completed upload is published (the media service's public bucket). */
export const MOCK_REVIEW_PHOTO_BASE = 'https://img.afrotransact.test/reviews';

interface MockReview {
  id: string;
  atin: string;
  /** Null for the reviews of other shoppers. */
  userId: string | null;
  reviewerName: string;
  rating: number;
  title: string | null;
  text: string | null;
  photos: string[];
  status: 'PUBLISHED' | 'PENDING' | 'REJECTED' | 'REMOVED';
  createdAt: string;
  updatedAt: string;
  reply: { text: string; at: string } | null;
  reported: Set<string>;
}

interface ReviewsState {
  reviews: MockReview[];
  seq: number;
}

const states = new WeakMap<object, ReviewsState>();

function seed(): ReviewsState {
  const shea = atinOf('p15');
  const at = (days: number) => new Date(Date.now() - days * DAY).toISOString();
  const other = (n: number, atin: string, name: string, rating: number, title: string | null, text: string | null, days: number, reply: string | null = null): MockReview => ({
    id: `00000000-0000-4000-8000-00000000a${String(n).padStart(3, '0')}`, atin, userId: null, reviewerName: name, rating, title, text, photos: [], status: 'PUBLISHED',
    createdAt: at(days), updatedAt: at(days), reply: reply ? { text: reply, at: at(days - 1) } : null, reported: new Set()
  });
  return {
    seq: 0,
    reviews: [
      other(1, shea, 'Amara O.', 5, 'Smooth and pure', 'Absorbs fast, no smell. Third jar.', 12),
      other(2, shea, 'Kofi B.', 3, null, 'Good, but the lid cracked in the post.', 9, 'Sorry about the lid, Kofi. We now wrap the jars twice.'),
      other(3, shea, 'Zainab K.', 4, 'Great for hair', 'I use it on my ends every Sunday.', 5),
      other(4, shea, 'Chidi E.', 5, 'Proper shea', null, 3),
      other(5, shea, 'Funmi A.', 5, 'Will reorder', 'Arrived quickly and smells earthy, the way it should.', 2),
      other(6, atinOf('p17'), 'Amara O.', 5, 'My kitchen bible', 'Every recipe I tried worked.', 20)
    ]
  };
}

function stateOf(): ReviewsState {
  const db = getDb();
  let s = states.get(db);
  if (!s) {
    s = seed();
    states.set(db, s);
  }
  return s;
}

/** First name and last initial: "Adaeze N." */
const displayName = (first: string, last: string) => `${first.trim()} ${last.trim().charAt(0).toUpperCase()}.`.trim();

function ratingOf(atin: string): ReviewRating {
  const published = stateOf().reviews.filter((r) => r.atin === atin && r.status === 'PUBLISHED');
  const breakdown = [1, 2, 3, 4, 5].map((n) => published.filter((r) => r.rating === n).length);
  const count = published.length;
  const average = count ? Math.round((published.reduce((a, r) => a + r.rating, 0) / count) * 10) / 10 : 0;
  return { average, count, breakdown };
}

const publicOf = (r: MockReview): PublicReview => ({
  id: r.id, reviewerName: r.reviewerName, rating: r.rating, title: r.title, text: r.text, photos: r.photos.map((url) => ({ url })), verifiedPurchase: true, createdAt: r.createdAt, reply: r.reply
});

const REASONS: Record<string, string> = {
  PERSONAL_INFORMATION: 'The review shared personal information.'
};

function ownOf(r: MockReview): OwnReview {
  const editableUntil = new Date(new Date(r.createdAt).getTime() + EDIT_WINDOW_DAYS * DAY);
  return {
    id: r.id, reviewerName: r.reviewerName, rating: r.rating, title: r.title, text: r.text, photos: r.photos.map((url) => ({ url })), status: r.status, createdAt: r.createdAt,
    updatedAt: r.updatedAt, editableUntil: editableUntil.toISOString(), editable: editableUntil.getTime() > Date.now(),
    moderation: r.status === 'REJECTED' || r.status === 'REMOVED' ? { reasonCode: 'PERSONAL_INFORMATION', reason: REASONS.PERSONAL_INFORMATION ?? '', note: null } : null, reply: r.reply
  };
}

/** The automatic checks of catalog (profanity, links, e-mail addresses, phone numbers): flagged reviews wait for staff. */
export function flaggedByChecks(...parts: Array<string | null | undefined>): boolean {
  const text = parts.filter(Boolean).join(' ');
  return /https?:\/\/|www\.|\S+@\S+\.\S+|(?:\+?\d[\s().-]?){9,}|\b(?:damn|shit|fuck)\w*/i.test(text);
}

function delivered(userId: string, atin: string): boolean {
  return account(userId).orders.some((o) => o.shipments.some((s) => s.status === 'delivered' && s.lines.some((l) => l.atin === atin || l.productAtin === atin)));
}

const mineOf = (userId: string, atin: string) => stateOf().reviews.find((r) => r.userId === userId && r.atin === atin);

const InputSchema = z.object({
  rating: z.number().int().min(1, 'Choose 1 to 5 stars').max(5, 'Choose 1 to 5 stars'),
  title: z.string().max(REVIEW_TITLE_MAX).optional(),
  text: z.string().max(REVIEW_TEXT_MAX).optional(),
  photos: z.array(z.object({ assetId: z.string().optional(), url: z.string().optional() })).max(REVIEW_PHOTOS_MAX).optional()
});

async function readInput(request: Request, existing: string[] = []) {
  let raw: unknown = {};
  try {
    raw = JSON.parse((await request.text()) || '{}');
  } catch {
    throw new MockProblem(400, 'MALFORMED_JSON', 'Malformed request', 'The request body is not valid JSON.');
  }
  const parsed = InputSchema.safeParse(raw);
  if (!parsed.success) {
    const errors: Record<string, string> = {};
    parsed.error.issues.forEach((i) => { errors[i.path.join('.') || '_'] = i.message; });
    throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Check your review.', { errors });
  }
  const photos = (parsed.data.photos ?? []).map((p) => {
    if (p.url && existing.includes(p.url)) return p.url;
    if (p.assetId) return `${MOCK_REVIEW_PHOTO_BASE}/${p.assetId}.webp`;
    throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Check your review.', { errors: { photos: 'That photo is not available' } });
  });
  return { rating: parsed.data.rating, title: parsed.data.title?.trim() || null, text: parsed.data.text?.trim() || null, photos };
}

export const reviewHandlers = [
  http.get('*/v1/products/:atin/reviews', route((ctx, p) => {
    const q = new URL(ctx.request.url).searchParams;
    const sort = q.get('sort') ?? 'recent';
    const stars = Number(q.get('stars') ?? 0) || 0;
    const page = Math.max(0, Number(q.get('page') ?? 0) || 0);
    const size = Math.min(50, Math.max(1, Number(q.get('size') ?? 10) || 10));
    const atin = p.atin ?? '';
    const items = stateOf().reviews.filter((r) => r.atin === atin && r.status === 'PUBLISHED' && (!stars || r.rating === stars))
      .sort((a, b) => (sort === 'highest' ? b.rating - a.rating : sort === 'lowest' ? a.rating - b.rating : 0) || b.createdAt.localeCompare(a.createdAt));
    const out: ProductReviewsPage = { rating: ratingOf(atin), items: items.slice(page * size, page * size + size).map(publicOf), total: items.length, page, size };
    return json(out);
  })),
  http.get('*/v1/products/:atin/reviews/mine', route((ctx, p) => {
    const u = requireUser(ctx);
    const atin = p.atin ?? '';
    const mine = mineOf(u.id, atin);
    const out: MyReview = mine
      ? { eligible: false, reason: 'already-reviewed', review: ownOf(mine) }
      : { eligible: delivered(u.id, atin), reason: delivered(u.id, atin) ? null : 'not-purchased', review: null };
    return json(out);
  })),
  http.post('*/v1/products/:atin/reviews', route(async (ctx, p) => {
    const u = requireUser(ctx);
    const atin = p.atin ?? '';
    if (!delivered(u.id, atin)) {
      throw new MockProblem(403, 'REVIEW_NOT_VERIFIED_PURCHASE', 'Only buyers can review', 'Only customers who received this item can review it. Once your order is delivered, you can rate it.');
    }
    if (mineOf(u.id, atin)) throw new MockProblem(409, 'REVIEW_ALREADY_EXISTS', 'Already reviewed', 'You have already reviewed this item. You can edit your review.');
    const input = await readInput(ctx.request);
    const s = stateOf();
    s.seq += 1;
    const now = new Date().toISOString();
    const review: MockReview = {
      id: `00000000-0000-4000-8000-00000000b${String(s.seq).padStart(3, '0')}`, atin, userId: u.id, reviewerName: displayName(u.firstName, u.lastName), ...input,
      status: flaggedByChecks(input.title, input.text) ? 'PENDING' : 'PUBLISHED', createdAt: now, updatedAt: now, reply: null, reported: new Set()
    };
    s.reviews.push(review);
    return json(ownOf(review), 201);
  })),
  http.put('*/v1/products/:atin/reviews/mine', route(async (ctx, p) => {
    const u = requireUser(ctx);
    const mine = mineOf(u.id, p.atin ?? '');
    if (!mine) throw new MockProblem(404, 'NOT_FOUND', 'No review', 'You have not reviewed this item.');
    if (!ownOf(mine).editable) throw new MockProblem(409, 'REVIEW_EDIT_WINDOW_CLOSED', 'Editing is closed', `Reviews can be edited for ${EDIT_WINDOW_DAYS} days after you wrote them.`);
    const input = await readInput(ctx.request, mine.photos);
    Object.assign(mine, input, { status: flaggedByChecks(input.title, input.text) ? 'PENDING' : 'PUBLISHED', updatedAt: new Date().toISOString() });
    return json(ownOf(mine));
  })),
  http.delete('*/v1/products/:atin/reviews/mine', route((ctx, p) => {
    const u = requireUser(ctx);
    const s = stateOf();
    const mine = mineOf(u.id, p.atin ?? '');
    if (!mine) throw new MockProblem(404, 'NOT_FOUND', 'No review', 'You have not reviewed this item.');
    s.reviews.splice(s.reviews.indexOf(mine), 1);
    return new Response(null, { status: 204 });
  })),
  http.post('*/v1/reviews/:reviewId/reports', route(async (ctx, p) => {
    const u = requireUser(ctx);
    const input = await ctx.request.json().catch(() => ({})) as { reasonCode?: string; details?: string };
    if (!REVIEW_REASON_CODES.some((c) => c === input.reasonCode)) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Choose a reason.', { errors: { reasonCode: 'Choose a reason' } });
    if (input.reasonCode === 'OTHER' && !input.details?.trim()) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Tell us what is wrong.', { errors: { details: 'Tell us what is wrong' } });
    const review = stateOf().reviews.find((r) => r.id === p.reviewId && r.status === 'PUBLISHED');
    if (!review) throw new MockProblem(404, 'NOT_FOUND', 'Not found', 'We couldn’t find that review.');
    if (review.userId === u.id) throw new MockProblem(403, 'FORBIDDEN', 'Not allowed', 'You cannot report your own review.');
    const created = !review.reported.has(u.id);
    review.reported.add(u.id);
    return json({ created }, created ? 201 : 200);
  })),
  http.post('*/v1/review-photos/uploads', route(async (ctx) => {
    requireUser(ctx);
    const b = (await ctx.request.json()) as { fileName?: string; contentType?: string; size?: number };
    const okType = b.contentType === 'image/jpeg' || b.contentType === 'image/png' || b.contentType === 'image/webp';
    if (!okType || !(b.size && b.size > 0 && b.size <= 8 * 1024 * 1024)) {
      throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Photos must be JPEG, PNG or WebP and at most 8 MB.');
    }
    const id = `review-photo-${Date.now()}`;
    return json({ assetId: id, maxBytes: 8 * 1024 * 1024, upload: { method: 'PUT', url: `${new URL(ctx.request.url).origin}/api/bff/mock-review-uploads/${id}`,
      headers: { 'Content-Type': b.contentType ?? 'image/webp' }, expiresAt: new Date(Date.now() + 15 * 60_000).toISOString() } }, 201);
  })),
  http.put('*/mock-review-uploads/:id', () => new Response(null, { status: 200 })),
  http.post('*/v1/review-photos/uploads/:assetId/complete', route((ctx, p) => {
    requireUser(ctx);
    return json({ assetId: p.assetId, url: `${MOCK_REVIEW_PHOTO_BASE}/${p.assetId}.webp`, status: 'READY' });
  }))
];
