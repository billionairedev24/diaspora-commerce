import { File as NodeFile } from 'node:buffer';

import { type AttributeDef } from '@afrotransact/api-client';
import { getSellerDb, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { BulkUploadView } from '@/components/listings/bulk/BulkUploadView';
import { ImportView } from '@/components/listings/bulk/ImportView';
import { AttributeField } from '@/components/listings/form/AttributeField';
import { ListingsView } from '@/components/listings/ListingsView';
import { ProductForm } from '@/components/listings/ProductForm';

import { mockApi } from './mock-api';
import { renderScreen, renderWithProviders } from './render';
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
  fireEvent.change(await screen.findByRole('combobox', { name: 'Category' }), { target: { value: query } });
  fireEvent.click(await screen.findByRole('option', { name }));
}

const sectionTitles = () => within(screen.getByRole('form', { name: 'Product' })).getAllByRole('heading', { level: 2 }).map((h) => h.textContent);

describe('product form v2 (S309): wide cards of sections with proper dropdowns', () => {
  it('shows the sections in order, only the ones the category needs, with compliance last', async () => {
    await renderScreen(<ProductForm />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Add a product' })).toBeInTheDocument();
    expect(sectionTitles()).toEqual(['Basics', 'Photos']);

    await chooseCategory('spices', /Spices & seasonings/);
    await screen.findByRole('heading', { level: 3, name: 'Food information' });
    expect(sectionTitles()).toEqual(['Basics', 'Photos', 'Price & stock', 'Details', 'Variants', 'Shipping & fulfilment', 'Compliance']);
    expect(screen.queryByRole('heading', { name: 'Permits and licences' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Shelf life')).toBeInTheDocument();

    await chooseCategory('frozen', /Frozen vegetables/);
    expect(await screen.findByRole('heading', { level: 3, name: 'Permits and licences' })).toBeInTheDocument();
    expect(screen.getByLabelText('Food facility / permit no.')).toBeInTheDocument();
  });

  it('chooses a category by typing over its full path and the keyboard alone, then offers it as a recent one', async () => {
    const user = userEvent.setup();
    window.localStorage.removeItem('sc.pf.categoryUse');
    await renderScreen(<ProductForm />);
    const category = await screen.findByRole('combobox', { name: 'Category' });
    expect(category).toHaveAttribute('aria-autocomplete', 'list');
    await user.click(category);
    expect(screen.getAllByRole('option').length).toBeGreaterThan(3);
    // "fashion women" only exists in the path of Dresses, not in its name.
    await user.type(category, 'fashion women');
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(expect.arrayContaining([expect.stringContaining('Fashion › Women › Dresses')]));
    await user.keyboard('{Enter}');
    expect(category).toHaveValue('Fashion › Women › Dresses');
    expect(await screen.findByRole('heading', { level: 2, name: 'Variants' })).toBeInTheDocument();

    await user.click(category);
    const list = screen.getByRole('listbox');
    expect(within(list).getByText('Recent')).toBeInTheDocument();
    expect(within(list).getAllByRole('option')[0]).toHaveTextContent('Dresses');
    await user.keyboard('{Escape}');
    expect(category).toHaveValue('Fashion › Women › Dresses');
  });

  it('requests a category that is not in the list from the same field', async () => {
    const user = userEvent.setup();
    await renderScreen(<ProductForm />);
    await user.type(await screen.findByRole('combobox', { name: 'Category' }), 'Hair pomades');
    await user.click(screen.getByRole('option', { name: /Request “Hair pomades” as a new category/ }));
    expect(screen.getByLabelText('New category name')).toHaveValue('Hair pomades');
  });

  it('picks or adds a brand in a combobox', async () => {
    const user = userEvent.setup();
    await renderScreen(<ProductForm />);
    await chooseCategory('dresses', /Dresses/);
    const brand = await screen.findByRole('combobox', { name: 'Brand' });
    await user.click(brand);
    expect(await screen.findByRole('option', { name: 'Mama Africa' })).toBeInTheDocument();
    await user.type(brand, 'Zuri Naturals');
    await user.click(screen.getByRole('option', { name: 'Add “Zuri Naturals” as a new brand' }));
    expect(brand).toHaveValue('Zuri Naturals');
    await user.click(brand);
    await user.click(await screen.findByRole('option', { name: 'Shea Gold' }));
    expect(brand).toHaveValue('Shea Gold');
  });

  it('uses a dropdown or a segmented control for every choice', async () => {
    await renderScreen(<ProductForm />);
    await chooseCategory('spices', /Spices & seasonings/);
    await screen.findByLabelText('Shelf life');
    // Dropdowns: condition, the enum attributes, the unit next to the number, ship-from and handling time.
    for (const name of ['Condition', 'Allergens (FALCPA)', 'Storage', 'Unit of Shelf life', 'Ships from', 'Handling time (business days)']) {
      expect(screen.getByRole('combobox', { name }).tagName).toBe('SELECT');
    }
    expect(within(screen.getByLabelText('Handling time (business days)')).getAllByRole('option').length).toBeGreaterThan(5);
    // Segmented controls: who ships it, and whether it comes in several versions.
    const ship = screen.getByRole('group', { name: 'Who ships it?' });
    expect(within(ship).getByRole('button', { name: 'I ship it' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(within(ship).getByRole('button', { name: 'Fulfilled by AfroTransact' }));
    expect(within(ship).getByRole('button', { name: 'Fulfilled by AfroTransact' })).toHaveAttribute('aria-pressed', 'true');
    // AfroTransact fulfilment: the ship-from choices go away and the package (with its unit selects) opens.
    expect(screen.queryByLabelText('Ships from')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Size unit').tagName).toBe('SELECT');
    expect(screen.getByLabelText('Weight unit').tagName).toBe('SELECT');
    const variants = screen.getByRole('group', { name: 'Does it come in different sizes, colours or weights?' });
    expect(within(variants).getByRole('button', { name: 'No, one version' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('folds optional fields under More details and opens them when something is filled in', async () => {
    await renderScreen(<ProductForm />);
    await chooseCategory('dresses', /Dresses/);
    const care = await screen.findByLabelText(/Care/);
    expect(care.closest('details')).not.toHaveAttribute('open');
    expect(screen.getByLabelText('Material').closest('details')).toBeNull();
    expect(screen.getByLabelText('Search keywords').closest('details')).not.toHaveAttribute('open');
    fireEvent.click(within(care.closest('details') as HTMLElement).getByText(/More details/));
    expect(care.closest('details')).toHaveAttribute('open');
  });

  it('turns variants on into a generated matrix with SKU, barcode, price, compare-at, stock and image per row', async () => {
    await renderScreen(<ProductForm />);
    await chooseCategory('dresses', /Dresses/);
    fireEvent.change(screen.getByLabelText('Product title'), { target: { value: 'Ankara Wrap Dress' } });
    expect(await screen.findByLabelText('SKU')).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: 'Yes, several' }));
    expect(screen.queryByLabelText('SKU')).not.toBeInTheDocument();
    expect(screen.getByText(/Each variant has its own SKU/)).toBeInTheDocument();
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

  it('keeps a compact readiness panel of what is missing and links to each field', async () => {
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
    fireEvent.click(screen.getByTestId('pf-primary'));
    expect(await screen.findAllByText('At least 10 characters')).not.toHaveLength(0);
    expect(nav.push).not.toHaveBeenCalled();
  });

  it('edits an existing listing on the same form, keeps photo order and stores the alt text with the image', async () => {
    const listing = getSellerDb().listings.find((l) => l.status === 'IN_REVIEW');
    if (!listing) throw new Error('fixture has a listing in review');
    const title = listing.title;
    await renderScreen(<ProductForm productId={listing.productId} />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Edit product' })).toBeInTheDocument();
    expect(screen.getByLabelText('Product title')).toHaveValue(title);
    expect(screen.getByLabelText('Description of image 1')).toHaveValue('');
    expect(within(screen.getByRole('list', { name: /Product images/ })).getByText('Main')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Description of image 1'), { target: { value: 'Jar of spice on a white table' } });
    fireEvent.change(screen.getByLabelText('Product title'), { target: { value: `${title}, Family Size` } });
    fireEvent.click(screen.getByTestId('pf-primary'));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith('/listings'));
    expect(getSellerDb().listings.find((l) => l.productId === listing.productId)?.title).toBe(`${title}, Family Size`);
    expect(getSellerDb().productDetails[listing.productId]?.images[0]?.alt).toBe('Jar of spice on a white table');
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

describe('attributes come from the product type (owner feedback)', () => {
  it('shows no Brand field for a book, and its own author, publisher, language and pages instead', async () => {
    await renderScreen(<ProductForm />);
    await chooseCategory('cookbooks', /African & Caribbean cooking/);
    await screen.findByLabelText('Author');
    expect(screen.queryByRole('combobox', { name: /Brand/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/Brand/)).not.toBeInTheDocument();
    for (const label of ['Author', 'Publisher', 'Language', 'Pages']) expect(screen.getByLabelText(label)).toBeInTheDocument();
    // The ISBN is the identifier of a book and cannot be waived.
    expect(screen.getByLabelText('ISBN-13')).toBeInTheDocument();
    expect(screen.getByLabelText(/Books need an ISBN-13/)).toBeDisabled();
    // Optional book fields are offered under More details.
    expect(screen.getByLabelText('Publication year (optional)').closest('details')).not.toHaveAttribute('open');
    expect(screen.getByLabelText('Edition (optional)')).toBeInTheDocument();
  });

  it('shows the Brand field for a type that defines a brand, and drops it when the category changes to a book', async () => {
    await renderScreen(<ProductForm />);
    expect(screen.queryByRole('combobox', { name: /Brand/ })).not.toBeInTheDocument();
    await chooseCategory('dresses', /Dresses/);
    const brand = await screen.findByRole('combobox', { name: 'Brand' });
    fireEvent.change(brand, { target: { value: 'Zuri' } });
    fireEvent.click(await screen.findByRole('option', { name: /Add “Zuri” as a new brand/ }));
    await chooseCategory('cookbooks', /African & Caribbean cooking/);
    await waitFor(() => expect(screen.queryByRole('combobox', { name: /Brand/ })).not.toBeInTheDocument());
    // The stale brand does not leak into the book's preview.
    expect(within(screen.getByRole('complementary', { name: 'Listing readiness' })).queryByText('Zuri')).not.toBeInTheDocument();
  });
});

describe('section navigator, listing quality and the sticky actions', () => {
  const sections = () => screen.getByRole('navigation', { name: 'Sections' });
  const stateOf = (name: RegExp) => within(sections()).getByRole('link', { name }).getAttribute('data-state');

  it('marks each section complete or with what is left to do, and updates as fields are filled in', async () => {
    await renderScreen(<ProductForm />);
    await screen.findByRole('navigation', { name: 'Sections' });
    expect(stateOf(/Basics/)).toBe('incomplete');
    expect(within(sections()).getByRole('link', { name: /Basics/ })).toHaveTextContent('2 to do');
    expect(stateOf(/Photos/)).toBe('incomplete');

    await chooseCategory('cookbooks', /African & Caribbean cooking/);
    await screen.findByLabelText('Author');
    fireEvent.change(screen.getByLabelText('Product title'), { target: { value: 'Jollof Rice and Other Stories' } });
    expect(stateOf(/Basics/)).toBe('complete');
    expect(within(sections()).getByRole('link', { name: /Basics/ })).toHaveTextContent('Complete');
    expect(stateOf(/Details/)).toBe('incomplete');
    expect(within(sections()).getByRole('link', { name: /Details/ })).toHaveTextContent('4 to do');

    for (const [label, value] of [['Author', 'Ade Bello'], ['Publisher', 'Kachifo'], ['Pages', '240']] as const) fireEvent.change(screen.getByLabelText(label), { target: { value } });
    fireEvent.change(screen.getByLabelText('Language'), { target: { value: 'English' } });
    expect(stateOf(/Details/)).toBe('complete');
    // The section card shows the same state.
    const card = document.querySelector('[data-section="details"]') as HTMLElement;
    expect(within(card).getByTestId('section-state')).toHaveTextContent('Complete');
    // Shipping has valid defaults; Price & stock still needs SKU, ISBN, price and quantity.
    expect(stateOf(/Shipping/)).toBe('complete');
    expect(stateOf(/Price & stock/)).toBe('incomplete');
  });

  it('scores the listing, lists what is missing and jumps to the field when an item is clicked', async () => {
    await renderScreen(<ProductForm />);
    const panel = await screen.findByRole('complementary', { name: 'Listing readiness' });
    const before = Number(within(panel).getByRole('progressbar', { name: 'Listing quality' }).getAttribute('aria-valuenow'));
    await chooseCategory('cookbooks', /African & Caribbean cooking/);
    await screen.findByLabelText('Author');
    fireEvent.change(screen.getByLabelText('Product title'), { target: { value: 'Jollof Rice and Other Stories' } });
    await waitFor(() => expect(Number(within(panel).getByRole('progressbar').getAttribute('aria-valuenow'))).toBeGreaterThan(before));
    expect(within(panel).getByTestId('quality-percent')).toHaveTextContent(/\d+% complete/);

    const missing = within(panel).getByTestId('missing-list');
    expect(within(missing).getByRole('link', { name: /Author/ })).toBeInTheDocument();
    fireEvent.click(within(missing).getByRole('link', { name: /Publisher/ }));
    expect(screen.getByLabelText('Publisher')).toHaveFocus();
    // The message appears with the field it belongs to, and is linked to it.
    expect(screen.getByLabelText('Publisher')).toHaveAccessibleDescription('Required');

    // Recommended items are listed apart and also jump to their field.
    const tips = within(panel).getByTestId('tips-list');
    fireEvent.click(within(tips).getByRole('link', { name: /Description/ }));
    expect(screen.getByLabelText('Description')).toHaveFocus();
  });

  it('jumps to a section and moves focus to its heading', async () => {
    await renderScreen(<ProductForm />);
    await chooseCategory('cookbooks', /African & Caribbean cooking/);
    await screen.findByLabelText('Author');
    fireEvent.click(within(await screen.findByRole('navigation', { name: 'Sections' })).getByRole('link', { name: /Details/ }));
    expect(screen.getByRole('heading', { level: 2, name: 'Details' })).toHaveFocus();
  });

  it('previews the listing on the storefront with its title, price and key attributes', async () => {
    await renderScreen(<ProductForm />);
    await chooseCategory('cookbooks', /African & Caribbean cooking/);
    await screen.findByLabelText('Author');
    fireEvent.change(screen.getByLabelText('Product title'), { target: { value: 'Jollof Rice and Other Stories' } });
    fireEvent.change(screen.getByLabelText('Author'), { target: { value: 'Ade Bello' } });
    fireEvent.change(screen.getByLabelText('Price (USD)'), { target: { value: '18.50' } });
    const preview = within(screen.getByRole('complementary', { name: 'Listing readiness' })).getByTestId('listing-preview');
    expect(within(preview).getByTestId('preview-title')).toHaveTextContent('Jollof Rice and Other Stories');
    expect(within(preview).getByTestId('preview-price')).toHaveTextContent('$18.50');
    expect(within(preview).getByTestId('preview-attrs')).toHaveTextContent('AuthorAde Bello');
  });

  it('keeps Save draft and Submit in one bar, Save draft only when there is something new to save', async () => {
    await renderScreen(<ProductForm />);
    const bar = await screen.findByRole('region', { name: 'Listing actions' });
    expect(within(bar).getByRole('button', { name: 'Save draft' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Product title'), { target: { value: 'Zobo leaves, dried' } });
    expect(within(bar).getByRole('button', { name: 'Save draft' })).toBeEnabled();
    fireEvent.click(within(bar).getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(within(bar).getByTestId('save-state')).toHaveTextContent(/Draft saved/));
    expect(within(bar).getByRole('button', { name: 'Save draft' })).toBeDisabled();
    expect(within(bar).getByTestId('pf-primary')).toHaveTextContent('Submit for review');
  });

  it('cannot save an edit nobody changed', async () => {
    const listing = getSellerDb().listings.find((l) => l.status === 'IN_REVIEW');
    if (!listing) throw new Error('fixture has a listing in review');
    await renderScreen(<ProductForm productId={listing.productId} />);
    const primary = await screen.findByTestId('pf-primary');
    await waitFor(() => expect(screen.getByLabelText('Product title')).toHaveValue(listing.title));
    expect(primary).toBeDisabled();
    expect(screen.getByTestId('save-state')).toHaveTextContent('No changes to save.');
    fireEvent.change(screen.getByLabelText('Product title'), { target: { value: `${listing.title}, new pack` } });
    expect(primary).toBeEnabled();
  });
});

describe('long choice lists', () => {
  it('turn into a searchable combobox, short ones stay a dropdown', () => {
    const def = (options: string[]): AttributeDef => ({ key: 'colour', label: 'Colour', options, required: true, multiline: false, kind: 'ENUM', units: [], section: 'DETAILS', help: null });
    const many = Array.from({ length: 12 }, (_, i) => `Shade ${i + 1}`);
    const first = renderWithProviders(<AttributeField def={def(many)} value="" unit="" onChange={() => undefined} onUnit={() => undefined} />);
    expect(screen.getByRole('combobox', { name: 'Colour' }).tagName).toBe('INPUT');
    first.unmount();
    renderWithProviders(<AttributeField def={def(['Red', 'Blue'])} value="" unit="" onChange={() => undefined} onUnit={() => undefined} />);
    expect(screen.getByRole('combobox', { name: 'Colour' }).tagName).toBe('SELECT');
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
