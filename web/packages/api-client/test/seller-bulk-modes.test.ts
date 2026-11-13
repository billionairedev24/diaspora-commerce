import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import { createSellerApiClient, isApiError, type ProductImport, type ProductImportRow, type StockImport, type StockImportRowPage } from '../src';
import { type components } from '../src/generated/gateway-bff-seller';
import { getSellerDb, MOCK_ROLE_COOKIE, resetSellerDb } from '../src/mocks/seller';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';

type S = components['schemas'];

const client = (cookie?: string) =>
  createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', headers: cookie ? { cookie } : {}, fetch: (req) => handleSellerMockRequest(req) });

const csv = (...lines: string[]) => new File([lines.join('\n')], 'file.csv', { type: 'text/csv' });
const named = (name: string, ...lines: string[]) => new File([lines.join('\n')], name, { type: 'text/csv' });

/** A SKU the seller holds in stock and the location the mock keeps it at. */
function heldSku(): { sku: string; node: string } {
  const nodes = ['HOU1', 'ATL1', 'NJ1', 'DFW1'];
  const found = getSellerDb().listings.filter((l) => l.fulfilment === 'AFROTRANSACT').flatMap((l, i) => l.variants.map((v, j) => ({ sku: v.sku, node: nodes[(i + j) % nodes.length] ?? 'HOU1' })));
  const first = found[0];
  if (!first) throw new Error('fixture has Fulfilled by AfroTransact listings');
  return first;
}

describe('Offer upload, price and quantity feed and stock import (S393 to S395)', () => {
  beforeEach(() => {
    resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  });

  it('runtime types follow the contract', () => {
    expectTypeOf<ProductImport>().toExtend<S['ProductImport']>();
    expectTypeOf<ProductImportRow>().toExtend<S['ProductImportRow']>();
    expectTypeOf<StockImport>().toExtend<S['StockImport']>();
    expectTypeOf<StockImportRowPage>().toExtend<S['StockImportRowPage']>();
  });

  it('downloads the offer and price and quantity templates by mode', async () => {
    const api = client();
    const offer = await api.downloadImportTemplate({ format: 'csv', mode: 'OFFER' });
    expect(offer.fileName).toBe('afrotransact-offer.csv');
    const text = await offer.blob.text();
    expect(text).toContain('Barcode');
    expect(text).toContain('Part number');
    expect(text).toContain('Condition');
    expect(text).toContain('Shipping charge');
    expect(text).toContain('Handling days');
    for (const forbidden of ['Image', 'Title', 'Description', 'Attributes']) expect(text).not.toContain(forbidden);
    const feed = await api.downloadImportTemplate({ format: 'csv', mode: 'PRICE_QTY' });
    expect(feed.fileName).toBe('afrotransact-price-qty.csv');
    expect(await feed.blob.text()).toContain('Seller SKU');
  });

  it('previews an offer file: matched, held for tax, bad barcode, unknown page, and offers once started', async () => {
    const api = client();
    const upload = await api.uploadImport(
      csv(
        '"Barcode (UPC, EAN, ISBN)",Seller SKU,Price *,Quantity',
        '4006381333931,A-1,24.99,10',
        '4006381333948,A-2,41.00,4',
        '4006381333932,A-3,9.00,1',
        '4006381333955,A-4,12.50,3',
        '4006381333931,A-5,24.99,2'
      ),
      'OFFER'
    );
    expect(upload).toMatchObject({ mode: 'OFFER', status: 'VALIDATED', totalRows: 5, errorRows: 3 });
    const rows = (await api.listImportRows(upload.id, { page: 0, size: 50 })).items;
    expect(rows[0]).toMatchObject({ status: 'VALID', atin: 'ATADEV0001' });
    expect(rows[1]?.messages.map((m) => m.message)).toContain('We are checking the tax details for this item.');
    expect(rows[2]?.messages[0]?.message).toBe('That barcode does not look right. Check the number on the pack.');
    expect(rows[3]?.messages[0]?.message).toBe('We could not find this product on AfroTransact. Add it as a new product instead.');
    expect(rows[4]?.messages[0]?.message).toBe('This barcode appears twice in your file.');
    await api.startImport(upload.id, true);
    const job = await api.getImport(upload.id);
    expect(job).toMatchObject({ status: 'COMPLETED', offersCreated: 1, heldRows: 1 });
    const done = (await api.listImportRows(upload.id, { page: 0, size: 50 })).items;
    expect(done.map((r) => r.outcome)).toEqual(['OFFER_CREATED', 'HELD', 'SKIPPED', 'SKIPPED', 'SKIPPED']);
    expect(done[1]?.outcomeNote).toBe('We are checking the tax details for this item.');
    // The same file again: the preview says the organisation sent it before.
    const again = await api.uploadImport(csv('"Barcode (UPC, EAN, ISBN)",Seller SKU,Price *,Quantity', '4006381333931,A-1,24.99,10'), 'OFFER');
    const first = await api.uploadImport(csv('"Barcode (UPC, EAN, ISBN)",Seller SKU,Price *,Quantity', '4006381333931,A-1,24.99,10'), 'OFFER');
    expect(again.sameFileAs ?? null).toBeNull();
    expect(first.sameFileAs).toBe(again.id);
  });

  it('refuses an offer file with no price or no way to name the page, and caps rows', async () => {
    const api = client();
    await expect(api.uploadImport(csv('Barcode,Quantity', '4006381333931,1'), 'OFFER')).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    await expect(api.uploadImport(csv('Price,Quantity', '1,1'), 'OFFER')).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    const many = ['Barcode,Price', ...Array.from({ length: 5001 }, () => '4006381333931,1.00')];
    await expect(api.uploadImport(csv(...many), 'OFFER')).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
  });

  it('applies a price and quantity feed to the seller\'s own SKUs only, blank cells unchanged', async () => {
    const api = client();
    const mine = getSellerDb().listings[0]?.variants[0]?.sku ?? '';
    const upload = await api.uploadImport(csv('Seller SKU,Price,Quantity', `${mine},24.99,`, 'NOT-MINE-1,5.00,1', `${mine}2,,`), 'PRICE_QTY');
    expect(upload).toMatchObject({ mode: 'PRICE_QTY', totalRows: 3, errorRows: 2 });
    const rows = (await api.listImportRows(upload.id, { page: 0, size: 50 })).items;
    expect(rows[1]?.messages[0]?.message).toBe('No listing of yours has this SKU.');
    await api.startImport(upload.id, true);
    const job = await api.getImport(upload.id);
    expect(job).toMatchObject({ status: 'COMPLETED', updatedProducts: 1 });
  });

  it('a feed needs edit access to inventory as well', async () => {
    const api = client(`${MOCK_ROLE_COOKIE}=seller.support`);
    await expect(api.uploadImport(csv('Seller SKU,Price', 'X-1,1.00'), 'PRICE_QTY')).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
  });

  it('imports a stock count: preview, reserved units protected, ledger-style result, report, re-upload hint', async () => {
    const api = client();
    const { sku, node } = heldSku();
    const tpl = await api.downloadStockImportTemplate('DELTA');
    expect(tpl.fileName).toBe('afrotransact-stock-delta.csv');
    expect(await tpl.blob.text()).toContain('Change');
    const upload = await api.uploadStockImport(named('count.csv', 'Seller SKU,Location,On hand', `${sku},${node},50`, `${sku},XXX,5`, 'NOPE-1,HOU1,5', `${sku},${node},abc`), 'ABSOLUTE');
    expect(upload).toMatchObject({ mode: 'ABSOLUTE', status: 'VALIDATED', totalRows: 4 });
    const rows = (await api.listStockImportRows(upload.id, { page: 0, size: 50 })).items;
    expect(rows[0]).toMatchObject({ status: 'VALID', before: expect.any(Number), after: 50 });
    expect(rows[1]?.messages[0]?.message).toBe('No stock of yours has this SKU at this location.');
    expect(rows[2]?.messages[0]?.message).toBe('No stock of yours has this SKU at this location.');
    expect(rows[3]?.messages.map((m) => m.message).join(' ')).toContain('appear twice');
    await expect(api.startStockImport(upload.id, false)).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
    await expect(api.startStockImport(upload.id, true)).resolves.toMatchObject({ status: 'QUEUED' });
    const done = await api.getStockImport(upload.id);
    expect(done).toMatchObject({ status: 'COMPLETED', appliedRows: 1 });
    const report = await api.downloadStockImportReport(upload.id);
    expect(await report.blob.text()).toContain('Message');
    expect((await api.listStockImports(0, 10)).items[0]?.id).toBe(upload.id);
    // The same file again says it was sent before.
    const again = await api.uploadStockImport(named('count.csv', 'Seller SKU,Location,On hand', `${sku},${node},50`, `${sku},XXX,5`, 'NOPE-1,HOU1,5', `${sku},${node},abc`), 'ABSOLUTE');
    expect(again.sameFileAs).toBe(upload.id);
  });

  it('never lets a count go below the reserved units, below zero, or past the limit', async () => {
    const api = client();
    const { sku, node } = heldSku();
    const inv = await api.getInventory(false);
    const pos = inv.positions.find((p) => p.sku === sku);
    const reserved = pos?.reserved ?? 0;
    const files = [`${sku},${node},99999999`, `${sku},${node},100000`];
    for (const line of files) {
      const u = await api.uploadStockImport(named('c.csv', 'Seller SKU,Location,On hand', line), 'ABSOLUTE');
      expect(u.errorRows).toBe(1);
    }
    const neg = await api.uploadStockImport(named('c.csv', 'Seller SKU,Location,Change', `${sku},${node},-100000`), 'DELTA');
    expect(neg.errorRows).toBe(1);
    if (reserved > 0) {
      const low = await api.uploadStockImport(named('c2.csv', 'Seller SKU,Location,On hand', `${sku},${node},${reserved - 1}`), 'ABSOLUTE');
      expect(low.errorRows).toBe(1);
      const rows = await api.listStockImportRows(low.id, { page: 0, size: 5 });
      expect(rows.items[0]?.messages[0]?.message).toContain('reserved for orders');
    }
  });

  it('refuses stock files that are not csv or have no header, and needs edit access to inventory', async () => {
    const api = client();
    await expect(api.uploadStockImport(new File(['x'], 'count.xlsx'), 'ABSOLUTE')).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    await expect(api.uploadStockImport(named('c.csv', 'Foo,Bar', 'a,b'), 'ABSOLUTE')).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    const support = client(`${MOCK_ROLE_COOKIE}=seller.support`);
    await expect(support.uploadStockImport(named('c.csv', 'Seller SKU,Location,On hand', 'A,B,1'), 'ABSOLUTE')).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
  });
});
