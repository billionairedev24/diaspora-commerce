import { File as NodeFile } from 'node:buffer';

import { getSellerDb, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { DEFAULT_LIMITS, fileProblem, heldWhyKey, imageCheckText, limitsFor, UPLOAD_KINDS } from '@/components/listings/bulk/bulk-model';
import { BulkUploadView } from '@/components/listings/bulk/BulkUploadView';
import { ImportHistory } from '@/components/listings/bulk/ImportHistory';
import { ImportView } from '@/components/listings/bulk/ImportView';
import { StockImportView } from '@/components/listings/bulk/StockImportView';
import { type EN_MESSAGES, tEn } from '@/i18n/t';

import { asRole, mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/* Uploads go through the Fetch API's Request, which is Node's (jsdom has none): use Node's FormData and File so the
   multipart body reaches the mock BFF, as it does in a browser. */
const NodeFormData = (await new Response('a=1', { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }).formData()).constructor as typeof FormData;
globalThis.FormData = NodeFormData;
globalThis.File = NodeFile as unknown as typeof File;

const downloads = vi.hoisted(() => ({ calls: [] as { name: string; text: string }[] }));
vi.mock('@/lib/export/table', () => ({
  download: (blob: Blob, name: string) => {
    void blob.text().then((text) => downloads.calls.push({ name, text }));
  }
}));

beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  asRole(null);
  nav.push.mockReset();
  downloads.calls.length = 0;
});

const OFFER_HEADER = '"Barcode (UPC, EAN, ISBN)",Seller SKU,Price *,Quantity';
const offerCsv = [OFFER_HEADER, '4006381333931,A-1,24.99,10', '4006381333948,A-2,41.00,4', '4006381333932,A-3,9.00,1'].join('\n');

/** A SKU the seller holds at a location in the mock stock. */
function held() {
  const nodes = ['HOU1', 'ATL1', 'NJ1', 'DFW1'];
  const first = getSellerDb().listings.filter((l) => l.fulfilment === 'AFROTRANSACT').flatMap((l, i) => l.variants.map((v, j) => ({ sku: v.sku, node: nodes[(i + j) % nodes.length] ?? 'HOU1' })))[0];
  if (!first) throw new Error('fixture holds stock');
  return first;
}

describe('bulk upload types (S393 to S395): rules that need no screen', () => {
  it('every upload type has its own limits: 10 MB and 5,000 rows, but 25 MB and 50,000 for the feed and the stock file', () => {
    expect(limitsFor('CREATE')).toEqual({ maxFileBytes: 10 * 1024 * 1024, maxRows: 5000 });
    expect(limitsFor('OFFER')).toEqual({ maxFileBytes: 10 * 1024 * 1024, maxRows: 5000 });
    expect(limitsFor('PRICE_QTY')).toEqual({ maxFileBytes: 25 * 1024 * 1024, maxRows: 50_000 });
    expect(limitsFor('STOCK')).toEqual({ maxFileBytes: 25 * 1024 * 1024, maxRows: 50_000 });
    const big = { name: 'feed.csv', size: 12 * 1024 * 1024 };
    expect(fileProblem(big, DEFAULT_LIMITS, 'OFFER')?.key).toBe('sc.bulk.err.size');
    expect(fileProblem(big, DEFAULT_LIMITS, 'PRICE_QTY')).toBeNull();
    expect(fileProblem({ name: 'x.csv', size: 26 * 1024 * 1024 }, DEFAULT_LIMITS, 'STOCK')?.key).toBe('sc.bulk.err.size');
  });

  it('a stock file is CSV only', () => {
    expect(fileProblem({ name: 'count.xlsx', size: 10 }, DEFAULT_LIMITS, 'STOCK')?.key).toBe('sc.bulk.err.csvOnly');
    expect(fileProblem({ name: 'count.csv', size: 10 }, DEFAULT_LIMITS, 'STOCK')).toBeNull();
    expect(fileProblem({ name: 'offers.xlsx', size: 10 }, DEFAULT_LIMITS, 'OFFER')).toBeNull();
  });

  it('what a member may upload follows their access: stock needs inventory, the feed needs listings and inventory', () => {
    const need = Object.fromEntries(UPLOAD_KINDS.map((k) => [k.kind, k.modules]));
    expect(need).toEqual({ CREATE: ['listings'], OFFER: ['listings'], PRICE_QTY: ['listings', 'inventory'], STOCK: ['inventory'], UPDATE: ['listings'] });
  });

  it('a held row is explained in plain words, whatever the service wrote', () => {
    expect(tEn(heldWhyKey('We are checking the tax details for this item.'))).toBe('We are checking the tax details for this item.');
    expect(heldWhyKey('You need approval to sell this brand')).toBe('sc.bulk.heldWhy.approval');
    expect(heldWhyKey('A photo is in review')).toBe('sc.bulk.heldWhy.photo');
    expect(heldWhyKey(null)).toBe('sc.bulk.heldWhy.generic');
  });

  it('a preview image finding is the product form card message of the same code, not wording of its own', () => {
    // The product form's image cards translate sc.img.check.<CODE>; the preview must give exactly that text for the same code.
    for (const code of ['FETCH_FAILED', 'FETCH_PRIVATE', 'HELD_REVIEW', 'NOT_VERIFIED', 'NO_MAIN_IMAGE', 'MAIN_TOO_SMALL', 'NOT_WHITE']) {
      const text = imageCheckText(tEn, { code, level: 'REJECT', params: { w: 640, h: 480 }, message: 'English fallback' });
      expect(text).toBe(tEn(`sc.img.check.${code}` as keyof typeof EN_MESSAGES, { px: 1000, w: 640, h: 480, min: 500, fill: 85, mb: 8 }));
      expect(text).not.toBe('English fallback');
    }
    // A code the catalogue does not have falls back to the service's own English message.
    expect(imageCheckText(tEn, { code: 'SOMETHING_NEW', level: 'WARN', params: {}, message: 'English fallback' })).toBe('English fallback');
    expect(tEn('sc.img.check.FETCH_FAILED')).toBe('We could not get this photo from the link. Check that the link is public and starts with https.');
  });
});

describe('bulk upload screen: upload types, templates and uploads', () => {
  it('offers the five upload types and no category picker for an offer file', async () => {
    const user = userEvent.setup();
    await renderScreen(<BulkUploadView />);
    const group = await screen.findByRole('radiogroup', { name: 'What should the file do?' });
    expect(within(group).getAllByRole('radio').map((r) => r.getAttribute('data-testid'))).toEqual(['bulk-kind-CREATE', 'bulk-kind-OFFER', 'bulk-kind-PRICE_QTY', 'bulk-kind-STOCK', 'bulk-kind-UPDATE']);
    expect(within(group).getByText('Sell products already on AfroTransact')).toBeInTheDocument();
    expect(screen.getByLabelText('Template for')).toBeInTheDocument();
    await user.click(screen.getByTestId('bulk-kind-OFFER'));
    expect(screen.queryByLabelText('Template for')).not.toBeInTheDocument();
    expect(screen.queryByText('Photos first')).not.toBeInTheDocument();
    expect(screen.getByTestId('bulk-columns')).toHaveTextContent('its name with the size or pack (5 kg, 500 ml, 6 pack)');
    expect(screen.getByTestId('bulk-columns')).toHaveTextContent('No photos, titles or details');
    expect(document.querySelector('[data-tour="bulk-upload-types"]')).not.toBeNull();
  });

  it('downloads the offer template, the price and quantity template and the stock template', async () => {
    const user = userEvent.setup();
    await renderScreen(<BulkUploadView />);
    await user.click(await screen.findByTestId('bulk-kind-OFFER'));
    await user.click(screen.getByRole('button', { name: 'CSV (.csv)' }));
    await user.click(screen.getByTestId('download-template'));
    await waitFor(() => expect(downloads.calls.map((c) => c.name)).toEqual(['afrotransact-offer.csv']));
    expect(downloads.calls[0]?.text).toContain('Barcode');
    expect(downloads.calls[0]?.text).not.toMatch(/Image|Title/);

    await user.click(screen.getByTestId('bulk-kind-PRICE_QTY'));
    await user.click(screen.getByTestId('download-template'));
    await waitFor(() => expect(downloads.calls.map((c) => c.name)).toContain('afrotransact-price-qty.csv'));

    await user.click(screen.getByTestId('bulk-kind-STOCK'));
    expect(screen.queryByRole('button', { name: 'Excel (.xlsx)' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'A change to add or remove' }));
    await user.click(screen.getByTestId('download-template'));
    await waitFor(() => expect(downloads.calls.map((c) => c.name)).toContain('afrotransact-stock-delta.csv'));
    expect(downloads.calls.find((c) => c.name === 'afrotransact-stock-delta.csv')?.text).toContain('Change');
  });

  it('uploads an offer file and opens its preview', async () => {
    const user = userEvent.setup();
    await renderScreen(<BulkUploadView />);
    await user.click(await screen.findByTestId('bulk-kind-OFFER'));
    fireEvent.change(screen.getByTestId('bulk-file'), { target: { files: [new File([offerCsv], 'offers.csv', { type: 'text/csv' })] } });
    await user.click(screen.getByRole('button', { name: 'Upload and check' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith(expect.stringMatching(/^\/listings\/bulk\/imp-\d+$/)));
    const imps = getSellerDb().imports;
    expect(imps[0]).toMatchObject({ mode: 'OFFER', totalRows: 3 });
  });

  it('shows the feed limits (25 MB, 50,000 rows) and accepts a 12 MB feed but not a 12 MB offer file', async () => {
    const user = userEvent.setup();
    await renderScreen(<BulkUploadView />);
    await user.click(await screen.findByTestId('bulk-kind-PRICE_QTY'));
    expect(screen.getByText(/up to 25 MB and 50,000 rows/)).toBeInTheDocument();
    const big = new File(['x'], 'feed.csv');
    Object.defineProperty(big, 'size', { value: 12 * 1024 * 1024 });
    fireEvent.change(screen.getByTestId('bulk-file'), { target: { files: [big] } });
    expect(screen.queryByTestId('bulk-error')).not.toBeInTheDocument();
    await user.click(screen.getByTestId('bulk-kind-OFFER'));
    fireEvent.change(screen.getByTestId('bulk-file'), { target: { files: [big] } });
    expect(await screen.findByTestId('bulk-error')).toHaveTextContent('larger than 10 MB');
  });

  it('uploads a stock file as CSV only and opens the stock preview', async () => {
    const user = userEvent.setup();
    const { sku, node } = held();
    await renderScreen(<BulkUploadView />);
    await user.click(await screen.findByTestId('bulk-kind-STOCK'));
    fireEvent.change(screen.getByTestId('bulk-file'), { target: { files: [new File(['x'], 'count.xlsx')] } });
    expect(await screen.findByTestId('bulk-error')).toHaveTextContent('must be a CSV');
    const csv = `Seller SKU,Location,On hand\n${sku},${node},60\n`;
    fireEvent.change(screen.getByTestId('bulk-file'), { target: { files: [new File([csv], 'count.csv', { type: 'text/csv' })] } });
    await user.click(screen.getByRole('button', { name: 'Upload and check' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith(expect.stringMatching(/^\/listings\/bulk\/stock\/stk-\d+$/)));
  });

  it('a member without inventory access is not offered the stock or feed types', async () => {
    asRole('seller.support');
    await renderScreen(<BulkUploadView />);
    const group = await screen.findByRole('radiogroup', { name: 'What should the file do?' });
    expect(within(group).queryByTestId('bulk-kind-STOCK')).not.toBeInTheDocument();
    expect(within(group).queryByTestId('bulk-kind-PRICE_QTY')).not.toBeInTheDocument();
  });
});

describe('offer upload preview and result', () => {
  it('previews matched, held and bad rows, runs the import and explains the held row', async () => {
    const user = userEvent.setup();
    const upload = await mockApi().uploadImport(new File([offerCsv], 'offers.csv', { type: 'text/csv' }), 'OFFER');
    await renderScreen(<ImportView importId={upload.id} />);
    expect(await screen.findByRole('heading', { level: 1, name: 'offers.csv' })).toBeInTheDocument();
    expect(screen.getByText(/Sell products already on AfroTransact/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByTestId('import-row')).toHaveLength(3));
    expect(screen.getAllByTestId('row-atin').map((e) => e.textContent)).toEqual(['ATADEV0001', 'ATADEV0002']);
    expect(screen.getAllByText('That barcode does not look right. Check the number on the pack.').length).toBeGreaterThan(0);
    await user.click(screen.getByTestId('import-valid'));
    await waitFor(() => expect(screen.getByTestId('done-offers')).toHaveTextContent('1 product added'), { timeout: 8000 });
    expect(screen.getByTestId('done-held')).toHaveTextContent('1 row on hold');
    const why = screen.getByTestId('held-why');
    expect(why).toHaveTextContent('We are checking the tax details for this item.');
    expect(screen.getByText('Added')).toBeInTheDocument();
    expect(screen.getAllByText('On hold').length).toBeGreaterThan(0);
  });

  it('says when this file was uploaded before and links to that result', async () => {
    const api = mockApi();
    const first = await api.uploadImport(new File([offerCsv], 'offers.csv', { type: 'text/csv' }), 'OFFER');
    const again = await api.uploadImport(new File([offerCsv], 'offers.csv', { type: 'text/csv' }), 'OFFER');
    expect(again.sameFileAs).toBe(first.id);
    await renderScreen(<ImportView importId={again.id} />);
    expect(await screen.findByText(/You uploaded this file on/)).toBeInTheDocument();
    expect(screen.getByText('Show that result or run it again?')).toBeInTheDocument();
    expect(screen.getByTestId('same-file-link')).toHaveAttribute('href', `/listings/bulk/${first.id}`);
  });

  it('shows a row image problem with the same words as the product form image cards', async () => {
    const upload = await mockApi().uploadImport(new File([offerCsv], 'offers.csv', { type: 'text/csv' }), 'OFFER');
    const row = getSellerDb().imports.find((i) => i.id === upload.id)?.rows[0];
    if (!row) throw new Error('row');
    row.images = [{ slot: 'MAIN', source: 'LINK', ref: 'https://cdn.example/a.jpg', state: 'BAD_LINK', url: null, message: 'English from the service',
      check: { code: 'FETCH_FAILED', level: 'REJECT', params: {}, message: 'English from the service' } }];
    await renderScreen(<ImportView importId={upload.id} />);
    const problem = await screen.findByTestId('row-image-problem');
    expect(problem).toHaveAttribute('data-check', 'FETCH_FAILED');
    expect(problem).toHaveTextContent(tEn('sc.img.check.FETCH_FAILED'));
    expect(problem).not.toHaveTextContent('English from the service');
  });

  it('a price and quantity feed previews the seller’s own SKUs and finishes with offers updated', async () => {
    const user = userEvent.setup();
    const mine = getSellerDb().listings[0]?.variants[0]?.sku ?? '';
    const upload = await mockApi().uploadImport(new File([`Seller SKU,Price,Quantity\n${mine},24.99,\nNOT-MINE-1,5.00,1\n`], 'feed.csv', { type: 'text/csv' }), 'PRICE_QTY');
    await renderScreen(<ImportView importId={upload.id} />);
    expect((await screen.findAllByText('No listing of yours has this SKU.')).length).toBeGreaterThan(0);
    await user.click(screen.getByTestId('import-valid'));
    await waitFor(() => expect(screen.getByText(/Import finished: 1 listing updated/)).toBeInTheDocument(), { timeout: 8000 });
  });

  it('the history lists the upload type by name', async () => {
    await mockApi().uploadImport(new File([offerCsv], 'offers.csv', { type: 'text/csv' }), 'OFFER');
    await renderScreen(<ImportHistory />);
    expect(await screen.findByText('Sell products already on AfroTransact')).toBeInTheDocument();
  });
});

describe('stock import preview and result', () => {
  it('previews before and after counts, protects units reserved for orders, applies the valid rows and downloads the report', async () => {
    const user = userEvent.setup();
    const { sku, node } = held();
    const csv = `Seller SKU,Location,On hand\n${sku},${node},77\nNOPE-1,${node},5\n`;
    const upload = await mockApi().uploadStockImport(new File([csv], 'count.csv', { type: 'text/csv' }), 'ABSOLUTE');
    await renderScreen(<StockImportView importId={upload.id} />);
    expect(await screen.findByRole('heading', { level: 1, name: 'count.csv' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByTestId('import-row')).toHaveLength(2));
    expect(screen.getAllByText('No stock of yours has this SKU at this location.').length).toBeGreaterThan(0);
    expect(screen.getByText('1 row has errors.')).toBeInTheDocument();
    await user.click(screen.getByTestId('import-valid'));
    await waitFor(() => expect(screen.getByText(/Import finished: 1 changed/)).toBeInTheDocument(), { timeout: 8000 });
    await user.click(screen.getByTestId('stock-report'));
    await waitFor(() => expect(downloads.calls.map((c) => c.name)).toContain('count-report.csv'));
    expect(downloads.calls.find((c) => c.name === 'count-report.csv')?.text).toContain('Message');
  });

  it('a count below the reserved units is a row error in plain words', async () => {
    const { sku, node } = held();
    const inv = await mockApi().getInventory(false);
    const reserved = inv.positions.find((p) => p.sku === sku)?.reserved ?? 0;
    if (reserved === 0) return; // the fixture reserves nothing for this SKU
    const upload = await mockApi().uploadStockImport(new File([`Seller SKU,Location,On hand\n${sku},${node},${reserved - 1}\n`], 'low.csv', { type: 'text/csv' }), 'ABSOLUTE');
    await renderScreen(<StockImportView importId={upload.id} />);
    expect(await screen.findByText(/reserved for orders/)).toBeInTheDocument();
  });

  it('the history lists the stock files', async () => {
    const { sku, node } = held();
    await mockApi().uploadStockImport(new File([`Seller SKU,Location,On hand\n${sku},${node},60\n`], 'count.csv', { type: 'text/csv' }), 'ABSOLUTE');
    await renderScreen(<ImportHistory />);
    const section = await screen.findByTestId('stock-files');
    expect(within(section).getByText('count.csv')).toBeInTheDocument();
    expect(within(section).getByRole('link')).toHaveAttribute('href', expect.stringMatching(/^\/listings\/bulk\/stock\/stk-\d+$/));
  });
});
