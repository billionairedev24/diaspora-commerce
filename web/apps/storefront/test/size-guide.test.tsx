import { atinOf } from '@afrotransact/api-client/mocks';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { ProductView } from '@/components/product/ProductView';
import { tEn } from '@/i18n/t';

import { installMockBff } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const DRESS = atinOf('p24');
const RICE = atinOf('p2');

beforeEach(() => window.localStorage.clear());

describe('variant chips and size guide on the product page (mock BFF)', () => {
  it('shows Size and Color chips for the variants, marks the current ones and links the others', async () => {
    renderWithProviders(<ProductView atin={DRESS} />);
    const size = await screen.findByRole('group', { name: 'Size' });
    const color = screen.getByRole('group', { name: 'Color' });
    const sizeChips = within(size).getAllByRole('listitem');
    expect(sizeChips.map((c) => c.textContent)).toEqual(['S', 'M', 'UK 12', 'Free size']);
    expect(within(size).getByText('S', { selector: '[aria-current="true"]' })).toBeInTheDocument();
    expect(within(color).getAllByRole('listitem')).toHaveLength(2);
    // Another size is a link to that variant's own page.
    const m = within(size).getByRole('link', { name: 'M' });
    expect(m.getAttribute('href')).toMatch(/^\/p\/AT/);
  });

  it('opens the size guide with the chart, highlights the shopper size and remembers the unit', async () => {
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<ProductView atin={DRESS} />);
    await user.click(await screen.findByRole('button', { name: tEn('sizeGuide.open') }));
    const sheet = await screen.findByRole('dialog', { name: tEn('sizeGuide.title') });
    const table = within(sheet).getByRole('table');
    const rows = within(table).getAllByRole('row');
    // Header + one row per size in the seller's chart; the shopper's own size is flagged.
    expect(rows.length).toBeGreaterThan(2);
    const current = rows.filter((r) => r.getAttribute('aria-current') === 'true');
    expect(current).toHaveLength(1);
    expect(current[0]).toHaveTextContent(tEn('sizeGuide.yourSize'));
    const before = within(table).getAllByRole('cell')[0]?.textContent;

    await user.click(within(sheet).getByRole('button', { name: tEn('sizeGuide.unit.IN') }));
    expect(within(sheet).getByRole('button', { name: tEn('sizeGuide.unit.IN') })).toHaveAttribute('aria-pressed', 'true');
    expect(within(table).getAllByRole('cell')[0]?.textContent).not.toBe(before);

    // The choice is kept for the next product page.
    unmount();
    renderWithProviders(<ProductView atin={DRESS} />);
    await user.click(await screen.findByRole('button', { name: tEn('sizeGuide.open') }));
    const again = await screen.findByRole('dialog', { name: tEn('sizeGuide.title') });
    expect(within(again).getByRole('button', { name: tEn('sizeGuide.unit.IN') })).toHaveAttribute('aria-pressed', 'true');
  });

  it('offers "Select" for the other sizes but not for the current one', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ProductView atin={DRESS} />);
    await user.click(await screen.findByRole('button', { name: tEn('sizeGuide.open') }));
    const sheet = await screen.findByRole('dialog', { name: tEn('sizeGuide.title') });
    expect(within(sheet).queryByRole('button', { name: tEn('sizeGuide.select', { size: 'S' }) })).not.toBeInTheDocument();
    expect(within(sheet).getByRole('button', { name: tEn('sizeGuide.select', { size: 'M' }) })).toBeInTheDocument();
  });

  it('keeps unsized products as they were: no chips and no size guide', async () => {
    renderWithProviders(<ProductView atin={RICE} />);
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: tEn('sizeGuide.open') })).not.toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Size' })).not.toBeInTheDocument();
  });
});
