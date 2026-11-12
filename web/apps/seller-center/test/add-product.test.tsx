import { File as NodeFile } from 'node:buffer';

import { type CategoryNode, type ProductDraft } from '@afrotransact/api-client';
import { approveMockPage, CATEGORY_TREE, getSellerDb, gtinWith, MOCK_GTIN, resetSellerDb, SELLER_ORG } from '@afrotransact/api-client/mocks/seller';
import { MOCK_FOOD_ORG } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { AddProductFlow } from '@/components/listings/add/AddProductFlow';

import { bffCalls, mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

globalThis.File = NodeFile as unknown as typeof File;

beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  nav.push.mockReset();
  nav.replace.mockReset();
  nav.search = new URLSearchParams();
  bffCalls.length = 0;
});

const uploads = () => bffCalls.filter((c) => c.includes('/uploads'));

const LOOKUP = { timeout: 4000 };

async function typeBarcode(code: string) {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText(/Barcode \(UPC, EAN, ISBN\)/), code);
  return user;
}

describe('Add a product starts with the barcode (S382)', () => {
  it('is the first step: a barcode, a brand and part number, or no barcode, and no product form yet', async () => {
    await renderScreen(<AddProductFlow />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Add a product' })).toBeInTheDocument();
    const modes = within(screen.getByRole('group', { name: 'How do you want to find the product?' }));
    expect(modes.getAllByRole('button').map((b) => b.textContent)).toEqual(['I have the barcode', 'Search by brand and part number', 'I don’t have a barcode']);
    expect(screen.queryByRole('form', { name: 'Product' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Product title')).not.toBeInTheDocument();
  });

  it('shows the existing product after the 12th digit, with its photo, title, brand, options and seller count', async () => {
    await renderScreen(<AddProductFlow />);
    await typeBarcode('0123 4567 8905');
    expect(await screen.findByTestId('match-title', undefined, LOOKUP)).toHaveTextContent('Raw Shea Butter, 8 oz');
    const card = within(screen.getByTestId('match-result'));
    expect(card.getByText('This product is already on AfroTransact')).toBeInTheDocument();
    expect(card.getByText('Kente Naturals')).toBeInTheDocument();
    expect(card.getByText('Size: 8 oz')).toBeInTheDocument();
    expect(card.getByTestId('match-sellers')).toHaveTextContent('2 sellers');
    expect(card.getByRole('img')).toHaveAttribute('alt', 'Raw Shea Butter, 8 oz');
    expect(card.getByRole('button', { name: 'Yes, sell this' })).toBeEnabled();
    expect(card.getByRole('button', { name: 'No, this is not my product' })).toBeInTheDocument();
    // No id anywhere on the screen.
    expect(document.body.textContent).not.toMatch(/ATSHEA|AT[0-9A-Z]{8}/);
  });

  it('opens only the offer fields for a page that exists: no photo, title or attribute field, and the page content says whose it is', async () => {
    await renderScreen(<AddProductFlow />);
    const user = await typeBarcode(MOCK_GTIN.live);
    await user.click(await screen.findByRole('button', { name: 'Yes, sell this' }, LOOKUP));
    expect(await screen.findByRole('heading', { level: 1, name: 'Sell this product' })).toBeInTheDocument();
    const form = within(screen.getByRole('form', { name: 'Sell this product' }));
    for (const label of ['Price (USD)', 'Compare-at price (optional)', 'Condition', 'Quantity', 'Ships from', 'SKU']) expect(form.getByLabelText(new RegExp(label.replace(/[()]/g, '\\$&')))).toBeInTheDocument();
    expect(form.getByText('Who ships it?')).toBeInTheDocument();
    expect(form.getByRole('radio', { name: 'I ship it' })).toBeChecked();
    for (const gone of ['Product title', 'Category', 'Description', 'Brand']) expect(screen.queryByLabelText(gone)).not.toBeInTheDocument();
    expect(screen.queryByTestId('image-input')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Photos' })).not.toBeInTheDocument();
    expect(screen.getByTestId('content-locked')).toHaveTextContent('The photos, title and details on this page belong to the page');
    expect(screen.getByRole('button', { name: 'Suggest a change' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'This is the wrong product' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'This page is a duplicate' })).toBeInTheDocument();
    expect(bffCalls.some((c) => c.startsWith('POST') && c.includes('/offers'))).toBe(false);
    expect(uploads()).toEqual([]);
  });

  it('sells the page with a price and stock, and says it is live', async () => {
    await renderScreen(<AddProductFlow />);
    const user = await typeBarcode(MOCK_GTIN.live);
    await user.click(await screen.findByRole('button', { name: 'Yes, sell this' }, LOOKUP));
    await screen.findByRole('heading', { level: 1, name: 'Sell this product' });
    await user.click(screen.getByRole('button', { name: 'Start selling this product' }));
    // Nothing is sent while a field is wrong; the first one takes focus.
    expect(await screen.findByText('Enter a price above zero, like 12.50.')).toBeInTheDocument();
    expect(screen.getByLabelText('Price (USD)')).toHaveFocus();
    await user.type(screen.getByLabelText('Price (USD)'), '12.50');
    await user.type(screen.getByLabelText('Quantity'), '8');
    await user.click(screen.getByRole('button', { name: 'Start selling this product' }));
    const done = await screen.findByTestId('offer-result');
    expect(done).toHaveAttribute('data-status', 'ACTIVE');
    expect(within(done).getByText('You are selling this product')).toBeInTheDocument();
    expect(within(done).getByRole('link', { name: 'Go to listings' })).toHaveAttribute('href', '/listings');
    expect(done.textContent).not.toMatch(/AT[0-9A-Z]{8}/);
  });

  it('says “We are checking the tax details for this item.” while the seller’s own tax decision is missing', async () => {
    await renderScreen(<AddProductFlow />);
    const user = await typeBarcode(MOCK_GTIN.taxHeld);
    await user.click(await screen.findByRole('button', { name: 'Yes, sell this' }, LOOKUP));
    await screen.findByRole('heading', { level: 1, name: 'Sell this product' });
    await user.type(screen.getByLabelText('Price (USD)'), '6');
    await user.type(screen.getByLabelText('Quantity'), '20');
    await user.click(screen.getByRole('button', { name: 'Start selling this product' }));
    const done = await screen.findByTestId('offer-result');
    expect(done).toHaveAttribute('data-status', 'TAX_HELD');
    expect(within(done).getByText('We are checking the tax details for this item.')).toBeInTheDocument();
    expect(within(done).getByText(/You do not need to do anything unless we ask you for a document/)).toBeInTheDocument();
  });

  it('asks for the condition note when the item is not new, and sends the fulfilment fields of the way chosen', async () => {
    await renderScreen(<AddProductFlow />);
    const user = await typeBarcode(MOCK_GTIN.live);
    await user.click(await screen.findByRole('button', { name: 'Yes, sell this' }, LOOKUP));
    await screen.findByRole('heading', { level: 1, name: 'Sell this product' });
    await user.selectOptions(screen.getByLabelText('Condition'), 'USED_GOOD');
    expect(screen.getByLabelText('Describe the condition')).toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'Fulfilled by AfroTransact (FBA)' }));
    expect(screen.queryByLabelText('Quantity')).not.toBeInTheDocument();
    expect(screen.getByText('Package size and weight')).toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'I ship it' }));
    expect(screen.getByLabelText('Quantity')).toBeInTheDocument();
  });

  it('answers a barcode that does not look right with the fix, and a store number with the plain message', async () => {
    await renderScreen(<AddProductFlow />);
    const user = await typeBarcode('012345678900');
    expect(await screen.findByText('That barcode does not look right. Did you mean 012345678905?', undefined, LOOKUP)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Use 012345678905' }));
    expect(await screen.findByTestId('match-title', undefined, LOOKUP)).toHaveTextContent('Raw Shea Butter, 8 oz');
    const input = screen.getByLabelText(/Barcode \(UPC, EAN, ISBN\)/);
    await user.clear(input);
    await user.type(input, '021234567893');
    expect(await screen.findByText('This number is for store use or coupons, not a product barcode.', undefined, LOOKUP)).toBeInTheDocument();
    expect(screen.getByText(/Use the barcode printed on the product itself/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Yes, sell this' })).not.toBeInTheDocument();
  });

  it('says why a page cannot be sold yet and offers no sell button', async () => {
    await renderScreen(<AddProductFlow />);
    await typeBarcode(MOCK_GTIN.gated);
    expect(await screen.findByText('You can’t sell this product yet', undefined, LOOKUP)).toBeInTheDocument();
    expect(screen.getByText('You need approval to sell this brand. Apply under Listings.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Yes, sell this' })).toBeDisabled();
  });

  it('continues into the full form with the barcode filled in and photos required when there is no page', async () => {
    await renderScreen(<AddProductFlow />);
    const user = await typeBarcode('9780306406157');
    expect(await screen.findByText('This product is not on AfroTransact yet', undefined, LOOKUP)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add the product' }));
    expect(await screen.findByRole('form', { name: 'Product' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Photos' })).toBeInTheDocument();
    expect(screen.getByTestId('image-input')).toBeInTheDocument();
    // Submitting without a photo says so; the barcode step can be taken again.
    expect(screen.getByRole('button', { name: 'Change the barcode' })).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('pf-primary'));
    expect((await screen.findAllByText('Add at least one image.')).length).toBeGreaterThan(0);
    // The barcode that found no page is already in the form (it shows once the category says the product has one).
    fireEvent.change(await screen.findByRole('combobox', { name: 'Category' }), { target: { value: 'books' } });
    fireEvent.click((await screen.findAllByRole('option'))[0] as HTMLElement);
    expect(await screen.findByLabelText(/ISBN|Barcode/, undefined, LOOKUP)).toHaveValue('9780306406157');
  });

  it('takes the no-barcode path with the brand, and says the item is checked', async () => {
    await renderScreen(<AddProductFlow />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'I don’t have a barcode' }));
    expect(screen.getByText('We are checking that this item has no barcode.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Brand'), 'Adire Atelier');
    await user.click(screen.getByRole('button', { name: 'Continue without a barcode' }));
    expect(await screen.findByRole('form', { name: 'Product' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Photos' })).toBeInTheDocument();
  });

  it('finds the page by brand and part number', async () => {
    await renderScreen(<AddProductFlow />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Search by brand and part number' }));
    await user.type(screen.getByLabelText('Brand'), 'Kente Naturals');
    await user.type(screen.getByLabelText('Part number'), 'sb 8');
    expect(await screen.findByTestId('match-title', undefined, LOOKUP)).toHaveTextContent('Raw Shea Butter, 8 oz');
  });

  it('files the wrong-product report from the card, and reads it as Waiting', async () => {
    await renderScreen(<AddProductFlow />);
    const user = await typeBarcode(MOCK_GTIN.live);
    await user.click(await screen.findByRole('button', { name: 'No, this is not my product' }, LOOKUP));
    await user.type(await screen.findByLabelText('What is different?'), 'Mine is the 16 oz jar');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByText('Thank you. We are checking this barcode. We will tell you within 2 business days.')).toBeInTheDocument();
    const list = await screen.findByTestId('change-requests', undefined, LOOKUP);
    expect(within(list).getByText('Waiting')).toBeInTheDocument();
    expect(within(list).getByText(/Wrong product/)).toBeInTheDocument();
    // A second one is refused in plain words.
    await user.click(screen.getByRole('button', { name: 'Close' }));
    await user.click(screen.getByRole('button', { name: 'No, this is not my product' }));
    await user.click(await screen.findByRole('button', { name: 'Send' }));
    expect(await screen.findByText('You already sent this. We will tell you when we have looked at it.')).toBeInTheDocument();
  });

  it('withdraws a request that is still waiting', async () => {
    await renderScreen(<AddProductFlow />);
    const user = await typeBarcode(MOCK_GTIN.live);
    await user.click(await screen.findByRole('button', { name: 'No, this is not my product' }, LOOKUP));
    await user.click(await screen.findByRole('button', { name: 'Send' }));
    const list = await screen.findByTestId('change-requests', undefined, LOOKUP);
    await user.click(within(list).getByRole('button', { name: 'Withdraw' }));
    await waitFor(() => expect(within(screen.getByTestId('change-requests')).getByText('Withdrawn')).toBeInTheDocument());
  });
});

describe('Seller A creates a page and seller B sells it by UPC without uploading an image (S382)', () => {
  const leaves = (nodes: CategoryNode[]): CategoryNode[] => nodes.flatMap((n) => (n.children.length ? leaves(n.children) : [n]));

  /** Seller A adds a product with a barcode, through the same API the form uses. */
  async function sellerCreatesPage(gtin: string): Promise<void> {
    const api = mockApi();
    const leaf = leaves(CATEGORY_TREE).find((l) => l.productType === 'book');
    if (!leaf) throw new Error('the mock tree has a book category');
    const draft: ProductDraft = { categoryId: leaf.id, title: 'Things Fall Apart, paperback', attributes: { author: 'Chinua Achebe', publisher: 'Heinemann', language: 'English', pages: '209', brand: 'Heinemann' },
      images: [{ url: 'https://img.afrotransact.dev/a-main.webp', width: 1600, height: 1600, bytes: 1000, contentType: 'image/webp' }], variationTheme: '',
      variants: [{ options: {}, sellerSku: 'TFA-1', gtin, priceMinor: 1299, quantity: 5 }] };
    await api.submitProduct(draft);
  }

  it('finds the page by the barcode once it is live, and the second seller adds a price and stock only', async () => {
    const gtin = '9780306406157';
    await sellerCreatesPage(gtin);
    expect(getSellerDb().matching?.pages.some((p) => p.ownerOrg === SELLER_ORG && p.title === 'Things Fall Apart, paperback')).toBe(true);

    await renderScreen(<AddProductFlow />, MOCK_FOOD_ORG);
    const user = await typeBarcode(gtin);
    // The page waits for review like the listing: the seller reads why, and cannot sell it yet.
    expect(await screen.findByText('This product page is not live yet. Try again once it is approved.', undefined, LOOKUP)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Yes, sell this' })).toBeDisabled();

    approveMockPage(getSellerDb(), gtin);
    // "Check barcode" asks again for the same number.
    await user.click(screen.getByRole('button', { name: 'Check barcode' }));
    expect(await screen.findByTestId('match-title', undefined, LOOKUP)).toHaveTextContent('Things Fall Apart, paperback');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Yes, sell this' })).toBeEnabled(), LOOKUP);
    await user.click(screen.getByRole('button', { name: 'Yes, sell this' }));
    await screen.findByRole('heading', { level: 1, name: 'Sell this product' });
    expect(screen.queryByTestId('image-input')).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Price (USD)'), '11.00');
    await user.type(screen.getByLabelText('Quantity'), '3');
    await user.click(screen.getByRole('button', { name: 'Start selling this product' }));
    expect(await screen.findByTestId('offer-result')).toHaveAttribute('data-status', 'ACTIVE');
    expect(bffCalls.some((c) => c.startsWith('POST') && c.endsWith('/v1/seller/offers'))).toBe(true);
    expect(uploads()).toEqual([]);

    // The creator keeps the page; the new seller has an offer on it; nobody uploaded a photo for it.
    const page = getSellerDb().matching?.pages.find((p) => p.title === 'Things Fall Apart, paperback');
    expect(page?.ownerOrg).toBe(SELLER_ORG);
    expect(getSellerDb().matching?.offers.some((o) => o.orgId === MOCK_FOOD_ORG && o.atin === page?.children[0]?.atin)).toBe(true);
  });

  it('keeps the barcode check digit honest: a made-up barcode with a wrong last digit is not a page', () => {
    expect(gtinWith('978030640615')).toBe('9780306406157');
  });
});
