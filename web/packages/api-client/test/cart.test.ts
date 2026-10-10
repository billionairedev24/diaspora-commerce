import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { ApiError, createApiClient } from '../src';
import { resetDb } from '../src/mocks';
import { mockServer } from '../src/mocks/node';

/** Client against the MSW handlers, keeping the session cookie like a browser would. */
function browserLikeClient() {
  let cookie = '';
  return createApiClient({
    baseUrl: 'http://bff.test',
    fetch: async (req) => {
      if (cookie) req.headers.set('cookie', cookie);
      const res = await fetch(req);
      const set = res.headers.get('set-cookie');
      if (set) cookie = set.split(';')[0] ?? '';
      return res;
    }
  });
}

beforeAll(() => mockServer.listen({ onUnhandledRequest: 'error' }));
afterAll(() => mockServer.close());
beforeEach(() => { resetDb(); });

describe('cart (mock BFF) — stock caps and packages per seller', () => {
  it('adds to cart and caps quantities by stock with a STOCK_LIMIT problem', async () => {
    const api = browserLikeClient();
    const gele = await api.getProduct('AT00000P18');
    const offer = gele.buyBox;
    expect(offer?.availableQty).toBe(3);
    expect(offer?.stockStatus).toBe('LOW_STOCK');
    let cart = await api.addToCart({ offerId: offer?.offerId ?? '', quantity: 1 });
    const line = cart.packages[0]?.lines[0];
    expect(line?.quantity).toBe(1);
    expect(line?.maxQuantity).toBe(3);
    cart = await api.updateCartLine(line?.lineId ?? '', { quantity: 3 });
    expect(cart.itemCount).toBe(3);
    const err = await api.addToCart({ offerId: offer?.offerId ?? '', quantity: 1 }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(409);
    expect((err as ApiError).code).toBe('STOCK_LIMIT');
    expect((err as ApiError).problem).toMatchObject({ available: 3, inCart: 3, requested: 4 });
    const err2 = await api.updateCartLine(line?.lineId ?? '', { quantity: 4 }).catch((e: unknown) => e);
    expect((err2 as ApiError).code).toBe('STOCK_LIMIT');
  });

  it('refuses out-of-stock offers', async () => {
    const api = browserLikeClient();
    const kente = await api.getProduct('AT00000P14');
    expect(kente.buyBox).toBeNull();
    const offerId = kente.otherOffers[0]?.offerId ?? 'AT00000P14-bonwire-weavers-collective';
    const err = await api.addToCart({ offerId, quantity: 1 }).catch((e: unknown) => e);
    expect((err as ApiError).code).toBe('OUT_OF_STOCK');
  });

  it('splits the cart into one package per seller and fulfilment method', async () => {
    const api = browserLikeClient();
    const jollof = await api.getProduct('AT000000P2');
    const teff = await api.getProduct('AT000000P5');
    const berbere = await api.getProduct('AT000000P4');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 2 });
    await api.addToCart({ offerId: teff.buyBox?.offerId ?? '', quantity: 1 });
    const cart = await api.addToCart({ offerId: berbere.buyBox?.offerId ?? '', quantity: 1 });
    expect(cart.packages).toHaveLength(3);
    const sellers = cart.packages.map((p) => `${p.seller.name}|${p.fulfilment}`).sort();
    expect(sellers).toEqual(['Addis Spice House|AFROTRANSACT', 'Addis Spice House|SELLER', 'AfroTransact|AFROTRANSACT']);
    const selfShip = cart.packages.find((p) => p.fulfilment === 'SELLER');
    expect(selfShip?.heading).toMatch(/^Shipped by Addis Spice House/);
    expect(selfShip?.deliveryOptions[0]?.key).toBe('carrier');
    expect(selfShip?.freeShippingNudge).not.toBeNull();
    expect(cart.itemCount).toBe(4);
    expect(cart.sellerCount).toBe(2);
  });

  it('saves for later and moves back to cart', async () => {
    const api = browserLikeClient();
    const p = await api.getProduct('AT000000P9');
    let cart = await api.addToCart({ offerId: p.buyBox?.offerId ?? '', quantity: 2 });
    const lineId = cart.packages[0]?.lines[0]?.lineId ?? '';
    cart = await api.saveForLater(lineId);
    expect(cart.itemCount).toBe(0);
    expect(cart.saved).toHaveLength(1);
    cart = await api.moveToCart(cart.saved[0]?.savedId ?? '');
    expect(cart.saved).toHaveLength(0);
    expect(cart.itemCount).toBe(1);
  });

  it('keeps carts separate per session', async () => {
    const a = browserLikeClient();
    const b = browserLikeClient();
    const p = await a.getProduct('AT000000P9');
    await a.addToCart({ offerId: p.buyBox?.offerId ?? '', quantity: 1 });
    expect((await b.getCart()).itemCount).toBe(0);
    expect((await a.getCart()).itemCount).toBe(1);
  });

  it('selection drives the subtotal', async () => {
    const api = browserLikeClient();
    const p = await api.getProduct('AT000000P2');
    let cart = await api.addToCart({ offerId: p.buyBox?.offerId ?? '', quantity: 2 });
    expect(cart.subtotalMinor).toBe(1298);
    cart = await api.updateCartLine(cart.packages[0]?.lines[0]?.lineId ?? '', { selected: false });
    expect(cart.subtotalMinor).toBe(0);
    expect(cart.selectedCount).toBe(0);
  });
});
