import { ApiError, createApiClient } from '@afrotransact/api-client';
import { describe, expect, it } from 'vitest';

import { mockFetch } from '../src/api/mock/router';
import { canRate, draftErrors, draftOf, EMPTY_DRAFT, problemKind, REVIEW_TEXT_MAX, REVIEW_TITLE_MAX, savedKind, toRequest } from '../src/domain/reviews';

describe('Rate this item (view-model)', () => {
  it('is offered only for delivered shipments', () => {
    expect(canRate('delivered')).toBe(true);
    expect(canRate('Delivered')).toBe(true);
    expect(['placed', 'packed', 'shipped', 'out_for_delivery'].some(canRate)).toBe(false);
  });

  it('requires 1 to 5 stars and bounds the words', () => {
    expect(draftErrors(EMPTY_DRAFT)).toEqual({ rating: 'starsRequired' });
    expect(draftErrors({ rating: 6, title: '', text: '' })).toEqual({ rating: 'starsRequired' });
    expect(draftErrors({ rating: 4, title: 'x'.repeat(REVIEW_TITLE_MAX + 1), text: 'y'.repeat(REVIEW_TEXT_MAX + 1) })).toEqual({ title: 'titleTooLong', text: 'textTooLong' });
    expect(draftErrors({ rating: 5, title: '', text: '' })).toEqual({});
  });

  it('sends trimmed words and leaves empty ones out', () => {
    expect(toRequest({ rating: 4, title: '  Lovely ', text: '' })).toEqual({ rating: 4, title: 'Lovely' });
    expect(toRequest({ rating: 5, title: '', text: ' Soft. ' })).toEqual({ rating: 5, text: 'Soft.' });
  });

  it('edits an existing review from its saved words', () => {
    expect(draftOf({ rating: 3, title: null, text: 'Fine' })).toEqual({ rating: 3, title: '', text: 'Fine' });
    expect(draftOf(null)).toEqual(EMPTY_DRAFT);
  });

  it('explains the refusals of the backend', () => {
    expect(problemKind(403, 'REVIEW_NOT_VERIFIED_PURCHASE')).toBe('notVerified');
    expect(problemKind(409, 'REVIEW_ALREADY_EXISTS')).toBe('alreadyReviewed');
    expect(problemKind(409, 'REVIEW_EDIT_WINDOW_CLOSED')).toBe('windowClosed');
    expect(problemKind(422, 'VALIDATION_FAILED')).toBe('invalid');
    expect(problemKind(500, undefined)).toBe('generic');
  });

  it('tells a flagged review from a published one', () => {
    expect(savedKind('PENDING', false)).toBe('moderation');
    expect(savedKind('PUBLISHED', true)).toBe('updated');
    expect(savedKind('PUBLISHED', false)).toBe('thanks');
  });
});

// The in-app mock backend answers the review routes (dev, EXPO_PUBLIC_MOCKS=1) with the contract's shapes.
describe('mock backend', () => {
  const api = () => ({
    bff: createApiClient({ baseUrl: 'http://bff.test', fetch: (req) => { req.headers.set('Authorization', 'Bearer mock-token'); return mockFetch(req); } })
  });

  it('lets the shopper rate a delivered item once, edit it and delete it', async () => {
    const { bff } = api();
    const delivered = 'ATGARI0004';
    expect(await bff.getMyProductReview(delivered)).toMatchObject({ eligible: true, review: null });
    const saved = await bff.writeProductReview(delivered, { rating: 4, title: 'Good garri', text: 'Crisp.' });
    expect(saved).toMatchObject({ status: 'PUBLISHED', rating: 4, reviewerName: 'Adaeze O.' });
    expect(await bff.getMyProductReview(delivered)).toMatchObject({ eligible: false, reason: 'already-reviewed' });
    const err = await bff.writeProductReview(delivered, { rating: 5 }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe('REVIEW_ALREADY_EXISTS');
    expect((await bff.editMyProductReview(delivered, { rating: 5 })).rating).toBe(5);
    await bff.deleteMyProductReview(delivered);
    expect((await bff.getMyProductReview(delivered)).eligible).toBe(true);
  });

  it('refuses an item that was not delivered', async () => {
    const { bff } = api();
    expect(await bff.getMyProductReview('ATSUYA0006')).toMatchObject({ eligible: false, reason: 'not-purchased' });
    const err = await bff.writeProductReview('ATSUYA0006', { rating: 5 }).catch((e: unknown) => e);
    expect((err as ApiError).code).toBe('REVIEW_NOT_VERIFIED_PURCHASE');
  });
});
