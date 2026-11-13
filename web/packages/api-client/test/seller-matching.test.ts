import { beforeEach, describe, expect, it } from 'vitest';

import { createSellerApiClient, isApiError } from '../src';
import { approveMockPage, getSellerDb, gtinWith, MOCK_GTIN, resetSellerDb, SELLER_ORG, verdictForFile } from '../src/mocks/seller';
import { MOCK_FOOD_ORG } from '../src/mocks/seller/departments';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';

const client = () => createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', headers: {}, fetch: (req) => handleSellerMockRequest(req) });
const problem = async (p: Promise<unknown>) => {
  try {
    await p;
  } catch (e) {
    if (isApiError(e)) return e;
    throw e;
  }
  throw new Error('expected a problem');
};

describe('catalog matching (mock BFF, S382)', () => {
  beforeEach(() => {
    resetSellerDb();
  });

  it('answers MATCH, NO_MATCH, INVALID and RESTRICTED in plain words', async () => {
    const { matching } = client();
    const match = await matching.match({ gtin: ' 0123-4567 8905 ' });
    expect(match).toMatchObject({ result: 'MATCH', canOffer: true, title: 'Raw Shea Butter, 8 oz', brand: 'Kente Naturals', sellerCount: 2 });
    expect(match.variants).toHaveLength(1);
    expect(await matching.match({ gtin: '9780306406157' })).toMatchObject({ result: 'NO_MATCH', canCreate: true, canOffer: false });
    const wrong = await matching.match({ gtin: '012345678900' });
    expect(wrong).toMatchObject({ result: 'INVALID', suggestion: MOCK_GTIN.live });
    expect(wrong.message).toBe(`That barcode does not look right. Did you mean ${MOCK_GTIN.live}?`);
    expect(await matching.match({ gtin: '021234567893' })).toMatchObject({ result: 'RESTRICTED', message: 'This number is for store use or coupons, not a product barcode.' });
    expect(await matching.match({ gtin: '12345' })).toMatchObject({ result: 'INVALID', message: 'Barcodes have 8, 12, 13 or 14 digits.' });
    expect((await problem(matching.match({ gtin: '' }))).status).toBe(422);
  });

  it('finds products by name with a photo, brand, size and seller count, and shows nothing about a seller', async () => {
    const { matching } = client();
    const found = await matching.candidates({ query: 'raw shea butter 8oz' });
    expect(found).toMatchObject({ tooShort: false, searchUnavailable: false, suggestedCategory: { id: 'pt-butters' } });
    expect(found.candidates[0]).toMatchObject({ title: 'Raw Shea Butter, 8 oz', brand: 'Kente Naturals', sizeLabel: '8 oz', kind: 'SAME_NAME', sellerCount: 2, canOffer: true });
    expect(found.candidates[0]?.image?.url).toBeTruthy();
    expect(JSON.stringify(found)).not.toMatch(/org-|ownerOrg|priceMinor/);
    expect(found.candidates.length).toBeLessThanOrEqual(6);
  });

  it('never lists a page that is not live, and a different size is only similar', async () => {
    const { matching } = client();
    expect((await matching.candidates({ query: 'ankara fabric 6 yards' })).candidates).toEqual([]);
    const other = await matching.candidates({ query: 'raw shea butter 16oz' });
    expect(other.candidates[0]).toMatchObject({ kind: 'SIMILAR', likely: false });
  });

  it('answers a short name, an unreachable search and a category chip', async () => {
    const { matching } = client();
    expect(await matching.candidates({ query: 'ra' })).toMatchObject({ candidates: [], tooShort: true });
    expect(await matching.candidates({ query: 'offline rice' })).toMatchObject({ candidates: [], searchUnavailable: true });
    const chip = await matching.candidates({ query: 'shea', categoryId: 'pt-butters' });
    expect(chip.category).toMatchObject({ id: 'pt-butters' });
    expect(chip.suggestedCategory ?? null).toBeNull();
  });

  it('needs the one-tap size answer and only a page that was shown; "Not here" is recorded and queues a likely duplicate', async () => {
    const { matching } = client();
    const found = await matching.candidates({ query: 'raw shea butter 8oz' });
    const atin = found.candidates[0]?.atin ?? '';
    expect((await problem(matching.decide({ choice: 'SELL_EXISTING', query: 'raw shea butter 8oz', atin }))).status).toBe(422);
    expect(await matching.decide({ choice: 'SELL_EXISTING', query: 'raw shea butter 8oz', atin, sizeConfirmed: true })).toMatchObject({ canOffer: true, atin });
    expect((await problem(matching.decide({ choice: 'SELL_EXISTING', query: 'raw shea butter 8oz', atin: 'ATNIKEV001', sizeConfirmed: true }))).status).toBe(422);
    expect(await matching.decide({ choice: 'NONE_OF_THESE', query: 'raw shea butter 8oz' })).toMatchObject({ choice: 'NONE_OF_THESE', possibleDuplicateQueued: true });
    expect(getSellerDb().matching?.decisions.map((d) => d.choice)).toEqual(['SELL_EXISTING', 'NONE_OF_THESE']);
  });

  it('matches UPC-A and EAN-13 of the same item, and lists every child of a page', async () => {
    const { matching } = client();
    expect((await matching.match({ gtin: `0${MOCK_GTIN.live}` })).title).toBe('Raw Shea Butter, 8 oz');
    const kaftan = await matching.match({ gtin: MOCK_GTIN.sizeA });
    expect(kaftan.variants.map((v) => v.options.Size)).toEqual(['Small', 'Large']);
    expect(kaftan.atin).toBe(kaftan.variants[0]?.atin);
  });

  it('says why a page cannot be sold yet', async () => {
    const { matching } = client();
    const gated = await matching.match({ gtin: MOCK_GTIN.gated });
    expect(gated.canOffer).toBe(false);
    expect(gated.blockers.map((b) => b.code)).toEqual(['offers.selling-approval-required']);
    const pending = await matching.match({ gtin: MOCK_GTIN.pending });
    expect(pending.blockers.map((b) => b.code)).toEqual(['catalog.page-not-live']);
    expect(pending.canCreate).toBe(false);
  });

  it('sells a page with offer terms only, and never takes page content', async () => {
    const { matching } = client();
    const m = await matching.match({ gtin: MOCK_GTIN.live });
    const made = await matching.createOffer({ atin: m.atin ?? '', priceMinor: 1250, fulfilment: 'SELLER', quantity: 4 });
    expect(made).toMatchObject({ status: 'ACTIVE', sellerSku: `${m.atin}-NEW`, message: 'Your offer is live.' });
    const again = await problem(matching.createOffer({ atin: m.atin ?? '', priceMinor: 1250, fulfilment: 'SELLER', quantity: 4 }));
    expect(again).toMatchObject({ status: 409, code: 'OFFER_EXISTS' });

    // The type does not let a client send page content, so ask the BFF directly.
    const res = await handleSellerMockRequest(new Request('http://seller.test/api/bff/v1/seller/offers', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ atin: 'ATSHEAV001', priceMinor: 900, fulfilment: 'SELLER', quantity: 1, title: 'Mine', images: [] }) }));
    const body = (await res.json()) as { code: string; errors: Record<string, string> };
    expect(res.status).toBe(422);
    expect(body.code).toBe('PAGE_CONTENT_LOCKED');
    expect(body.errors).toEqual({ title: 'That detail belongs to the product page. Suggest a change instead.', images: 'That detail belongs to the product page. Suggest a change instead.' });
  });

  it('holds an offer for tax until the seller’s own tax decision is resolved', async () => {
    const { matching } = client();
    const m = await matching.match({ gtin: MOCK_GTIN.taxHeld });
    const held = await matching.createOffer({ atin: m.atin ?? '', priceMinor: 600, fulfilment: 'SELLER', quantity: 9 });
    expect(held).toMatchObject({ status: 'TAX_HELD', heldReason: 'TAX_DECISION_MISSING', message: 'We are checking the tax details for this item.' });
  });

  it('refuses a gated brand, a page that is not live, a used item without a note and a package outside Fulfilled by AfroTransact', async () => {
    const { matching } = client();
    const gated = await matching.match({ gtin: MOCK_GTIN.gated });
    expect(await problem(matching.createOffer({ atin: gated.atin ?? '', priceMinor: 900, fulfilment: 'SELLER', quantity: 1 }))).toMatchObject({ status: 422, code: 'SELLING_APPROVAL_REQUIRED' });
    const pending = await matching.match({ gtin: MOCK_GTIN.pending });
    expect((await problem(matching.createOffer({ atin: pending.atin ?? '', priceMinor: 900, fulfilment: 'SELLER', quantity: 1 }))).status).toBe(404);
    const live = await matching.match({ gtin: MOCK_GTIN.live });
    const used = await problem(matching.createOffer({ atin: live.atin ?? '', priceMinor: 900, fulfilment: 'SELLER', quantity: 1, condition: 'USED_GOOD' }));
    expect(used.fieldErrors.conditionNote).toBe('Describe the condition');
    const box = await problem(matching.createOffer({ atin: live.atin ?? '', priceMinor: 900, fulfilment: 'SELLER', quantity: 1, packaging: { lengthIn: 1, widthIn: 1, heightIn: 1, weightLb: 1 } }));
    expect(box.fieldErrors.packaging).toBeDefined();
  });

  it('files, lists and withdraws change requests, once each while waiting', async () => {
    const { matching } = client();
    const m = await matching.match({ gtin: MOCK_GTIN.live });
    // A change needs an offer on the page; a wrong-product report and a duplicate do not.
    expect(await problem(matching.fileChangeRequest(m.parentAtin ?? '', { type: 'TITLE', payload: { value: 'Raw Shea Butter, 8 oz jar' } }))).toMatchObject({ status: 403, code: 'NOT_YOUR_OFFER' });
    const wrongProduct = await matching.fileChangeRequest(m.atin ?? '', { type: 'WRONG_MATCH', payload: { variantAtin: m.atin }, note: 'Mine is 16 oz' });
    expect(wrongProduct).toMatchObject({ status: 'PENDING', type: 'WRONG_MATCH', note: 'Mine is 16 oz' });
    expect(await problem(matching.fileChangeRequest(m.atin ?? '', { type: 'WRONG_MATCH', payload: { variantAtin: m.atin } }))).toMatchObject({ status: 409, code: 'CHANGE_REQUEST_EXISTS' });
    await matching.createOffer({ atin: m.atin ?? '', priceMinor: 1250, fulfilment: 'SELLER', quantity: 4 });
    const title = await matching.fileChangeRequest(m.parentAtin ?? '', { type: 'TITLE', payload: { value: 'Raw Shea Butter, 8 oz jar' } });
    expect((await matching.listChangeRequests({ status: 'PENDING' })).items.map((r) => r.type)).toEqual(['TITLE', 'WRONG_MATCH']);
    expect(await matching.withdrawChangeRequest(title.id)).toMatchObject({ status: 'WITHDRAWN' });
    expect(await problem(matching.withdrawChangeRequest(title.id))).toMatchObject({ status: 409, code: 'CHANGE_REQUEST_DECIDED' });
    expect((await matching.listChangeRequests({ status: 'PENDING' })).items.map((r) => r.type)).toEqual(['WRONG_MATCH']);
  });

  it('treats a barcode nobody has used as no page, for every organisation (the creator-and-seller path is the seller-center spec add-product.test.tsx)', async () => {
    const api = client();
    const gtin = gtinWith('978000000001');
    await api.setActiveOrg(SELLER_ORG);
    expect((await api.matching.match({ gtin })).result).toBe('NO_MATCH');
    expect(approveMockPage(getSellerDb(), gtin)).toBe(false);
    await api.setActiveOrg(MOCK_FOOD_ORG);
    expect((await api.matching.match({ gtin })).result).toBe('NO_MATCH');
  });
});

describe('image rules in the mock upload (S388)', () => {
  it('judges a file by its name for the slot it is uploaded for', () => {
    expect(verdictForFile('shea-front.jpg', 'MAIN')).toMatchObject({ verdict: 'PASS', checks: [], width: 1600 });
    expect(verdictForFile('small-front.jpg', 'MAIN')).toMatchObject({ verdict: 'REJECT', width: 640, height: 480 });
    expect(verdictForFile('small-front.jpg', 'MAIN').checks[0]).toMatchObject({ code: 'MAIN_TOO_SMALL', params: { w: 640, h: 480, min: 1000 } });
    expect(verdictForFile('small-side.jpg', 'OTHER').checks[0]?.code).toBe('TOO_SMALL');
    expect(verdictForFile('low-side.jpg', 'OTHER')).toMatchObject({ verdict: 'WARN' });
    expect(verdictForFile('white-front.jpg', 'MAIN').checks.map((c) => c.code)).toEqual(['NOT_WHITE']);
    expect(verdictForFile('white-front.jpg', 'OTHER').checks).toEqual([]);
    expect(verdictForFile('fill-front.jpg', 'MAIN')).toMatchObject({ verdict: 'WARN' });
    expect(verdictForFile('underfill-front.jpg', 'MAIN')).toMatchObject({ verdict: 'REJECT' });
    expect(verdictForFile('held-front.jpg', 'MAIN')).toMatchObject({ verdict: 'HOLD' });
    expect(verdictForFile('border-white-held.jpg', 'MAIN').verdict).toBe('REJECT');
  });

  it('answers an upload with the slot, the verdict and the checks, and the slot reaches the check', async () => {
    resetSellerDb();
    const api = client();
    const ticket = await api.requestUpload({ purpose: 'product-image', fileName: 'small-front.jpg', contentType: 'image/jpeg', size: 1000, slot: 'MAIN', title: 'Raw Shea Butter', brand: 'Kente Naturals', productTypeCode: 'beauty' });
    const asset = await api.completeUpload(ticket.assetId);
    expect(asset).toMatchObject({ status: 'REJECTED', slot: 'MAIN', verdict: 'REJECT' });
    expect(asset.checks[0]).toMatchObject({ code: 'MAIN_TOO_SMALL', level: 'REJECT' });
    const extra = await api.completeUpload((await api.requestUpload({ purpose: 'product-image', fileName: 'small-side.jpg', contentType: 'image/jpeg', size: 1000 })).assetId);
    expect(extra).toMatchObject({ slot: 'OTHER', verdict: 'REJECT' });
    expect(extra.checks[0]?.code).toBe('TOO_SMALL');
    const other = await api.completeUpload((await api.requestUpload({ purpose: 'storefront', fileName: 'logo.png', contentType: 'image/png', size: 1000 })).assetId);
    expect(other.verdict).toBeUndefined();
    expect(other.checks).toEqual([]);
  });

  it('judges a library image for its slot, and the library says why in the same codes', async () => {
    resetSellerDb();
    const api = client();
    await api.setActiveOrg(SELLER_ORG);
    for (const fileName of ['SMALL-1.MAIN.jpg', 'HELD-2.MAIN.jpg', 'GOOD-3.MAIN.jpg']) {
      const t = await api.requestUpload({ purpose: 'product-image', fileName, contentType: 'image/jpeg', size: 1000, library: true });
      await api.completeUpload(t.assetId);
    }
    const page = await api.listImageLibrary({ page: 0, size: 50 });
    const by = (sku: string) => page.items.find((i) => i.sku === sku);
    expect(by('SMALL-1')).toMatchObject({ status: 'REJECTED', verdict: 'REJECT', url: null });
    expect(by('SMALL-1')?.checks[0]).toMatchObject({ code: 'MAIN_TOO_SMALL', params: { w: 640, h: 480 } });
    expect(by('HELD-2')).toMatchObject({ status: 'FLAGGED', verdict: 'HOLD' });
    expect(by('HELD-2')?.checks[0]?.code).toBe('HELD_TEXT');
    expect(by('GOOD-3')).toMatchObject({ status: 'READY', verdict: 'PASS', checks: [] });
  });

  it('lists Improve your image tasks, failing first', async () => {
    resetSellerDb();
    const api = client();
    await api.setActiveOrg(SELLER_ORG);
    const { items } = await api.matching.listImageTasks();
    expect(items.map((t) => t.state)).toEqual(['FAIL', 'IMPROVE']);
    expect(items[0]).toMatchObject({ daysLeft: 48, hidden: false });
    expect(items[0]?.reasons.map((r) => r.code)).toEqual(['NOT_WHITE', 'MAIN_TOO_SMALL']);
  });
});
