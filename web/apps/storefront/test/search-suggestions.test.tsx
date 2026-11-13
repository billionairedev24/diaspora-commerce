import { type ProductSummary } from '@afrotransact/api-client';
import { locationForZip, suggest } from '@afrotransact/api-client/mocks';
import { http, HttpResponse, mockServer } from '@afrotransact/api-client/mocks/node';
import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { SearchBox } from '@/components/shell/SearchBox';

import { installMockBff } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

/** Types "rice" and returns the first product row, whose data is the mock BFF's first hit with the given fields forced. */
async function suggestionRow(overrides: Partial<ProductSummary>) {
  mockServer.use(
    http.get('*/v1/search/suggestions', () => {
      const loc = locationForZip('77002');
      if (!loc) throw new Error('no fixture location');
      const base = suggest('rice', loc, new Date());
      const first = base.products[0];
      if (!first) throw new Error('no fixture product');
      return HttpResponse.json({ ...base, phrases: [], products: [{ ...first, title: 'Basmati Rice', ...overrides }] });
    })
  );
  renderWithProviders(<SearchBox />);
  const box = screen.getByRole('combobox', { name: 'Search' });
  fireEvent.focus(box);
  fireEvent.change(box, { target: { value: 'rice' } });
  return screen.findByRole('option', { name: /Basmati Rice/ });
}

describe('search suggestion rows', () => {
  it('never mention shipping and never leave an empty fragment, even for a seller-fulfilled item with no city', async () => {
    const row = await suggestionRow({ brand: 'Tilda', department: 'Grocery', sellerCity: '', fulfilment: 'SELLER', inStock: true, reviewCount: 0 });
    expect(row.textContent).not.toMatch(/ships from/i);
    expect(row.textContent).not.toMatch(/fulfilled|delivery/i);
    expect(row.textContent).toContain('Tilda · Grocery');
    expect(row.textContent).not.toMatch(/·\s*·/);
  });

  it('never shows "0 reviews" or stars without reviews', async () => {
    const row = await suggestionRow({ brand: 'Tilda', department: 'Grocery', rating: 0, reviewCount: 0 });
    expect(row.textContent).not.toMatch(/0 (reviews|ratings)/i);
    expect(within(row).queryByRole('img', { name: /stars/ })).toBeNull();
  });

  it('shows the rating and count when the product has reviews', async () => {
    const row = await suggestionRow({ brand: 'Tilda', department: 'Grocery', rating: 4.5, reviewCount: 128 });
    expect(within(row).getByRole('img', { name: '4.5 out of 5 stars' })).toBeInTheDocument();
    expect(row.textContent).toContain('128 ratings');
  });

  it('skips an unknown brand and flags an out-of-stock product', async () => {
    const row = await suggestionRow({ brand: '', department: 'Grocery', inStock: false, reviewCount: 0 });
    expect(row.textContent).toContain('Grocery · Currently unavailable');
    expect(row.textContent).not.toMatch(/^\s*·|·\s*$/);
  });

  it('shows no availability text for an in-stock product', async () => {
    const row = await suggestionRow({ brand: 'Tilda', department: 'Grocery', inStock: true });
    expect(row.textContent).not.toMatch(/unavailable|out of stock/i);
  });
});
