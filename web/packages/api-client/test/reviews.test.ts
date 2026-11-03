import { afterAll, beforeAll, beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import { ApiError, createApiClient, type MyReview, type OwnReview, type ProductReviewsPage, reviewKeys, uploadReviewPhoto } from '../src';
import { type components } from '../src/generated/gateway-bff';
import { resetDb } from '../src/mocks';
import { atinOf } from '../src/mocks/fixtures/catalog';
import { mockServer } from '../src/mocks/node';

type S = components['schemas'];

const SHEA = atinOf('p15');
const RICE = atinOf('p2');

function client() {
  let cookie = '';
  const opts = {
    baseUrl: 'http://bff.test',
    fetch: async (req: Request) => {
      if (cookie) req.headers.set('cookie', cookie);
      const res = await fetch(req);
      const set = res.headers.get('set-cookie');
      if (set) cookie = set.split(';')[0] ?? '';
      return res;
    }
  };
  return { api: createApiClient(opts), opts };
}

async function signedIn() {
  const { api, opts } = client();
  const res = await opts.fetch(new Request(api.loginUrl('/account'), { redirect: 'manual' }));
  if (res.status !== 302) throw new Error('expected the hosted sign-in redirect');
  return api;
}

async function problemOf(p: Promise<unknown>): Promise<ApiError> {
  try {
    await p;
  } catch (e) {
    if (e instanceof ApiError) return e;
    throw e;
  }
  throw new Error('expected a problem');
}

beforeAll(() => mockServer.listen({ onUnhandledRequest: 'error' }));
afterAll(() => mockServer.close());
beforeEach(() => { resetDb(); });

describe('review schemas stay aligned with the contract', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<ProductReviewsPage>().toExtend<S['ProductReviewsPage']>();
    expectTypeOf<OwnReview>().toExtend<S['OwnReview']>();
    expectTypeOf<MyReview>().toExtend<S['MyReview']>();
  });

  it('keeps the shopper own review under the account queries so signing out drops it', () => {
    expect(reviewKeys.mine(SHEA).slice(0, 2)).toEqual(['account', 'reviews']);
    expect(reviewKeys.list(SHEA, { sort: 'highest' })).toEqual(['product', 'reviews', SHEA, 'list', { sort: 'highest' }]);
  });
});

describe('mock BFF — product reviews', () => {
  it('shows the rating and a sorted, paged list to guests', async () => {
    const { api } = client();
    const page = await api.listProductReviews(SHEA);
    expect(page.rating.count).toBe(5);
    expect(page.rating.breakdown).toEqual([0, 0, 1, 1, 3]);
    expect(page.rating.average).toBe(4.4);
    expect(page.items.every((r) => r.verifiedPurchase)).toBe(true);
    expect(page.items.map((r) => r.reviewerName)).toContain('Amara O.');
    const lowest = await api.listProductReviews(SHEA, { sort: 'lowest', size: 2 });
    expect(lowest.items.map((r) => r.rating)).toEqual([3, 4]);
    expect(lowest.total).toBe(5);
    const second = await api.listProductReviews(SHEA, { sort: 'lowest', size: 2, page: 1 });
    expect(second.items).toHaveLength(2);
    expect((await api.listProductReviews(SHEA, { stars: 5 })).total).toBe(3);
    expect(await problemOf(api.getMyProductReview(SHEA))).toMatchObject({ status: 401 });
  });

  it('lets only a customer with a delivered line review, once, and says why not', async () => {
    const api = await signedIn();
    expect(await api.getMyProductReview(SHEA)).toEqual({ eligible: true, reason: null, review: null });
    expect(await api.getMyProductReview(RICE)).toEqual({ eligible: false, reason: 'not-purchased', review: null });
    const denied = await problemOf(api.writeProductReview(RICE, { rating: 5 }));
    expect(denied.status).toBe(403);
    expect(denied.code).toBe('REVIEW_NOT_VERIFIED_PURCHASE');
    expect((await problemOf(api.writeProductReview(SHEA, { rating: 0 }))).status).toBe(422);

    const review = await api.writeProductReview(SHEA, { rating: 4, title: 'Lovely', text: 'Soft and fragrant.' });
    expect(review).toMatchObject({ status: 'PUBLISHED', reviewerName: 'Adaeze N.', editable: true, moderation: null });
    expect((await problemOf(api.writeProductReview(SHEA, { rating: 5 }))).code).toBe('REVIEW_ALREADY_EXISTS');
    const mine = await api.getMyProductReview(SHEA);
    expect(mine).toMatchObject({ eligible: false, reason: 'already-reviewed' });
    expect(mine.review?.id).toBe(review.id);
    const page = await api.listProductReviews(SHEA);
    expect(page.rating.count).toBe(6);
    expect(page.items.some((r) => r.id === review.id && r.reviewerName === 'Adaeze N.')).toBe(true);
  });

  it('sends reviews with a link, e-mail address or phone number to moderation instead of publishing them', async () => {
    const api = await signedIn();
    const review = await api.writeProductReview(SHEA, { rating: 5, text: 'Great, see www.cheap-shea.example' });
    expect(review.status).toBe('PENDING');
    expect((await api.listProductReviews(SHEA)).rating.count).toBe(5);
    const edited = await api.editMyProductReview(SHEA, { rating: 5, text: 'Great shea butter' });
    expect(edited.status).toBe('PUBLISHED');
    expect((await api.listProductReviews(SHEA)).rating.count).toBe(6);
  });

  it('edits and deletes the own review and updates the rating', async () => {
    const api = await signedIn();
    await api.writeProductReview(SHEA, { rating: 1 });
    expect((await api.listProductReviews(SHEA)).rating.breakdown[0]).toBe(1);
    await api.editMyProductReview(SHEA, { rating: 5, title: 'Changed my mind' });
    expect((await api.listProductReviews(SHEA)).rating.breakdown).toEqual([0, 0, 1, 1, 4]);
    await api.deleteMyProductReview(SHEA);
    expect((await api.listProductReviews(SHEA)).rating.count).toBe(5);
    expect(await problemOf(api.deleteMyProductReview(SHEA))).toMatchObject({ status: 404 });
    expect((await api.getMyProductReview(SHEA)).eligible).toBe(true);
  });

  it('reports other shoppers reviews once and refuses reports of the own review', async () => {
    const api = await signedIn();
    const [first] = (await api.listProductReviews(SHEA)).items;
    expect(first).toBeDefined();
    const id = first?.id ?? '';
    expect(await api.reportProductReview(id, { reasonCode: 'SPAM' })).toEqual({ created: true });
    expect(await api.reportProductReview(id, { reasonCode: 'SPAM' })).toEqual({ created: false });
    expect((await problemOf(api.reportProductReview(id, { reasonCode: 'OTHER' }))).status).toBe(422);
    const own = await api.writeProductReview(SHEA, { rating: 5 });
    expect((await problemOf(api.reportProductReview(own.id, { reasonCode: 'SPAM' }))).status).toBe(403);
  });

  it('uploads a photo straight to storage, then attaches it to the review', async () => {
    const api = await signedIn();
    const puts: string[] = [];
    const file = Object.assign(new Blob(['x'], { type: 'image/webp' }), { name: 'jar.webp' });
    const asset = await uploadReviewPhoto(api, file, async (url) => {
      puts.push(url);
      return new Response(null, { status: 200 });
    });
    expect(asset.status).toBe('READY');
    expect(puts).toHaveLength(1);
    const review = await api.writeProductReview(SHEA, { rating: 5, photos: [{ assetId: asset.assetId }] });
    expect(review.photos).toEqual([{ url: asset.url }]);
    const refused = Object.assign(new Blob(['x'], { type: 'application/pdf' }), { name: 'a.pdf' });
    expect((await problemOf(uploadReviewPhoto(api, refused))).status).toBe(422);
  });
});
