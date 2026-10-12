import { File as NodeFile } from 'node:buffer';

import { getSellerDb, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { BulkUploadView } from '@/components/listings/bulk/BulkUploadView';
import { ImportView } from '@/components/listings/bulk/ImportView';
import { ListingsView } from '@/components/listings/ListingsView';
import { ProductForm } from '@/components/listings/ProductForm';

import { mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/* Uploads go through the Fetch API's Request, which is Node's (jsdom has none): use Node's FormData and File so the
   multipart body reaches the mock BFF, as it does in a browser. */
const NodeFormData = (await new Response('a=1', { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }).formData()).constructor as typeof FormData;
globalThis.FormData = NodeFormData;
globalThis.File = NodeFile as unknown as typeof File;

beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  nav.push.mockReset();
  nav.replace.mockReset();
});

async function chooseCategory(query: string, name: RegExp) {
  fireEvent.change(await screen.findByLabelText('Search categories'), { target: { value: query } });
  fireEvent.click(await screen.findByRole('button', { name }));
}

describe('one-step product form (S307)', () => {
  it('starts with the category, then shows the sections its product type needs', async () => {
    await renderScreen(<ProductForm />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Add a product' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Food information' })).not.toBeInTheDocument();

    await chooseCategory('spices', /Spices & seasonings/);
    expect(await screen.findByRole('heading', { name: 'Food information' })).toBeInTheDocument();
    expect(screen.getByLabelText('Shelf life')).toBeInTheDocument();
    expect(screen.getByLabelText('Unit of Shelf life')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Permits and licences' })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Pre-order' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Shipping' })).toBeInTheDocument();

    // AfroTransact fulfilment: package dimensions become required and the shipping section goes away.
    fireEvent.click(screen.getByRole('radio', { name: /Fulfilled by AfroTransact/ }));
    expect(screen.queryByRole('heading', { name: 'Shipping' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Change' }));
    await chooseCategory('frozen', /Frozen vegetables/);
    expect(await screen.findByRole('heading', { name: 'Permits and licences' })).toBeInTheDocument();
    expect(screen.getByLabelText('Food facility / permit no.')).toBeInTheDocument();
  });

  it('turns variations on into a generated matrix with SKU, barcode, price, compare-at, stock and image per row', async () => {
    await renderScreen(<ProductForm />);
    await chooseCategory('dresses', /Dresses/);
    fireEvent.change(screen.getByLabelText('Product title'), { target: { value: 'Ankara Wrap Dress' } });
    fireEvent.click(await screen.findByLabelText(/several sizes, colours or weights/));
    fireEvent.click(screen.getByRole('button', { name: 'Size × Color' }));
    fireEvent.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('button', { name: 'M' }));
    fireEvent.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('button', { name: 'L' }));
    fireEvent.click(within(screen.getByRole('group', { name: 'Color' })).getByRole('button', { name: 'Gold' }));
    const table = screen.getByRole('table', { name: 'Variations' });
    expect(within(table).getAllByTestId('variant-row')).toHaveLength(2);
    expect(within(table).getByLabelText('Was (USD) · M / Gold')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Generate missing SKUs' }));
    expect(within(table).getByLabelText('SKU · L / Gold')).toHaveValue('ANKARA-WRAP-DRESS-L-GOLD');
  });

  it('keeps a readiness panel of what is missing and links to each field', async () => {
    await renderScreen(<ProductForm />);
    const panel = await screen.findByRole('complementary', { name: 'Listing readiness' });
    expect(within(panel).getByTestId('readiness')).toHaveTextContent('3 things missing');
    await chooseCategory('spices', /Spices & seasonings/);
    await waitFor(() => expect(within(panel).getByTestId('readiness')).toHaveTextContent(/1\d things missing/));
    const link = within(panel).getByRole('link', { name: /Product title/ });
    fireEvent.click(link);
    expect(screen.getByLabelText('Product title')).toHaveFocus();
    // Submitting with problems moves to the first one and calls nothing.
    fireEvent.change(screen.getByLabelText('Product title'), { target: { value: 'Suya' } });
    fireEvent.click(within(panel).getByTestId('pf-primary'));
    expect(await screen.findAllByText('At least 10 characters')).not.toHaveLength(0);
    expect(nav.push).not.toHaveBeenCalled();
  });

  it('edits an existing listing on the same form and saves the changes', async () => {
    const listing = getSellerDb().listings.find((l) => l.status === 'IN_REVIEW');
    if (!listing) throw new Error('fixture has a listing in review');
    const title = listing.title;
    await renderScreen(<ProductForm productId={listing.productId} />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Edit product' })).toBeInTheDocument();
    expect(screen.getByLabelText('Product title')).toHaveValue(title);
    fireEvent.change(screen.getByLabelText('Product title'), { target: { value: `${title}, Family Size` } });
    fireEvent.click(within(screen.getByRole('complementary', { name: 'Listing readiness' })).getByTestId('pf-primary'));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith('/listings'));
    expect(getSellerDb().listings.find((l) => l.productId === listing.productId)?.title).toBe(`${title}, Family Size`);
  });

  it('autosaves a draft that the listings page offers to continue', async () => {
    await renderScreen(<ProductForm />);
    fireEvent.change(await screen.findByLabelText('Product title'), { target: { value: 'Zobo leaves, dried' } });
    await waitFor(() => expect(getSellerDb().drafts).toHaveLength(1), { timeout: 4000 });
    expect(nav.replace).toHaveBeenCalledWith(expect.stringMatching(/^\/listings\/new\?draft=draft-\d+$/), { scroll: false });
    await renderScreen(<ListingsView />);
    expect(await screen.findByRole('link', { name: 'Zobo leaves, dried' })).toHaveAttribute('href', expect.stringContaining('/listings/new?draft='));
  });
});

describe('bulk upload (S308)', () => {
  it('rejects old .xls files before uploading and uploads a CSV into a preview', async () => {
    await renderScreen(<BulkUploadView />);
    const input = await screen.findByTestId('bulk-file');
    fireEvent.change(input, { target: { files: [new File(['x'], 'products.xls')] } });
    expect(await screen.findByTestId('bulk-error')).toHaveTextContent('save the file as .xlsx');
    const csv = 'SKU *,Category *,Title *,Image URLs *,Price (USD) *,Quantity *,No barcode\nZOBO-8,Groceries › Pantry › Spices & seasonings,Zobo Leaves Dried 8 oz,https://example.com/z.jpg,7.50,30,Yes\n';
    fireEvent.change(input, { target: { files: [new File([csv], 'products.csv', { type: 'text/csv' })] } });
    fireEvent.click(screen.getByRole('button', { name: 'Upload and check' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith(expect.stringMatching(/^\/listings\/bulk\/imp-\d+$/)));
  });

  it('previews rows with per-cell errors, filters to errors and imports the valid rows', async () => {
    const csv = [
      'SKU *,Category,Title *,Description,Image URLs *,Price (USD) *,Quantity *,Barcode (GTIN/UPC/EAN),No barcode',
      'JOLLOF-4,Groceries › Pantry › Spices & seasonings,Jollof Seasoning Smoky 4 oz,Blend,https://example.com/j.jpg,6.99,40,,Yes',
      'EGUSI-1,Groceries › Pantry › Spices & seasonings,Egusi Ground 1 lb,Melon seed,https://example.com/e.jpg,12.5O,25,,Yes',
      'ZOBO-8,Groceries › Pantry › Spices & seasonings,Zobo Leaves Dried 8 oz,Hibiscus,https://example.com/z.jpg,7.50,30,,Yes'
    ].join('\n');
    const upload = await mockApi().uploadImport(new File([csv], 'pantry.csv', { type: 'text/csv' }), 'CREATE');
    await renderScreen(<ImportView importId={upload.id} />);
    expect(await screen.findByRole('heading', { level: 1, name: 'pantry.csv' })).toBeInTheDocument();
    expect(screen.getByTestId('import-stats')).toHaveTextContent(/Rows\s*3/);
    expect(screen.getByText('1 row has errors.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByTestId('import-row').length).toBeGreaterThan(0));
    const bad = document.querySelector('[data-cell-error="true"]');
    expect(bad).toHaveTextContent('12.5O');
    expect(bad).toHaveTextContent('not a price');

    fireEvent.click(screen.getByLabelText('Only rows with errors'));
    await waitFor(() => expect(screen.getAllByTestId('import-row')).toHaveLength(1));

    fireEvent.click(screen.getByTestId('import-valid'));
    await waitFor(() => expect(screen.getByText(/Import finished/)).toBeInTheDocument(), { timeout: 8000 });
    expect(getSellerDb().listings.slice(0, 2).map((l) => l.title).sort()).toEqual(['Jollof Seasoning Smoky 4 oz', 'Zobo Leaves Dried 8 oz']);
  });
});
