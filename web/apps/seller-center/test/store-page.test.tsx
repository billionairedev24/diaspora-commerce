import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { contrastRatio } from '@afrotransact/tokens';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { beforeEach, describe, expect, it } from 'vitest';

import { StorePageView } from '@/components/store-page/StorePageView';
import { addFeatured, filterPreviewProducts, localProblems, moveFeatured, moveSection, toggleSection } from '@/lib/store-page';

import { asRole } from './mock-api';
import { renderScreen } from './render';

describe('store page helpers', () => {
  it('moves and toggles sections, and keeps at most eight featured products', () => {
    const s = [{ id: 'featured' as const, enabled: true }, { id: 'about' as const, enabled: true }];
    expect(moveSection(s, 'about', -1).map((x) => x.id)).toEqual(['about', 'featured']);
    expect(moveSection(s, 'featured', -1)).toBe(s);
    expect(toggleSection(s, 'about', false)[1]!.enabled).toBe(false);
    let f: string[] = [];
    for (let i = 0; i < 12; i++) f = addFeatured(f, `A${i}`);
    expect(f).toHaveLength(8);
    expect(addFeatured(f, 'A0')).toEqual(f);
    expect(moveFeatured(['a', 'b'], 'b', -1)).toEqual(['b', 'a']);
  });

  it('flags markup and bad links before saving', () => {
    const c = { ...JSON.parse('{"hero":{"tagline":"<script>x</script>"},"announcement":{"text":"","href":"javascript:alert(1)"},"about":{"heading":"","body":""},"policies":{"shipping":"","returns":""},"contact":{"hours":"","phone":"abc"},"social":[{"network":"x","url":"http://a.b"}]}') };
    expect(Object.keys(localProblems(c))).toEqual(expect.arrayContaining(['hero.tagline', 'announcement.href', 'contact.phone', 'social.0.url']));
  });

  it('the preview grid filters, sorts and searches locally', () => {
    const p = (atin: string, title: string, dept: string, price: number) => ({ atin, title, brand: '', department: dept, priceMinor: price, rating: 0, reviewCount: 0 }) as never;
    const all = [p('A', 'Soap', 'Care', 500), p('B', 'Brush', 'Tools', 3000), p('C', 'Salt', 'Care', 1500)];
    expect(filterPreviewProducts(all, { q: 'so', category: '', price: '', sort: 'relevance' })).toHaveLength(1);
    expect(filterPreviewProducts(all, { q: '', category: 'Care', price: '10-25', sort: 'relevance' }).map((x) => x.atin)).toEqual(['C']);
    expect(filterPreviewProducts(all, { q: '', category: '', price: '', sort: 'price_desc' }).map((x) => x.atin)).toEqual(['B', 'C', 'A']);
  });
});

describe('Settings > Store page', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('edits theme and featured products, saves a draft, publishes, then unpublishes, with history', async () => {
    const user = userEvent.setup();
    await renderScreen(<StorePageView />);
    expect(await screen.findByTestId('store-state')).toHaveTextContent('Not published');
    // the preview is the real storefront, from the draft being edited
    expect(await screen.findByTestId('storefront-root')).toBeInTheDocument();
    await user.click(screen.getByTestId('preset-midnight'));
    await waitFor(() => expect(screen.getByTestId('storefront-root')).toHaveAttribute('data-preset', 'midnight'));
    // an accent that cannot be read is turned into one that can
    fireEvent.change(screen.getByTestId('accent-input'), { target: { value: '#ffd400' } });
    const accent = screen.getByTestId('accent-value').textContent!;
    expect(contrastRatio(accent, '#1a1e26')).toBeGreaterThanOrEqual(4.5);
    await user.type(screen.getByLabelText('Tagline'), 'Pantry staples, delivered fresh');
    await user.type(screen.getByTestId('featured-search'), 'rice');
    const results = await screen.findByTestId('featured-results');
    await user.click((await within(results).findAllByRole('button', { name: 'Add' }))[0]!);
    expect(within(screen.getByTestId('featured-chosen')).getAllByRole('listitem')).toHaveLength(1);
    await user.click(screen.getByTestId('save-draft'));
    expect(await screen.findByText(/Your changes are saved/)).toBeInTheDocument();
    await user.click(screen.getByTestId('publish'));
    expect(await screen.findByText('Your store page is live.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('store-state')).toHaveTextContent('Published'));
    expect(screen.getByTestId('view-store')).toHaveAttribute('href', expect.stringMatching(/\/b\/ade-s-kitchen$/));
    await user.click(screen.getByTestId('unpublish'));
    await waitFor(() => expect(screen.getByTestId('store-state')).toHaveTextContent('Not published'));
    const audit = await screen.findByTestId('store-audit');
    expect(audit.textContent).toMatch(/Unpublished[\s\S]*Published[\s\S]*Saved a draft/);
  });

  it('shows the preview at phone width and never uses the word offer', async () => {
    const user = userEvent.setup();
    await renderScreen(<StorePageView />);
    await screen.findByTestId('store-editor');
    await user.click(screen.getByRole('radio', { name: 'Phone' }));
    expect(screen.getByTestId('store-preview-frame')).toHaveAttribute('data-width', 'mobile');
    expect(screen.getByTestId('store-editor').textContent).not.toMatch(/\boffer/i);
  });

  it('a member who may only look cannot save or publish', async () => {
    asRole('seller.catalog');
    await renderScreen(<StorePageView />);
    await screen.findByTestId('store-state').catch(() => undefined);
    expect(screen.queryByTestId('publish')).toBeNull();
  });

  it('has no serious axe violations', async () => {
    await renderScreen(<StorePageView />);
    await screen.findByTestId('storefront-root');
    const r = await axe.run(document.body, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } });
    expect(r.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.nodes[0]?.html.slice(0, 80)}`)).toEqual([]);
  });
});
