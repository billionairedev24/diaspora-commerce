import { beforeEach, describe, expect, it } from 'vitest';

import { createSellerApiClient, isApiError } from '../src';
import { getSellerDb, resetSellerDb } from '../src/mocks/seller';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';

const client = () => createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', fetch: (req) => handleSellerMockRequest(req) });

/**
 * A seller's own shipping is behind a staff switch that is OFF by default. Off, nothing about it is available and the server
 * refuses a charge; on, a seller sets one flat charge per order (never per product) within $0 to $100.
 */
describe('seller self-shipping (staff switch, off by default)', () => {
  beforeEach(() => {
    resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  });

  it('is not available by default and refuses a shipping charge with 409', async () => {
    const api = client();
    expect(await api.getSelfShipping()).toMatchObject({ available: false, enabled: false, chargeMinor: 0 });
    await expect(api.setSelfShipping({ enabled: true, chargeMinor: 500 })).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
  });

  it('with the switch on, saves one flat charge and refuses one outside $0 to $100', async () => {
    getSellerDb().selfShipping.available = true;
    const api = client();
    expect(await api.getSelfShipping()).toMatchObject({ available: true, enabled: false });
    expect(await api.setSelfShipping({ enabled: true, chargeMinor: 750 })).toMatchObject({ available: true, enabled: true, chargeMinor: 750 });
    expect(await api.getSelfShipping()).toMatchObject({ enabled: true, chargeMinor: 750 });
    await expect(api.setSelfShipping({ enabled: true, chargeMinor: 10_001 })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    expect(await api.setSelfShipping({ enabled: true, chargeMinor: 0 })).toMatchObject({ chargeMinor: 0 });
  });

  it('turning the switch off again hides the seller’s opt-in without deleting it', async () => {
    const db = getSellerDb();
    db.selfShipping.available = true;
    const api = client();
    await api.setSelfShipping({ enabled: true, chargeMinor: 500 });
    db.selfShipping.available = false;
    expect(await api.getSelfShipping()).toMatchObject({ available: false, enabled: false, chargeMinor: 500 });
  });
});
