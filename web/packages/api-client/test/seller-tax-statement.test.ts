import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import { createSellerApiClient, type SellerTaxStatement } from '../src';
import { type components } from '../src/generated/gateway-bff-seller';
import { resetSellerDb } from '../src/mocks/seller';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';

const client = () => createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', fetch: (req) => handleSellerMockRequest(req) });

describe('seller tax statement', () => {
  beforeEach(() => resetSellerDb());

  it('stays aligned with the contract', () => {
    expectTypeOf<SellerTaxStatement>().toExtend<components['schemas']['SellerTaxStatement']>();
  });

  it('lists the tax collected per state with totals, and the months to pick', async () => {
    const api = client();
    const now = await api.getTaxStatement('');
    expect(now.months).toHaveLength(24);
    expect(now.month).toBe(now.months[0]);
    expect(now.rows.map((r) => r.state)).toEqual(['TX', 'NY']);
    expect(now.totals.netTaxMinor).toBe(1_527 + 888);
    expect(now.totals.taxCollectedMinor - now.totals.taxRefundedMinor).toBe(now.totals.netTaxMinor);
  });

  it('is empty for a quiet month and refuses a malformed one', async () => {
    const api = client();
    const old = (await api.getTaxStatement('')).months[10] ?? '';
    expect((await api.getTaxStatement(old)).rows).toEqual([]);
    await expect(api.getTaxStatement('September')).rejects.toMatchObject({ status: 422 });
  });
});
