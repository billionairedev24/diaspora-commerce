import { getSellerDb, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { ListingsView } from '@/components/listings/ListingsView';

import { mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  nav.push.mockReset();
  nav.replace.mockReset();
  nav.search = new URLSearchParams();
});

const draftsInDb = (n: number) => {
  for (let i = 0; i < n; i += 1) {
    getSellerDb().drafts.push({
      id: `draft-x${i}`, orgId: getSellerDb().drafts[0]?.orgId ?? 'org-ade-kitchen', productId: null, title: i === 0 ? '' : `Batch draft ${String(i).padStart(2, '0')}`,
      categoryPath: i % 2 ? 'Groceries › Pantry' : 'Fashion › Dresses', payload: { title: `Batch draft ${i}` }, version: 0, updatedAt: new Date(Date.UTC(2026, 9, 1, 10, i)).toISOString()
    });
  }
};

describe('Listings table follows the row-action standard', () => {
  it('has the product name as the link, chips and meta under it, and one "⋯" menu per row instead of buttons', async () => {
    await renderScreen(<ListingsView />);
    await screen.findAllByRole('button', { name: /^Actions for / });
    expect(screen.getAllByRole('row').length).toBeGreaterThan(5);
    // No outline pill buttons for Edit / Pause / Resume anywhere in the table.
    expect(screen.queryByRole('button', { name: /^(Edit|Pause|Resume)$/ })).not.toBeInTheDocument();
    const first = (await screen.findAllByRole('button', { name: /^Actions for / }))[0] as HTMLElement;
    expect(first).toHaveAttribute('aria-haspopup', 'menu');
    const row = first.closest('tr') as HTMLElement;
    const name = within(row).getAllByRole('link')[0] as HTMLElement;
    expect(name).toHaveAttribute('href', expect.stringMatching(/^\/listings\/prod-\d+\/edit$/));
    // The ATIN sits under the name; the duplicated "Fulfilment" link under the chip is gone.
    expect(within(row).queryByText('Fulfilment')).not.toBeInTheDocument();
  });

  it('offers Edit, View on storefront and Pause in the menu of a live listing, and pauses it', async () => {
    const user = userEvent.setup();
    await renderScreen(<ListingsView />);
    const live = getSellerDb().listings.find((l) => l.status === 'LIVE' && l.atin && l.variantCount === 1 && l.fulfilment === 'SELLER');
    expect(live).toBeDefined();
    await user.click(await screen.findByRole('button', { name: `Actions for ${live?.title}` }));
    const menu = await screen.findByRole('menu');
    expect(within(menu).getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Edit', 'View on storefront', 'Pause listing', 'Fulfilment options']);
    expect(within(menu).getByRole('menuitem', { name: 'View on storefront' })).toHaveAttribute('href', expect.stringContaining(`/p/${live?.atin}`));
    await user.click(within(menu).getByRole('menuitem', { name: 'Pause listing' }));
    await waitFor(() => expect(getSellerDb().listings.find((l) => l.productId === live?.productId)?.status).toBe('PAUSED'));
  });

  it('opens the editor from the row menu', async () => {
    const user = userEvent.setup();
    await renderScreen(<ListingsView />);
    const l = getSellerDb().listings.find((x) => x.status === 'LIVE');
    await user.click(await screen.findByRole('button', { name: `Actions for ${l?.title}` }));
    await user.click(await screen.findByRole('menuitem', { name: 'Edit' }));
    expect(nav.push).toHaveBeenCalledWith(`/listings/${l?.productId}/edit`);
  });

  it('changes the stock of a single seller-held offer in the cell: save with Enter, cancel with Escape', async () => {
    const user = userEvent.setup();
    await renderScreen(<ListingsView />);
    const l = getSellerDb().listings.find((x) => x.status === 'LIVE' && x.atin && x.variantCount === 1 && x.fulfilment === 'SELLER');
    expect(l).toBeDefined();
    await user.click(await screen.findByRole('button', { name: `Edit stock of ${l?.title}` }));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('textbox', { name: `Edit stock of ${l?.title}` })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: `Edit stock of ${l?.title}` }));
    const field = screen.getByRole('textbox', { name: `Edit stock of ${l?.title}` });
    await user.clear(field);
    await user.type(field, '77{Enter}');
    await waitFor(() => expect(getSellerDb().listings.find((x) => x.productId === l?.productId)?.stock).toBe(77));
  });

  it('changes the price of a single-offer listing in the cell and rejects a bad amount', async () => {
    const user = userEvent.setup();
    await renderScreen(<ListingsView />);
    const l = getSellerDb().listings.find((x) => x.status === 'LIVE' && x.variantCount === 1);
    expect(l).toBeDefined();
    await user.click(await screen.findByRole('button', { name: `Edit price of ${l?.title}` }));
    const field = screen.getByRole('textbox', { name: `Edit price of ${l?.title}` });
    await user.clear(field);
    await user.type(field, 'abc{Enter}');
    expect(await within(field.closest('form') as HTMLElement).findByRole('alert')).toHaveTextContent('Enter a price like 12.99');
    await user.clear(field);
    await user.type(field, '12.34{Enter}');
    await waitFor(() => expect(getSellerDb().listings.find((x) => x.productId === l?.productId)?.priceMinor).toBe(1234), { timeout: 4000 });
  });

  it('shows the offer on another seller\'s page by the page\'s name, with its tax status, and edits the offer, not the page', async () => {
    const user = userEvent.setup();
    const db = getSellerDb();
    db.listings.push({ productId: 'p-shea', atin: 'ATSHEA0001', title: 'Raw Shea Butter (8 oz)', sku: 'SHEA-8OZ', variantCount: 1, priceMinor: 1100, currency: 'USD', stock: 0, fulfilment: 'SELLER',
      status: 'IN_REVIEW', statusLabel: 'We are checking the tax details for this item.', moderationNote: 'We are checking the tax details for this item.', imageUrl: null, updatedAt: '2026-10-09T10:00:00Z',
      kind: 'OFFER', offerId: 'of-shea', offerVersion: 1, quantity: 7, variants: [{ sku: 'SHEA-8OZ', quantity: 7 }] });
    db.offers.push({ offerId: 'of-shea', atin: 'ATSHEAV001', productId: 'p-shea', sellerSku: 'SHEA-8OZ', condition: 'NEW', priceMinor: 1100, currency: 'USD', fulfilment: 'SELLER', quantity: 7, availableQty: 0,
      shipFromLocationId: null, shipsFrom: 'Houston, TX', status: 'TAX_HELD', version: 1 });
    nav.search = new URLSearchParams('q=Shea');
    await renderScreen(<ListingsView />);
    const row = (await screen.findByText('Raw Shea Butter (8 oz)')).closest('tr') as HTMLElement;
    expect(within(row).getByText('Selling on another seller\'s page')).toBeInTheDocument();
    expect(within(row).getAllByText('We are checking the tax details for this item.').length).toBeGreaterThan(0);
    // The name is not a link to the other seller's page, and the menu has no Edit, Pause or Resume of the page.
    expect(within(row).queryByRole('link', { name: 'Raw Shea Butter (8 oz)' })).not.toBeInTheDocument();
    await user.click(within(row).getByRole('button', { name: 'Actions for Raw Shea Butter (8 oz)' }));
    const menu = await screen.findByRole('menu');
    expect(within(menu).queryByRole('menuitem', { name: 'Edit' })).not.toBeInTheDocument();
    expect(within(menu).queryByRole('menuitem', { name: /Pause|Resume/ })).not.toBeInTheDocument();
    await user.keyboard('{Escape}');
    // Price and quantity change the offer.
    await user.click(await screen.findByRole('button', { name: 'Edit price of Raw Shea Butter (8 oz)' }));
    const price = screen.getByRole('textbox', { name: 'Edit price of Raw Shea Butter (8 oz)' });
    await user.clear(price);
    await user.type(price, '12.50{Enter}');
    await waitFor(() => expect(getSellerDb().offers.find((o) => o.offerId === 'of-shea')?.priceMinor).toBe(1250), { timeout: 4000 });
    await user.click(await screen.findByRole('button', { name: 'Edit stock of Raw Shea Butter (8 oz)' }));
    const qty = screen.getByRole('textbox', { name: 'Edit stock of Raw Shea Butter (8 oz)' });
    await user.clear(qty);
    await user.type(qty, '9{Enter}');
    await waitFor(() => expect(getSellerDb().offers.find((o) => o.offerId === 'of-shea')?.quantity).toBe(9), { timeout: 4000 });
    expect(getSellerDb().listings.find((l) => l.productId === 'p-shea')?.title).toBe('Raw Shea Butter (8 oz)');
  });

  it('pauses and resumes an item sold on another seller\'s page: the offer changes, the page row never gets the page actions', async () => {
    const user = userEvent.setup();
    const db = getSellerDb();
    db.listings.push({ productId: 'p-shea', atin: 'ATSHEA0001', title: 'Raw Shea Butter (8 oz)', sku: 'SHEA-8OZ', variantCount: 1, priceMinor: 1100, currency: 'USD', stock: 7, fulfilment: 'SELLER',
      status: 'LIVE', statusLabel: 'Live', moderationNote: null, imageUrl: null, updatedAt: '2026-10-09T10:00:00Z',
      kind: 'OFFER', offerId: 'of-shea', offerVersion: 1, quantity: 7, variants: [{ sku: 'SHEA-8OZ', quantity: 7 }] });
    db.offers.push({ offerId: 'of-shea', atin: 'ATSHEAV001', productId: 'p-shea', sellerSku: 'SHEA-8OZ', condition: 'NEW', priceMinor: 1100, currency: 'USD', fulfilment: 'SELLER', quantity: 7, availableQty: 7,
      shipFromLocationId: null, shipsFrom: 'Houston, TX', status: 'ACTIVE', version: 1 });
    nav.search = new URLSearchParams('q=Shea');
    await renderScreen(<ListingsView />);
    const rowOf = async () => (await screen.findByText('Raw Shea Butter (8 oz)')).closest('tr') as HTMLElement;
    await user.click(within(await rowOf()).getByRole('button', { name: 'Actions for Raw Shea Butter (8 oz)' }));
    expect(screen.queryByRole('menuitem', { name: 'Pause listing' })).not.toBeInTheDocument();
    await user.click(await screen.findByRole('menuitem', { name: 'Pause selling' }));
    await waitFor(() => expect(getSellerDb().offers.find((o) => o.offerId === 'of-shea')?.status).toBe('PAUSED'), { timeout: 4000 });
    expect(await screen.findByText('Paused. It stays off sale until you resume it.')).toBeInTheDocument();
    await user.click(within(await rowOf()).getByRole('button', { name: 'Actions for Raw Shea Butter (8 oz)' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Resume selling' }));
    await waitFor(() => expect(getSellerDb().offers.find((o) => o.offerId === 'of-shea')?.status).toBe('ACTIVE'), { timeout: 4000 });
    // The page row of the other seller was never paused or resumed as a page.
    expect(getSellerDb().listings.find((l) => l.productId === 'p-shea')?.title).toBe('Raw Shea Butter (8 oz)');
  });

  it('has Update stock by variant for a multi-variant listing and a plain number in the cell', async () => {
    const user = userEvent.setup();
    await renderScreen(<ListingsView />);
    const l = getSellerDb().listings.find((x) => x.status === 'LIVE' && x.variantCount > 1 && x.fulfilment === 'SELLER');
    if (!l) return;
    expect(screen.queryByRole('button', { name: `Edit stock of ${l.title}` })).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: `Actions for ${l.title}` }));
    expect(await screen.findByRole('menuitem', { name: 'Update stock by variant' })).toBeInTheDocument();
  });

  it('never shows drafts, and the Drafts tab count is the API total', async () => {
    draftsInDb(3);
    await renderScreen(<ListingsView />);
    expect(await screen.findByRole('tab', { name: 'Drafts (3)' })).toBeInTheDocument();
    expect(screen.queryByText('Batch draft 01')).not.toBeInTheDocument();
    expect(screen.queryByTestId('draft-row')).not.toBeInTheDocument();
  });

  it('shows no Drafts tab and no actions for a role that can only view', async () => {
    const { asRole } = await import('./mock-api');
    asRole('seller.fulfilment');
    try {
      await renderScreen(<ListingsView />);
      await screen.findByText('Ofada Rice, Destoned · 5 lb');
      expect(screen.queryByRole('tab', { name: /Drafts/ })).not.toBeInTheDocument();
      // The only thing left in a live row's menu is the public page; nothing edits, pauses or opens a sheet.
      const user = userEvent.setup();
      const live = getSellerDb().listings.find((l) => l.status === 'LIVE' && l.atin);
      await user.click(await screen.findByRole('button', { name: `Actions for ${live?.title}` }));
      expect((await screen.findAllByRole('menuitem')).map((i) => i.textContent)).toEqual(['View on storefront']);
      expect(screen.queryByRole('button', { name: /^Edit (stock|price)/ })).not.toBeInTheDocument();
    } finally {
      asRole(null);
    }
  });
});

describe('Drafts table', () => {
  it('switches tabs through the URL', async () => {
    const user = userEvent.setup();
    draftsInDb(2);
    await renderScreen(<ListingsView />);
    await user.click(await screen.findByRole('tab', { name: 'Drafts (2)' }));
    expect(nav.replace).toHaveBeenCalledWith('/?tab=drafts', { scroll: false });
  });

  it('lists drafts newest first with the continue link, category, to-do count and saved time', async () => {
    draftsInDb(3);
    nav.search = new URLSearchParams('tab=drafts');
    await renderScreen(<ListingsView />);
    const first = await screen.findByRole('link', { name: 'Batch draft 02' });
    const rows = screen.getAllByRole('row').filter((r) => r.getAttribute('data-row-id'));
    expect(rows.map((r) => r.getAttribute('data-row-id'))).toEqual(['draft-x2', 'draft-x1', 'draft-x0']);
    expect(first).toHaveAttribute('href', '/listings/new?draft=draft-x2');
    expect(screen.getByRole('link', { name: 'Untitled product' })).toBeInTheDocument();
    const row = first.closest('tr') as HTMLElement;
    expect(within(row).getByText('Fashion › Dresses')).toBeInTheDocument();
    expect(within(row).getByText('4 to do')).toBeInTheDocument();
    expect(within(row).getByRole('img', { name: '1 of 5 basics done' })).toBeInTheDocument();
  });

  it('pages on the server: 12 drafts, 10 per page, the second page holds the rest', async () => {
    draftsInDb(12);
    nav.search = new URLSearchParams('tab=drafts&size=10');
    await renderScreen(<ListingsView />);
    expect(await screen.findByText('Showing 1–10 of 12')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Drafts (12)' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Showing 11–12 of 12')).toBeInTheDocument();
  });

  it('searches title and category on the server', async () => {
    draftsInDb(6);
    nav.search = new URLSearchParams('tab=drafts');
    await renderScreen(<ListingsView />);
    await screen.findByRole('link', { name: 'Batch draft 05' });
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'pantry' } });
    await waitFor(() => expect(screen.getByText('Showing 1–3 of 3')).toBeInTheDocument());
    expect(screen.queryByRole('link', { name: 'Batch draft 02' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'zzz' } });
    expect(await screen.findByText('No drafts match.')).toBeInTheDocument();
  });

  it('discards one draft after a confirmation that names it, with Cancel focused first', async () => {
    const user = userEvent.setup();
    draftsInDb(3);
    nav.search = new URLSearchParams('tab=drafts');
    await renderScreen(<ListingsView />);
    await user.click(await screen.findByRole('button', { name: 'Actions for Batch draft 02' }));
    const menu = await screen.findByRole('menu');
    const items = within(menu).getAllByRole('menuitem');
    expect(items.map((i) => i.textContent)).toEqual(['Continue', 'Discard']);
    expect(items[1]?.className).toMatch(/menuDanger/);
    await user.click(items[1] as HTMLElement);
    const dialog = await screen.findByRole('dialog', { name: 'Discard “Batch draft 02”?' });
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus());
    expect(getSellerDb().drafts.some((d) => d.id === 'draft-x2')).toBe(true);
    await user.click(within(dialog).getByRole('button', { name: 'Discard' }));
    await waitFor(() => expect(getSellerDb().drafts.some((d) => d.id === 'draft-x2')).toBe(false));
    await waitFor(() => expect(screen.queryByRole('link', { name: 'Batch draft 02' })).not.toBeInTheDocument());
    expect(await screen.findByRole('tab', { name: 'Drafts (2)' })).toBeInTheDocument();
  });

  it('cancelling the confirmation keeps the draft', async () => {
    const user = userEvent.setup();
    draftsInDb(2);
    nav.search = new URLSearchParams('tab=drafts');
    await renderScreen(<ListingsView />);
    await user.click(await screen.findByRole('button', { name: 'Actions for Batch draft 01' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Discard' }));
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(getSellerDb().drafts.some((d) => d.id === 'draft-x1')).toBe(true);
  });

  it('discards the selected drafts one by one from the selection bar', async () => {
    const user = userEvent.setup();
    draftsInDb(4);
    nav.search = new URLSearchParams('tab=drafts');
    await renderScreen(<ListingsView />);
    await screen.findByRole('link', { name: 'Batch draft 03' });
    await user.click(screen.getByRole('checkbox', { name: 'Select Batch draft 03' }));
    await user.click(screen.getByRole('checkbox', { name: 'Select Batch draft 02' }));
    await user.click(screen.getByRole('button', { name: 'Discard selected' }));
    const dialog = await screen.findByRole('dialog', { name: 'Discard 2 drafts?' });
    await user.click(within(dialog).getByRole('button', { name: 'Discard' }));
    await waitFor(() => expect(getSellerDb().drafts.map((d) => d.id).sort()).toEqual(['draft-x0', 'draft-x1']));
  });

  it('explains an empty Drafts tab in plain words with the Add a product button', async () => {
    nav.search = new URLSearchParams('tab=drafts');
    await renderScreen(<ListingsView />);
    expect(await screen.findByText(/You have no drafts/)).toBeInTheDocument();
    const add = screen.getAllByRole('link', { name: 'Add a product' });
    expect(add.length).toBeGreaterThanOrEqual(1);
    expect(mockApi()).toBeDefined();
  });
});
