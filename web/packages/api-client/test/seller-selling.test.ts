import { beforeEach, describe, expect, it } from 'vitest';

import { type ApplyToSell, createSellerApiClient, isApiError, SellerSellingStatusSchema, SellingApprovalsSchema } from '../src';
import { decideSellingMock, resetSellerDb, resetSellingMocks, SELLER_ORG } from '../src/mocks/seller';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';

const client = () => createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', headers: {}, fetch: (req) => handleSellerMockRequest(req) });
const future = new Date(Date.now() + 200 * 86_400_000).toISOString().slice(0, 10);
const recent = new Date(Date.now() - 20 * 86_400_000).toISOString().slice(0, 10);
const supplementDocs: NonNullable<ApplyToSell['documents']> = [
  { kind: 'PERMIT_LICENCE', assetId: 'asset-permit', expiresOn: future },
  { kind: 'SAFETY_CERTIFICATE', assetId: 'asset-lab' },
  { kind: 'LIABILITY_INSURANCE', assetId: 'asset-policy', expiresOn: future, namesAfroTransact: true }
];

describe('selling approvals (mock BFF): the product form chip, the approvals page and applying', () => {
  beforeEach(() => {
    resetSellerDb();
    resetSellingMocks();
  });

  it('answers SELL, APPLY or NOT_AVAILABLE for a category and brand, in plain words', async () => {
    const api = client();
    await api.setActiveOrg(SELLER_ORG);
    expect((await api.getSellingStatus({ categoryCode: 'packaged-snacks', brand: 'Tasty' })).level).toBe('SELL');
    const apply = await api.getSellingStatus({ categoryCode: 'dietary-supplements' });
    expect(apply).toMatchObject({ level: 'APPLY', missing: [{ state: 'NOT_APPLIED', canApply: true }] });
    expect(apply.reason).toContain('You need approval');
    const blocked = await api.getSellingStatus({ categoryCode: 'gift-baskets-bundles' });
    expect(blocked).toMatchObject({ level: 'NOT_AVAILABLE' });
    expect(blocked.reason).toContain('bundles');
    expect((await api.getSellingStatus({ categoryCode: 'phones', brand: 'Apple' })).level).toBe('APPLY');
    expect((await api.getSellingStatus({ categoryCode: 'adult-clothing-footwear', brand: "Nike" })).level).toBe('APPLY');
    expect((await api.getSellingStatus({ categoryCode: 'candles', brand: 'Nike' })).level).toBe('SELL');
    expect((await api.getSellingStatus({ categoryCode: 'perfumes-fragrances', brand: 'Gucci' })).level).toBe('APPLY');
  });

  it('jewelry needs approval only for gold, silver, gemstones or watches, and says what it is waiting for', async () => {
    const api = client();
    await api.setActiveOrg(SELLER_ORG);
    expect((await api.getSellingStatus({ categoryCode: 'jewelry-accessories', material: 'beads' })).level).toBe('SELL');
    expect((await api.getSellingStatus({ categoryCode: 'jewelry-accessories', material: '18k Gold' })).level).toBe('APPLY');
    expect((await api.getSellingStatus({ categoryCode: 'jewelry-accessories', productTypeCode: 'wrist-watch' })).level).toBe('APPLY');
    expect(await api.getSellingStatus({ categoryCode: 'jewelry-accessories' })).toMatchObject({ level: 'SELL', pendingFacts: ['material'] });
  });

  it('applying names the documents to fix, files once, and the status follows the application', async () => {
    const api = client();
    await api.setActiveOrg(SELLER_ORG);
    await expect(api.applyToSell({ scopeType: 'CATEGORY', scopeKey: 'dietary-supplements', documents: [{ kind: 'PERMIT_LICENCE', assetId: 'a', expiresOn: '2020-01-01' }] })).rejects.toSatisfy(
      (e) => isApiError(e) && e.status === 422 && JSON.stringify(e).includes('documents.LIABILITY_INSURANCE')
    );
    await expect(api.applyToSell({ scopeType: 'CATEGORY', scopeKey: 'packaged-snacks' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    const filed = await api.applyToSell({ scopeType: 'CATEGORY', scopeKey: 'dietary-supplements', documents: supplementDocs });
    expect(filed).toMatchObject({ status: 'PENDING_REVIEW', statusWords: 'With our team', kind: 'NEW' });
    expect((await api.applyToSell({ scopeType: 'CATEGORY', scopeKey: 'dietary-supplements', documents: supplementDocs })).id).toBe(filed.id);
    expect((await api.getSellingStatus({ categoryCode: 'dietary-supplements' })).missing[0]).toMatchObject({ state: 'APPLIED', canApply: false });
    expect(await api.listSellingApplications()).toHaveLength(1);
    expect((await api.getSellingApprovals()).approvals.find((r) => r.scopeKey === 'dietary-supplements')).toMatchObject({ state: 'APPLIED', statusWords: 'With our team' });

    decideSellingMock(SELLER_ORG, filed.id, 'APPROVED');
    expect((await api.getSellingStatus({ categoryCode: 'dietary-supplements' })).level).toBe('SELL');
    expect((await api.getSellingApprovals()).approvals.find((r) => r.scopeKey === 'dietary-supplements')).toMatchObject({ state: 'APPROVED', statusWords: 'Approved' });
  });

  it('a request for information is answered by resubmitting, and a disqualification cannot be appealed', async () => {
    const api = client();
    await api.setActiveOrg(SELLER_ORG);
    const filed = await api.applyToSell({ scopeType: 'BRAND', scopeKey: 'gucci', documents: [
      { kind: 'BRAND_AUTHORIZATION', assetId: 'a1' }, { kind: 'PURCHASE_INVOICE', assetId: 'a2', issuedOn: recent }, { kind: 'PHOTOS_ALL_SIDES', assetId: 'a3' }] });
    decideSellingMock(SELLER_ORG, filed.id, 'INFO_REQUESTED');
    expect((await api.getSellingStatus({ categoryCode: 'bags-purses', brand: 'Gucci' })).missing[0]).toMatchObject({ state: 'NEEDS_ATTENTION', applicationStatus: 'INFO_REQUESTED' });
    expect(await api.resubmitSellingApplication(filed.id, {})).toMatchObject({ status: 'PENDING_REVIEW' });
    await expect(api.resubmitSellingApplication(filed.id, {})).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
    decideSellingMock(SELLER_ORG, filed.id, 'DISQUALIFIED');
    const s = await api.getSellingStatus({ categoryCode: 'bags-purses', brand: 'Gucci' });
    expect(s.level).toBe('NOT_AVAILABLE');
    expect(s.reason).toContain('cannot be appealed');
    await expect(api.applyToSell({ scopeType: 'BRAND', scopeKey: 'gucci', documents: [] })).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
  });

  it('food service is one capability for every food service category, and the product form reads it', async () => {
    const api = client();
    await api.setActiveOrg(SELLER_ORG);
    expect((await api.getSellingCapabilities()).foodService.approved).toBe(false);
    const s = await api.getSellingStatus({ categoryCode: 'bakery-products' });
    expect(s.missing[0]?.scope).toEqual({ type: 'CAPABILITY', key: 'FOOD_SERVICE' });
    const filed = await api.applyToSell({ scopeType: 'CAPABILITY', scopeKey: 'FOOD_SERVICE', documents: [
      { kind: 'PERMIT_LICENCE', assetId: 'p', expiresOn: future }, { kind: 'LIABILITY_INSURANCE', assetId: 'i', expiresOn: future, namesAfroTransact: true }] });
    decideSellingMock(SELLER_ORG, filed.id, 'APPROVED');
    expect((await api.getSellingCapabilities()).foodService.approved).toBe(true);
    expect((await api.getSellingStatus({ categoryCode: 'prepared-meals' })).level).toBe('SELL');
  });

  it('every answer matches the contract schemas', async () => {
    const api = client();
    await api.setActiveOrg(SELLER_ORG);
    const approvals = await api.getSellingApprovals();
    expect(SellingApprovalsSchema.parse(approvals).approvals.length).toBeGreaterThan(5);
    expect(SellerSellingStatusSchema.parse(await api.getSellingStatus({ categoryCode: 'dietary-supplements' })).level).toBe('APPLY');
  });
});
