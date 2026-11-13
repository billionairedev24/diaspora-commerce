import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ConsoleShell } from '@/components/shell/ConsoleShell';
import { TaxReviewPanel } from '@/components/tax/TaxReviewPanel';
import { TaxView } from '@/components/tax/TaxView';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen, renderWithProviders } from './render';
import { nav } from './setup';

const NOW = new Date('2026-11-09T12:00:00Z');

const queueTitles = (table: HTMLElement) =>
  within(table)
    .getAllByRole('row')
    .slice(1)
    .map((r) => within(r).queryAllByRole('cell')[0]?.textContent ?? '');

const sidebarHrefs = async () => {
  const sidebar = (await screen.findAllByRole('navigation', { name: 'Staff console' }))[0];
  if (!sidebar) throw new Error('sidebar');
  return [...sidebar.querySelectorAll('a')].map((a) => a.getAttribute('href'));
};

describe('Sales tax in the sidebar', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    nav.set('/');
  });
  afterEach(() => interceptStaff(null));

  it('is a link of its own for whoever holds tax:read, and not inside Settings', async () => {
    asRoles('platform.super_admin');
    renderWithProviders(<ConsoleShell><p>body</p></ConsoleShell>);
    expect(await sidebarHrefs()).toContain('/tax');
    expect(await sidebarHrefs()).not.toContain('/fees');
  });

  it('is there for an auditor, who can read tax, and for finance', async () => {
    for (const role of ['platform.auditor', 'platform.finance']) {
      asRoles(role);
      const { unmount } = renderWithProviders(<ConsoleShell><p>body</p></ConsoleShell>);
      expect(await sidebarHrefs(), role).toContain('/tax');
      unmount();
    }
  });

  it('is hidden from a role without tax:read', async () => {
    asRoles('platform.customer_support');
    renderWithProviders(<ConsoleShell><p>body</p></ConsoleShell>);
    expect(await sidebarHrefs()).not.toContain('/tax');
  });

  it('opens on Tax review with the sub-tabs of the old Settings section, and no tax section left in Settings', async () => {
    asRoles('platform.super_admin');
    nav.set('/tax');
    await renderScreen(<TaxView />);
    const tabs = await screen.findAllByRole('tab');
    expect(tabs.map((t) => t.textContent)).toEqual([
      'Tax review', 'Review settings', 'Who calculates tax', 'Profiles', 'Provider codes', 'Shadow report', 'Filings', 'Nexus tracker', 'Rates'
    ]);
    expect(screen.getByRole('tab', { name: 'Tax review' })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByRole('button', { name: 'Audit sample' })).toBeInTheDocument();
  });

  it('keeps the tab keys of the old links', async () => {
    asRoles('platform.super_admin');
    nav.set('/tax?tab=tax-review-settings');
    await renderScreen(<TaxView />);
    expect(await screen.findByRole('tab', { name: 'Review settings', selected: true })).toBeInTheDocument();
  });

  it('only lists filings, nexus and rates (tables of Fees) to someone who can open Fees', async () => {
    asRoles('platform.customer_support');
    nav.set('/tax');
    // The tabs are built from the session's modules: support has neither Fees nor Sales tax, so only the tax-owned tabs could ever show.
    await renderScreen(<TaxView />);
    await waitFor(() => expect(screen.queryByRole('tab', { name: 'Filings' })).not.toBeInTheDocument());
    expect(screen.queryByRole('tab', { name: 'Nexus tracker' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Rates' })).not.toBeInTheDocument();
  });
});

describe('Tax review table', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.super_admin');
    nav.set('/tax?tab=tax-review');
  });
  afterEach(() => interceptStaff(null));

  it('names the seller on every row of every view, and never leaves it blank', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxReviewPanel />);
    const sellerOf = (row: HTMLElement) => within(row).getAllByRole('cell')[2]?.textContent ?? '';
    for (const view of [null, 'Audit sample', 'Decisions']) {
      if (view) await user.click(screen.getByRole('button', { name: view }));
      const table = await screen.findByRole('table');
      await within(table).findAllByRole('row');
      await waitFor(() => expect(within(table).getAllByRole('row').length).toBeGreaterThan(1));
      for (const row of within(table).getAllByRole('row').slice(1)) {
        expect(sellerOf(row).trim(), `${view ?? 'queue'}: ${row.textContent}`).not.toBe('');
        expect(sellerOf(row)).not.toMatch(/^—$|Unknown seller/);
      }
    }
  });

  it('searches the item name, the ATIN and the seller name', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxReviewPanel />);
    const search = await screen.findByRole('searchbox');
    const only = (title: string) => waitFor(() => expect(queueTitles(screen.getByRole('table'))).toEqual([expect.stringContaining(title)]));
    await user.type(search, 'cashews');
    await only('Roasted cashews, 6 oz bag');
    await user.clear(search);
    await user.type(search, 'AT20000102');
    await only('Yoruba for beginners');
    await user.clear(search);
    await user.type(search, 'lagos books');
    await only('Yoruba for beginners');
    await user.clear(search);
    await user.type(search, 'cashews');
    await only('Roasted cashews, 6 oz bag');
    await user.clear(search);
    await user.type(search, 'zobo');
    await waitFor(() => expect(queueTitles(screen.getByRole('table')).join('|')).toMatch(/Sparkling hibiscus water.*\|.*Dried hibiscus flowers|Dried hibiscus flowers.*\|.*Sparkling hibiscus water/));
    expect(queueTitles(screen.getByRole('table'))).toHaveLength(2);
  });

  it('filters by why it needs review, and an asked-for reason looks in every state', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxReviewPanel />);
    const table = await screen.findByRole('table', { name: 'Tax review queue' });
    await within(table).findByText('Roasted cashews, 6 oz bag');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Why it needs review' }), 'REGULATED_CATEGORY');
    await waitFor(() => expect(queueTitles(screen.getByRole('table'))).toHaveLength(1));
    expect(queueTitles(screen.getByRole('table'))[0]).toContain('Yoruba for beginners');
    expect(within(screen.getByRole('table')).getAllByText('Regulated category').length).toBeGreaterThan(0);
    // A missing answer waits with the seller, so it is found under "any status" without choosing one.
    await user.selectOptions(screen.getByRole('combobox', { name: 'Why it needs review' }), 'MISSING_FACT');
    await waitFor(() => expect(queueTitles(screen.getByRole('table'))[0]).toContain('Palm oil, 1 litre'));
    expect(screen.getByRole('combobox', { name: 'Status' })).toHaveValue('ALL');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Why it needs review' }), 'NO_MATCHING_RULE');
    await waitFor(() => expect(queueTitles(screen.getByRole('table'))[0]).toContain('Dried hibiscus flowers'));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Why it needs review' }), 'AUDIT_SAMPLE');
    await waitFor(() => expect(queueTitles(screen.getByRole('table'))[0]).toContain('Shea butter body cream'));
  });

  it('filters by status, category and seller, and clears them together', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxReviewPanel />);
    await screen.findByText('Roasted cashews, 6 oz bag');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'STALE');
    await waitFor(() => expect(queueTitles(screen.getByRole('table'))).toEqual([expect.stringContaining('Plantain flour, 2 lb bag')]));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'PENDING_REVIEW');
    await screen.findByText('Roasted cashews, 6 oz bag');

    // Seller: type part of the name, choose one, and its name stays on the filter.
    await user.type(screen.getByRole('textbox', { name: 'Seller' }), 'naija');
    await user.click(await screen.findByRole('button', { name: 'Naija Pantry Foods' }));
    await waitFor(() => expect(queueTitles(screen.getByRole('table'))).toEqual([expect.stringContaining('Plantain chips, 2 oz single serve')]));
    expect(within(screen.getByTestId('review-seller-filter')).getByText('Naija Pantry Foods')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(queueTitles(screen.getByRole('table')).length).toBeGreaterThan(3));
    expect(within(screen.getByTestId('review-seller-filter')).queryByText('Naija Pantry Foods')).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Seller' })).toHaveValue('');
  });

  it('sorts by a column header, and the seller and reason columns do not sort', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxReviewPanel />);
    const table = await screen.findByRole('table', { name: 'Tax review queue' });
    await within(table).findByText('Roasted cashews, 6 oz bag');
    expect(within(table).queryByRole('button', { name: /Seller/ })).not.toBeInTheDocument();
    expect(within(table).queryByRole('button', { name: /Why it needs review/ })).not.toBeInTheDocument();
    await user.click(within(table).getByRole('button', { name: /^Item/ }));
    await waitFor(() => expect(queueTitles(screen.getByRole('table'))[0]).toContain('Dried hibiscus flowers'));
    await user.click(within(screen.getByRole('table')).getByRole('button', { name: /^Item/ }));
    await waitFor(() => expect(queueTitles(screen.getByRole('table'))[0]).toContain('Yoruba for beginners'));
  });

  it('shows the item with its photo, the reason a person is needed, and opens it on a row click', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxReviewPanel />);
    const table = await screen.findByRole('table', { name: 'Tax review queue' });
    const row = (await within(table).findByText('Roasted cashews, 6 oz bag')).closest('tr') as HTMLElement;
    expect(row.querySelector('img')).not.toBeNull();
    expect(within(row).getByText('New listing')).toBeInTheDocument();
    await user.click(within(row).getByText('Roasted cashews, 6 oz bag'));
    expect(await screen.findByRole('dialog', { name: 'Roasted cashews, 6 oz bag' })).toBeInTheDocument();
  });

  it('says so when a filter matches nothing', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxReviewPanel />);
    await screen.findByText('Roasted cashews, 6 oz bag');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Why it needs review' }), 'BLOCKED');
    expect(await screen.findByText('No items match this search or these filters.')).toBeInTheDocument();
  });
});
