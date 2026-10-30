import { sellerFormPreview, type TaxonomyAttribute } from '@afrotransact/api-client';
import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { BrandsView } from '@/components/catalog/BrandsView';
import { brandActions, previewBrandList } from '@/components/catalog/brandModel';
import { CategoriesView } from '@/components/catalog/CategoriesView';
import { CatalogView } from '@/components/catalog/CatalogView';
import { SellerFormPreview } from '@/components/catalog/SellerFormPreview';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const api = () => mockApi().taxonomy;
const brandNamed = async (name: string) => {
  const found = (await api().brands({ limit: 200 })).brands.find((b) => b.name === name);
  if (!found) throw new Error(`no brand ${name}`);
  return found;
};
const sheet = () => screen.findByTestId('brand-sheet');

describe('brand model', () => {
  it('offers only the decisions the status allows', () => {
    expect(brandActions('PENDING')).toEqual(['approve', 'reject', 'merge', 'edit']);
    expect(brandActions('APPROVED')).toEqual(['merge', 'edit', 'retire']);
    expect(brandActions('REJECTED')).toEqual(['approve', 'edit']);
    expect(brandActions('MERGED_INTO')).toEqual([]);
  });

  it('previews a pasted list: new, already listed (case and accents ignored), too short, and repeats left out', () => {
    const p = previewBrandList('Nike\nlevis\nZara\nzara\nX\nHauté Couture, Maggi', [
      { name: 'Nike', status: 'APPROVED' },
      { name: 'Levi’s', status: 'APPROVED' },
      { name: 'Maggi', status: 'REJECTED' }
    ]);
    expect(p.rows.map((r) => [r.name, r.outcome, r.match])).toEqual([
      ['Nike', 'EXISTS', 'Nike'],
      ['levis', 'EXISTS', 'Levi’s'],
      ['Zara', 'NEW', null],
      ['X', 'INVALID', null],
      ['Hauté Couture', 'NEW', null],
      ['Maggi', 'NEW', null]
    ]);
    expect(p).toMatchObject({ fresh: 3, existing: 2, invalid: 1, repeated: 1 });
  });
});

describe('the brand queue', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-24T12:00:00Z'));
    asRoles(null);
    nav.set('/moderation?tab=brands');
  });

  it('opens on the brands waiting for review, naming the seller and showing products and where it applies', async () => {
    await renderScreen(<BrandsView />);
    const row = (await screen.findByText('Cotedivoire')).closest('tr')!;
    expect(within(row).getByText('Lagos Threads')).toBeInTheDocument();
    expect(within(row).getByText('2 products')).toBeInTheDocument();
    expect(within(row).getByText('Fashion')).toBeInTheDocument();
    expect(screen.queryByText('Nike')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Waiting for review (4)' })).toHaveAttribute('aria-pressed', 'true');
    expect(document.body.textContent).not.toMatch(/brand-cotedivoire/);
  });

  it('shows the other statuses on request and searches by name', async () => {
    const user = userEvent.setup();
    await renderScreen(<BrandsView />);
    await screen.findByText('Cotedivoire');
    await user.click(screen.getByRole('button', { name: 'Approved (4)' }));
    expect(await screen.findByText('Nike')).toBeInTheDocument();
    await user.type(screen.getByRole('searchbox'), 'maggi');
    await waitFor(() => expect(screen.queryByText('Nike')).not.toBeInTheDocument());
    expect(screen.getByText('Maggi')).toBeInTheDocument();
  });

  it('approves a brand: a reason is required, and the history names who decided', async () => {
    const user = userEvent.setup();
    await renderScreen(<BrandsView />);
    await user.click(await screen.findByRole('button', { name: 'Approve Nikee' }));
    const s = await sheet();
    const submit = within(s).getByRole('button', { name: 'Approve' });
    expect(submit).toBeDisabled();
    await user.type(within(s).getByLabelText(/Reason/), 'Real brand, spelled wrong');
    await user.click(submit);
    await waitFor(async () => expect((await brandNamed('Nikee')).status).toBe('APPROVED'));
    expect(await within(await sheet()).findByText(/Approved Nikee/)).toBeInTheDocument();
    expect(within(await sheet()).getAllByText(/Ifeoma Okafor/).length).toBeGreaterThan(0);
  });

  it('rejects a brand only with a reason', async () => {
    const user = userEvent.setup();
    await renderScreen(<BrandsView />);
    await user.click(await screen.findByText('Shea Gold'));
    let s = await sheet();
    await user.click(within(s).getByRole('button', { name: 'Reject' }));
    s = await sheet();
    const submit = within(s).getByRole('button', { name: 'Reject brand' });
    expect(submit).toBeDisabled();
    await user.type(within(s).getByLabelText(/Reason/), 'ab');
    expect(submit).toBeDisabled();
    await user.type(within(s).getByLabelText(/Reason/), 'c');
    await user.click(submit);
    await waitFor(async () => expect(await brandNamed('Shea Gold')).toMatchObject({ status: 'REJECTED', decisionReason: 'abc' }));
  });

  it('merges a brand into an approved one, saying how many products move', async () => {
    const user = userEvent.setup();
    await renderScreen(<BrandsView />);
    await user.click(await screen.findByText('Nikee'));
    await user.click(within(await sheet()).getByRole('button', { name: 'Merge into another brand' }));
    const s = await sheet();
    const submit = within(s).getByRole('button', { name: 'Merge brands' });
    expect(submit).toBeDisabled();
    await user.click(await within(s).findByRole('combobox', { name: /Merge into/ }));
    await user.keyboard('Nik');
    await user.click(await screen.findByRole('option', { name: /Nike/ }));
    expect(within(s).getByText('1 products move to Nike.')).toBeInTheDocument();
    await user.type(within(s).getByLabelText(/Reason/), 'Same brand');
    await user.click(submit);
    await waitFor(async () => expect(await brandNamed('Nikee')).toMatchObject({ status: 'MERGED_INTO', mergedIntoName: 'Nike' }));
    expect((await brandNamed('Nike')).products).toBe(121);
  });

  it('cannot save an edit with no change; a new name or place enables it', async () => {
    const user = userEvent.setup();
    await renderScreen(<BrandsView />);
    await user.click(await screen.findByText('Adire Lagos'));
    await user.click(within(await sheet()).getByRole('button', { name: 'Edit name and scope' }));
    const s = await sheet();
    await user.type(within(s).getByLabelText(/Reason/), 'Checking');
    const save = within(s).getByRole('button', { name: 'Save changes' });
    expect(save).toBeDisabled();
    expect(within(s).getByText('Change the name or where it applies to save.')).toBeInTheDocument();
    const name = within(s).getByLabelText('Brand name');
    await user.type(name, 'x');
    expect(save).toBeEnabled();
    await user.type(name, '{Backspace}');
    expect(save).toBeDisabled();
    await user.click(within(s).getByRole('checkbox', { name: 'Beauty' }));
    expect(save).toBeEnabled();
    await user.click(save);
    await waitFor(async () => expect((await brandNamed('Adire Lagos')).scope.map((x) => x.path).sort()).toEqual(['Beauty', 'Fashion']));
  });

  it('adds many brands from a pasted list after a preview of what matches', async () => {
    const user = userEvent.setup();
    await renderScreen(<BrandsView />);
    await user.click(await screen.findByRole('button', { name: 'Add many' }));
    const s = await sheet();
    await user.click(await within(s).findByRole('checkbox', { name: 'Fashion' }));
    await user.type(within(s).getByLabelText(/Brands, one per line/), 'Nike{Enter}Zara{Enter}Zara{Enter}Ankara Co{Enter}Q');
    const preview = await within(s).findByTestId('bulk-preview');
    expect(within(preview).getByText('2 new, 1 already listed, 1 not valid')).toBeInTheDocument();
    expect(within(preview).getByText('Already listed as Nike')).toBeInTheDocument();
    expect(within(preview).getByText('1 repeated lines were left out.')).toBeInTheDocument();
    const submit = within(s).getByRole('button', { name: 'Add 2 brands' });
    expect(submit).toBeDisabled();
    await user.type(within(s).getByLabelText(/Reason/), 'Launch list');
    await user.click(submit);
    expect(await within(s).findByTestId('bulk-result')).toHaveTextContent('2 added, 0 already listed, 0 not valid');
    expect((await api().brands({ status: ['APPROVED'], limit: 200 })).brands.map((b) => b.name)).toEqual(expect.arrayContaining(['Zara', 'Ankara Co']));
  });

  it('adds one brand with a name, a place and a reason', async () => {
    const user = userEvent.setup();
    await renderScreen(<BrandsView />);
    await user.click(await screen.findByRole('button', { name: 'Add a brand' }));
    const s = await sheet();
    const add = within(s).getByRole('button', { name: 'Add brand' });
    await user.type(within(s).getByLabelText('Brand name'), 'Ankara Co');
    await user.type(within(s).getByLabelText(/Reason/), 'Listed by staff');
    expect(add).toBeDisabled();
    await user.click(await within(s).findByRole('checkbox', { name: 'Fashion' }));
    await user.click(add);
    await waitFor(async () => expect(await brandNamed('Ankara Co')).toMatchObject({ status: 'APPROVED' }));
  });

  it('adds a Brands tab to Catalog with the number waiting, for staff who hold catalog:taxonomy', async () => {
    nav.set('/moderation');
    await renderScreen(<CatalogView />);
    expect(await screen.findByRole('tab', { name: 'Brands (4 to review)' })).toBeInTheDocument();
  });
});

describe('the BRAND attribute in the seller form preview', () => {
  it('shows the brand field as the seller sees it, with the no-brand choices', () => {
    const brand: TaxonomyAttribute = { key: 'brand', label: 'Brand', kind: 'BRAND', options: ['UNBRANDED', 'HANDMADE'], units: [], required: true, section: 'DETAILS', help: 'The brand printed on it.', applies: true };
    const traits = { food: false, perishable: false, regulated: false, preorder: false };
    expect(sellerFormPreview({ identifierRule: 'GTIN', traits, attributes: [brand] })[1]?.fields[0]).toMatchObject({ control: 'brand', options: ['UNBRANDED', 'HANDMADE'] });
    render(<SellerFormPreview type={{ identifierRule: 'GTIN', traits, attributes: [brand] }} />);
    const field = screen.getByTestId('preview-brand');
    expect(within(field).getByText('Search brands')).toBeInTheDocument();
    expect(within(field).getByText(/No brand · Handmade/)).toBeInTheDocument();
    expect(within(field).getByText('The brand printed on it.')).toBeInTheDocument();
  });

  it('is a kind in the product type editor: the key is fixed, the no-brand choices are ticked, and the preview follows', async () => {
    const user = userEvent.setup();
    resetStaffDb(new Date('2026-10-24T12:00:00Z'));
    asRoles(null);
    nav.set('/moderation?tab=categories');
    await renderScreen(<CategoriesView />);
    await screen.findByRole('tree');
    const books = screen.getAllByRole('button', { name: /^Open Books to see and change it/ });
    await user.click(books[books.length - 1]!);
    await user.click(within(await screen.findByTestId('node-sheet')).getByRole('button', { name: 'Edit its details' }));
    const editor = await screen.findByTestId('type-editor');
    await user.click(within(editor).getByRole('button', { name: 'Add a detail' }));
    await user.type(within(editor).getByLabelText('What sellers see'), 'Publisher brand');
    await user.selectOptions(within(editor).getByLabelText('Kind of answer'), 'Brand');
    expect(within(editor).getByLabelText('Key')).toHaveValue('brand');
    expect(within(editor).getByLabelText('Key')).toBeDisabled();
    await user.click(within(editor).getByLabelText('Handmade'));
    const preview = within(editor).getByTestId('seller-form-preview');
    expect(within(preview).getByTestId('preview-brand')).toHaveTextContent('Publisher brand');
    expect(within(preview).getByText(/Handmade/)).toBeInTheDocument();
    expect(within(preview).getByText('Search brands')).toBeInTheDocument();
  });
});
