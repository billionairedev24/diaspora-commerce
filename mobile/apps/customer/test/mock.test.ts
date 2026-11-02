import { CartSchema, guideCell, MeSchema, pickerDimensions, type ProductDetail, ProductDetailSchema, SearchPageSchema, selectedRowIndex, selectedSize, variantForSize } from '@afrotransact/api-client';
import { beforeEach, describe, expect, it } from 'vitest';

import { resetMockDb } from '../src/api/mock/db';
import { mockFetch } from '../src/api/mock/router';
import { atZoned } from '../src/api/mock/zoned';

const req = (path: string, init: RequestInit = {}) =>
  mockFetch(new Request(`http://localhost:8200${path}`, { ...init, headers: { Authorization: 'Bearer t', 'Content-Type': 'application/json', ...(init.headers ?? {}) } }));

describe('dev mock backend', () => {
  beforeEach(() => resetMockDb());

  it('serves contract-valid BFF responses', async () => {
    expect(SearchPageSchema.safeParse(await (await req('/v1/search?q=rice')).json()).success).toBe(true);
    expect(ProductDetailSchema.safeParse(await (await req('/v1/products/ATSHITO001')).json()).success).toBe(true);
    expect(CartSchema.safeParse(await (await req('/v1/cart')).json()).success).toBe(true);
    expect(MeSchema.safeParse(await (await req('/v1/me')).json()).success).toBe(true);
  });

  it('requires a bearer token outside public routes', async () => {
    const res = await mockFetch(new Request('http://localhost:8200/v1/cart'));
    expect(res.status).toBe(401);
  });

  it('places an order, clears the cart and returns a delivery code', async () => {
    const summary = (await (await req('/v1/checkout/summary')).json()) as { packages: Array<{ packageId: string }> };
    const res = await req('/v1/checkout/orders', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'co_00000000000000000000' },
      body: JSON.stringify({ addressId: 'a1', paymentMethodId: 'pm_visa4242', deliverySelections: summary.packages.map((p, i) => ({ packageId: p.packageId, option: i === 0 ? 'afrotransact_delivery' : 'carrier', preferences: { handover: 'hand' } })) })
    });
    expect(res.status).toBe(201);
    const conf = (await res.json()) as { orderNumber: string; shipments: Array<{ deliveryCode: string | null }> };
    expect(conf.shipments[0]?.deliveryCode).toMatch(/^\d{4}$/);
    const cart = CartSchema.parse(await (await req('/v1/cart')).json());
    expect(cart.itemCount).toBe(0);
  });

  it('quotes the refund before cancelling a booking', async () => {
    const mine = (await (await req('/v1/bookings/mine')).json()) as Array<{ id: string }>;
    const q = (await (await req(`/v1/bookings/${mine[0]?.id}/cancellation-quote`)).json()) as { refundMinor: number; depositMinor: number };
    expect(q.depositMinor).toBeGreaterThan(0);
    expect(q.refundMinor).toBe(q.depositMinor);
  });

  it('keeps provider calendars in provider time', () => {
    expect(atZoned('2026-10-12', 10 * 60, 'America/Chicago').toISOString()).toBe('2026-10-12T15:00:00.000Z');
    expect(atZoned('2026-12-14', 9 * 60, 'America/New_York').toISOString()).toBe('2026-12-14T14:00:00.000Z');
  });

  it('serves a sized garment with option chips and its size chart, and keeps the other sizes out of search', async () => {
    const res = await req('/v1/products/ATADIRE000');
    const body = await res.json();
    expect(ProductDetailSchema.safeParse(body).success).toBe(true);
    const d = body as ProductDetail;
    expect(d.sizeChart?.rows.map((r) => r.size)).toEqual(['S', 'M', 'UK 12', 'Free size']);
    expect(d.product.variants).toHaveLength(8);
    const dims = pickerDimensions(d.product.variants);
    expect(dims.map((x) => x.name)).toEqual(['Size', 'Color']);
    expect(selectedSize(d.product.variants)).toBe('S');
    expect(selectedRowIndex(d.sizeChart!, 'S')).toBe(0);
    expect(variantForSize(d.product.variants, 'Free size')?.state).toBe('available');
    // Free size is sold out in Indigo adire: from there the chip goes to the colour that still has it.
    const indigo = (await (await req('/v1/products/ATADIRE010')).json()) as ProductDetail;
    expect(selectedSize(indigo.product.variants)).toBe('S');
    expect(variantForSize(indigo.product.variants, 'Free size')).toEqual({ value: 'Free size', state: 'elsewhere', atin: 'ATADIRE003' });
    expect(guideCell(d.sizeChart!, d.sizeChart!.rows[0]!, 'bust', 'IN')).toBe('33.1–34.6');

    const found = (await (await req('/v1/search?q=adire')).json()) as { hits: Array<{ atin: string }> };
    expect(found.hits.map((i) => i.atin)).toEqual(['ATADIRE000']);
  });

  it('serves products without sizes as before: no chips, no chart', async () => {
    const d = (await (await req('/v1/products/ATSHITO001')).json()) as ProductDetail;
    expect(d.sizeChart).toBeNull();
    expect(pickerDimensions(d.product.variants)).toEqual([]);
  });
});
