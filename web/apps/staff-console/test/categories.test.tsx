import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { CatalogView } from '@/components/catalog/CatalogView';
import { CategoriesView } from '@/components/catalog/CategoriesView';
import { changedFields, draftOfType, linesFromText, listFromText, moveAmongVisible, moveTargets, productsInUse, withTrait } from '@/components/catalog/model';
import { SellerFormPreview } from '@/components/catalog/SellerFormPreview';

import { asRoles, mockApi } from './mock-api';
import { renderScreen, renderWithProviders } from './render';
import { nav } from './setup';

const api = () => mockApi().taxonomy;
const row = (name: string) => screen.getByRole('button', { name: new RegExp(`^Open ${name} to see and change it`) });
const nodeIn = async (path: string) => {
  const found = (await api().tree()).nodes.find((n) => n.path === path);
  if (!found) throw new Error(`no ${path}`);
  return found;
};

describe('category model', () => {
  it('offers only valid places to move to: other departments for a category, other categories for a product type', async () => {
    const { nodes } = await api().tree();
    const women = nodes.find((n) => n.path === 'Fashion › Women')!;
    const dresses = nodes.find((n) => n.path === 'Fashion › Women › Dresses')!;
    expect(moveTargets(nodes, women).map((n) => n.path)).toEqual(['Beauty', 'Books', 'Groceries', 'Home']);
    expect(moveTargets(nodes, dresses).map((n) => n.path)).toContain('Fashion › Men');
    expect(moveTargets(nodes, dresses).map((n) => n.path)).not.toContain('Fashion › Women');
    expect(moveTargets(nodes, nodes[0]!)).toEqual([]);
    expect(productsInUse(women)).toBe(85);
  });

  it('moves a row among the shown siblings by swapping with its neighbour in the full order', () => {
    const all = ['a', 'b', 'c', 'd'];
    expect(moveAmongVisible(all, ['a', 'c', 'd'], 'c', -1)).toEqual(['c', 'b', 'a', 'd']);
    expect(moveAmongVisible(all, all, 'a', -1)).toBe(all);
    expect(moveAmongVisible(all, all, 'd', 1)).toBe(all);
  });

  it('keeps perishable tied to food and lists only the parts of a product type that changed', async () => {
    expect(withTrait({ food: true, perishable: true, regulated: false, preorder: false }, 'food', false)).toMatchObject({ food: false, perishable: false });
    expect(withTrait({ food: false, perishable: false, regulated: false, preorder: false }, 'perishable', true)).toMatchObject({ food: true, perishable: true });
    const book = await api().productType('book');
    const before = draftOfType(book);
    expect(changedFields(before, { ...before, label: ' Books ' }, false)).toEqual([]);
    expect(changedFields(before, { ...before, label: 'Books & media', identifierRule: 'GTIN' }, true)).toEqual(['label', 'identifierRule', 'attributes']);
  });

  it('reads choices one per line (they may contain commas) and units separated by commas', () => {
    expect(linesFromText('Packaged, shelf-stable\n\n Candy \nCandy')).toEqual(['Packaged, shelf-stable', 'Candy']);
    expect(listFromText('days, months,\nweeks, days')).toEqual(['days', 'months', 'weeks']);
  });
});

describe('the category tree', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-24T12:00:00Z'));
    asRoles(null);
    nav.set('/moderation?tab=categories');
  });

  it('shows departments, categories and product types with the products under each', async () => {
    await renderScreen(<CategoriesView />);
    const tree = await screen.findByRole('tree', { name: 'Departments, categories and product types' });
    const fashion = within(tree).getByRole('button', { name: /^Open Fashion to see/ }).closest('li')!;
    expect(within(fashion).getAllByTestId('node-count')[0]).toHaveTextContent('103 products');
    expect(within(tree).getByRole('button', { name: /^Open Dresses to see/ })).toBeInTheDocument();
    expect(within(tree).getByText(/Women’s dresses/)).toBeInTheDocument();
  });

  it('collapses a department and finds a category by search', async () => {
    const user = userEvent.setup();
    await renderScreen(<CategoriesView />);
    await screen.findByRole('tree');
    await user.click(screen.getByRole('button', { name: 'Collapse all' }));
    expect(screen.queryByRole('button', { name: /^Open Dresses to see/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Open Fashion' }));
    expect(screen.getByRole('button', { name: /^Open Women to see/ })).toBeInTheDocument();
    await user.type(screen.getByLabelText('Find a category'), 'spice');
    expect(await screen.findByText('Groceries › Pantry › Spices & seasonings')).toBeInTheDocument();
    expect(screen.queryByText('Fashion')).not.toBeInTheDocument();
    await user.clear(screen.getByLabelText('Find a category'));
    await user.type(screen.getByLabelText('Find a category'), 'zzz');
    expect(await screen.findByText('No category matches')).toBeInTheDocument();
  });

  it('renames a category: Save is disabled until the name differs, a reason is required, and the paths follow', async () => {
    const user = userEvent.setup();
    await renderScreen(<CategoriesView />);
    await screen.findByRole('tree');
    await user.click(row('Women'));
    const sheet = await screen.findByTestId('node-sheet');
    await user.click(within(sheet).getByRole('button', { name: 'Rename' }));
    const save = within(sheet).getByRole('button', { name: 'Rename' });
    expect(save).toBeDisabled();
    const name = within(sheet).getByLabelText('Name');
    await user.clear(name);
    await user.type(name, 'Ladies');
    expect(save).toBeEnabled();
    await user.click(save);
    // The reason is required and named under its field.
    expect((await within(sheet).findAllByText('Required')).length).toBeGreaterThan(0);
    await user.type(within(sheet).getByLabelText(/Reason/), 'Better wording');
    await user.click(save);
    await waitFor(() => expect(screen.getByRole('button', { name: /^Open Ladies to see/ })).toBeInTheDocument());
    expect((await nodeIn('Fashion › Ladies › Dresses')).productCount).toBe(42);
    expect((await api().audit()).items[0]).toMatchObject({ action: 'NODE_RENAMED', reason: 'Better wording', actorName: 'Ifeoma Okafor' });
  });

  it('will not retire a category that has products and says why; an empty one is retired and restored', async () => {
    const user = userEvent.setup();
    await renderScreen(<CategoriesView />);
    await screen.findByRole('tree');
    await user.click(row('Women'));
    let sheet = await screen.findByTestId('node-sheet');
    expect(within(sheet).getByRole('button', { name: 'Retire' })).toBeDisabled();
    expect(within(sheet).getByText(/still has 85 products, so it cannot be retired/)).toBeInTheDocument();
    await user.keyboard('{Escape}');

    await user.click(row('Hair care'));
    sheet = await screen.findByTestId('node-sheet');
    await user.click(within(sheet).getByRole('button', { name: 'Retire' }));
    await user.type(within(sheet).getByLabelText(/Reason/), 'No seller uses it');
    await user.click(within(sheet).getByRole('button', { name: 'Retire' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: /^Open Hair care to see/ })).not.toBeInTheDocument());
    expect((await nodeIn('Beauty › Skin & hair › Hair care')).active).toBe(false);

    await user.click(screen.getByLabelText(/Show retired/));
    await user.click(await screen.findByRole('button', { name: /^Open Hair care to see/ }));
    sheet = await screen.findByTestId('node-sheet');
    expect(within(sheet).getByText('Retired')).toBeInTheDocument();
    await user.click(within(sheet).getByRole('button', { name: 'Restore' }));
    await user.type(within(sheet).getByLabelText(/Reason/), 'Sellers asked');
    await user.click(within(sheet).getByRole('button', { name: 'Restore' }));
    await waitFor(async () => expect((await nodeIn('Beauty › Skin & hair › Hair care')).active).toBe(true));
  });

  it('moves a product type to another category', async () => {
    const user = userEvent.setup();
    await renderScreen(<CategoriesView />);
    await screen.findByRole('tree');
    await user.click(row('Tops'));
    const sheet = await screen.findByTestId('node-sheet');
    await user.click(within(sheet).getByRole('button', { name: 'Move' }));
    const save = within(sheet).getByRole('button', { name: 'Move' });
    expect(save).toBeDisabled();
    await user.selectOptions(within(sheet).getByLabelText('Move to category'), 'Fashion › Men');
    await user.type(within(sheet).getByLabelText(/Reason/), 'Tops are unisex');
    await user.click(save);
    await waitFor(async () => expect((await api().tree()).nodes.some((n) => n.path === 'Fashion › Men › Tops')).toBe(true));
  });

  it('adds a department and then a category in it, each with a reason', async () => {
    const user = userEvent.setup();
    await renderScreen(<CategoriesView />);
    await screen.findByRole('tree');
    await user.click(screen.getByRole('button', { name: 'Add a department' }));
    let sheet = await screen.findByTestId('node-sheet');
    await user.type(within(sheet).getByLabelText('Name'), 'Toys');
    await user.click(within(sheet).getByRole('button', { name: 'Add' }));
    expect((await within(sheet).findAllByText('Required')).length).toBeGreaterThan(0);
    await user.type(within(sheet).getByLabelText(/Reason/), 'New department');
    await user.click(within(sheet).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(screen.getByRole('button', { name: /^Open Toys to see/ })).toBeInTheDocument());
    sheet = await screen.findByTestId('node-sheet');
    await user.click(within(sheet).getByRole('button', { name: 'Add a category' }));
    await user.type(within(sheet).getByLabelText('Name'), 'Board games');
    await user.type(within(sheet).getByLabelText(/Reason/), 'Games shelf');
    await user.click(within(sheet).getByRole('button', { name: 'Add' }));
    await waitFor(async () => expect((await api().tree()).nodes.some((n) => n.path === 'Toys › Board games')).toBe(true));
  });

  it('arranges siblings with the arrows, then saves the order with a reason', async () => {
    const user = userEvent.setup();
    await renderScreen(<CategoriesView />);
    await screen.findByRole('tree');
    await user.click(screen.getByRole('button', { name: 'Move Groceries up' }));
    expect(await screen.findByText(/New order for the departments \(not saved yet\)/)).toBeInTheDocument();
    const names = () => Array.from(screen.getByRole('tree').children).map((li) => li.querySelector('strong')?.textContent);
    expect(names()[0]).toBe('Groceries');
    await user.click(screen.getByRole('button', { name: 'Save order' }));
    const dialog = await screen.findByRole('dialog', { name: 'Save the new order' });
    expect(within(dialog).getByRole('button', { name: 'Save order' })).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/Reason/), 'Groceries sell most');
    await user.click(within(dialog).getByRole('button', { name: 'Save order' }));
    await waitFor(() => expect(screen.queryByText(/not saved yet/)).not.toBeInTheDocument());
    const tree = await api().tree();
    expect(tree.nodes.filter((n) => n.parentId === null).sort((a, b) => a.position - b.position)[0]?.name).toBe('Groceries');
  });

  it('shows the tree read-only to staff who cannot edit it', async () => {
    asRoles('platform.catalog_manager');
    await renderScreen(<CategoriesView />);
    await screen.findByRole('tree');
    expect(screen.getByRole('button', { name: 'Add a department' })).toBeInTheDocument();
  });
});

describe('product-type details with the live seller-form preview', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-24T12:00:00Z'));
    asRoles(null);
    nav.set('/moderation?tab=categories');
  });

  it('previews the form as it is edited: a new detail appears, "not asked" hides it, and nothing saves without a reason', async () => {
    const user = userEvent.setup();
    await renderScreen(<CategoriesView />);
    await screen.findByRole('tree');
    const books = screen.getAllByRole('button', { name: /^Open Books to see and change it/ });
    await user.click(books[books.length - 1]!);
    let sheet = await screen.findByTestId('node-sheet');
    await user.click(within(sheet).getByRole('button', { name: 'Edit its details' }));
    sheet = await screen.findByTestId('type-editor');
    const preview = within(sheet).getByTestId('seller-form-preview');
    expect(within(preview).getByText('ISBN-13')).toBeInTheDocument();
    expect(within(preview).getByTestId('preview-author')).toBeInTheDocument();
    expect(within(preview).queryByTestId('preview-brand')).not.toBeInTheDocument();
    const save = within(sheet).getByRole('button', { name: 'Save the details' });
    expect(save).toBeDisabled();

    await user.click(within(sheet).getByRole('button', { name: 'Add a detail' }));
    await user.type(within(sheet).getByLabelText('What sellers see'), 'Brand');
    expect(within(sheet).getByLabelText('Key')).toHaveValue('brand');
    expect(within(preview).getByTestId('preview-brand')).toBeInTheDocument();
    // A brand is only an attribute: ask for it, make it required, then stop asking.
    await user.click(within(sheet).getByLabelText('Required'));
    expect(within(preview).getByTestId('preview-brand')).toHaveTextContent('Brand *');
    expect(save).toBeEnabled();
    await user.click(within(sheet).getByLabelText(/Ask sellers for this/));
    expect(within(preview).queryByTestId('preview-brand')).not.toBeInTheDocument();
    await user.click(within(sheet).getByLabelText(/Ask sellers for this/));
    expect(within(preview).getByTestId('preview-brand')).toBeInTheDocument();

    await user.click(save);
    expect(await within(sheet).findAllByText('Say why in at least 3 characters.')).not.toHaveLength(0);
    await user.type(within(sheet).getByLabelText(/Why are you changing this/), 'Publishers are brands too');
    await user.click(save);
    await waitFor(async () => expect((await api().productType('book')).attributes.map((a) => a.key)).toContain('brand'));
    expect((await api().audit('book')).items[0]).toMatchObject({ action: 'TYPE_UPDATED', reason: 'Publishers are brands too' });
  });

  it('adds choices and units, and turns food and regulated sections on only with their traits', async () => {
    const type = await api().productType('top');
    renderWithProviders(<SellerFormPreview type={{ ...type, traits: { ...type.traits, food: true }, attributes: [...type.attributes, { key: 'shelf', label: 'Shelf life', kind: 'NUMBER', options: [], units: ['days', 'months'], required: true, section: 'FOOD', help: 'From the date of manufacture', applies: true }] }} />);
    const previews = screen.getAllByTestId('seller-form-preview');
    const food = previews[previews.length - 1]!;
    expect(within(food).getByText('Food')).toBeInTheDocument();
    expect(within(food).getByTestId('preview-shelf')).toHaveTextContent('Shelf life *');
    expect(within(food).getByText('days')).toBeInTheDocument();
    expect(within(food).getByText('From the date of manufacture')).toBeInTheDocument();
  });

  it('a new product type starts from another one and opens its details to refine', async () => {
    const user = userEvent.setup();
    await renderScreen(<CategoriesView />);
    await screen.findByRole('tree');
    await user.click(row('Pantry'));
    const sheet = await screen.findByTestId('node-sheet');
    await user.click(within(sheet).getByRole('button', { name: 'Add a product type' }));
    await user.type(within(sheet).getByLabelText('Name'), 'Hot sauces');
    await user.selectOptions(within(sheet).getByLabelText(/New details start from/), 'Groceries');
    await user.type(within(sheet).getByLabelText(/Reason/), 'Sellers keep asking');
    await user.click(within(sheet).getByRole('button', { name: 'Add' }));
    const editor = await screen.findByTestId('type-editor');
    expect(within(editor).getByLabelText('Name of the details')).toHaveValue('Hot sauces');
    expect(within(editor).getByTestId('seller-form-preview')).toBeInTheDocument();
    expect((await api().tree()).nodes.find((n) => n.path === 'Groceries › Pantry › Hot sauces')?.productType).toBe('hot_sauces');
  });
});

describe('the Catalog page and category requests', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-24T12:00:00Z'));
    asRoles(null);
    nav.set('/moderation');
  });

  it('shows the tree and the requests tabs, with the number of requests waiting, to staff who manage the taxonomy', async () => {
    asRoles('platform.catalog_manager');
    await renderScreen(<CatalogView />);
    expect(await screen.findByRole('tab', { name: 'Categories' })).toBeInTheDocument();
    expect(await screen.findByRole('tab', { name: 'Category requests (2)' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Products' })).toBeInTheDocument();
  });

  it('hides both tabs from staff without catalog:taxonomy', async () => {
    asRoles('platform.operations_manager');
    await renderScreen(<CatalogView />);
    expect(await screen.findByRole('tab', { name: 'Products' })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Categories' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: /Category requests/ })).not.toBeInTheDocument();
  });

  it('lists requests by the seller’s name and approves one: the category and its details are created in one step', async () => {
    const user = userEvent.setup();
    nav.set('/moderation?tab=category-requests');
    await renderScreen(<CatalogView />);
    const table = await screen.findByRole('table', { name: 'Category requests' });
    expect(within(table).getByText('Lagos Threads')).toBeInTheDocument();
    expect(within(table).getByText('Fashion › Women › Bridal aso-ebi sets')).toBeInTheDocument();
    expect(within(table).queryByText('req-1')).not.toBeInTheDocument();
    await user.click(within(table).getByText('Lagos Threads'));
    const sheet = await screen.findByRole('dialog', { name: 'Bridal aso-ebi sets' });
    expect(within(sheet).getAllByText(/REQ-000041/).length).toBeGreaterThan(0);
    expect(within(sheet).getByText('Products waiting for this category')).toBeInTheDocument();
    await user.click(within(sheet).getByRole('button', { name: 'Approve & create' }));
    const form = await screen.findByRole('dialog', { name: 'Approve and create' });
    expect(within(form).getByText(/As suggested: Product type: Fashion › Women › Bridal aso-ebi sets — will be created/)).toBeInTheDocument();
    expect(within(form).getByLabelText('Where it goes')).toHaveValue((await nodeIn('Fashion › Women')).id);
    await user.click(within(form).getByRole('button', { name: 'Approve & create' }));
    await waitFor(async () => expect((await api().tree()).nodes.some((n) => n.path === 'Fashion › Women › Bridal aso-ebi sets')).toBe(true));
    expect((await api().tree()).pendingRequests).toBe(1);
  });

  it('rejecting needs a reason the seller sees', async () => {
    const user = userEvent.setup();
    nav.set('/moderation?tab=category-requests&open=req-2');
    await renderScreen(<CatalogView />);
    const sheet = await screen.findByRole('dialog', { name: 'Hot sauces' });
    await user.click(within(sheet).getByRole('button', { name: 'Reject' }));
    const form = await screen.findByRole('dialog', { name: 'Reject the category request' });
    await user.click(within(form).getByRole('button', { name: 'Reject' }));
    expect((await within(form).findAllByText('Required')).length).toBeGreaterThan(0);
    await user.type(within(form).getByLabelText(/Reason/), 'Already covered by Sauces');
    await user.click(within(form).getByRole('button', { name: 'Reject' }));
    await waitFor(async () => expect((await api().tree()).pendingRequests).toBe(1));
  });
});
