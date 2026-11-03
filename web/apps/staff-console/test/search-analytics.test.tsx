import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { addDays, customProblem, presetRange } from '@/components/search/filters';
import { SearchAnalyticsView } from '@/components/search/SearchAnalyticsView';
import { download } from '@/lib/export/table';
import { count, share } from '@/lib/format';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

vi.mock('@/lib/export/table', async (importOriginal) => ({ ...(await importOriginal<Record<string, unknown>>()), download: vi.fn() }));

const RANGE = '?period=custom&from=2026-10-28&to=2026-11-03';

const readBlob = (blob: Blob) =>
  new Promise<string>((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result));
    r.onerror = () => reject(r.error);
    r.readAsText(blob);
  });

/** Records the staff requests the page makes. */
function recordRequests() {
  const urls: URL[] = [];
  interceptStaff(async (req, next) => {
    urls.push(new URL(req.url));
    return next(req);
  });
  return urls;
}

const rowsOf = (table: HTMLElement) => within(table).getAllByRole('row').slice(1);

beforeEach(() => {
  resetStaffDb(new Date('2026-11-03T10:00:00Z'));
  asRoles(null);
  interceptStaff(null);
  vi.mocked(download).mockClear();
  nav.set(`/search/analytics${RANGE}`);
});

describe('date presets', () => {
  it('end today and count days inclusively', () => {
    expect(presetRange('7', '2026-11-03')).toEqual({ from: '2026-10-28', to: '2026-11-03' });
    expect(presetRange('30', '2026-11-03').from).toBe('2026-10-05');
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
  });

  it('checks a custom range', () => {
    expect(customProblem('2026-10-01', '2026-10-31')).toBeNull();
    expect(customProblem('', '2026-10-31')).toBe('format');
    expect(customProblem('2026-11-02', '2026-11-01')).toBe('order');
    expect(customProblem('2025-01-01', '2026-11-01')).toBe('long');
  });

  it('formats counts and shares for the active locale', () => {
    expect(count(12345)).toBe('12,345');
    expect(share(0.1234)).toBe('12.3%');
    expect(share(0.0001)).toBe('<0.1%');
    expect(share(0)).toBe('0%');
  });
});

describe('Search analytics page', () => {
  it('shows the top queries of a market by name, with a plain explainer and no claim of exactness', async () => {
    await renderScreen(<SearchAnalyticsView />);
    const table = await screen.findByRole('table', { name: 'Top queries' });
    const rows = await waitFor(() => {
      const r = rowsOf(table);
      expect(r.length).toBeGreaterThan(5);
      return r;
    });
    expect(within(rows[0] as HTMLElement).getByText('shea butter')).toBeInTheDocument();
    expect(within(rows[1] as HTMLElement).getByText('kente')).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText('gari')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Market' })).toHaveDisplayValue('United States');
    expect(screen.getByText(/What shoppers search for most/)).toBeInTheDocument();
    expect(screen.getByText(/can be slightly off/)).toBeInTheDocument();
    expect(screen.getByTestId('search-totals')).toHaveTextContent(/searches · .* found nothing/);
    // No ids anywhere: the table shows queries and numbers only.
    expect(screen.queryByText(/search-analytics|ruleId/i)).not.toBeInTheDocument();
  });

  it('searches and sorts on the BFF', async () => {
    const urls = recordRequests();
    const user = userEvent.setup();
    await renderScreen(<SearchAnalyticsView />);
    await screen.findByRole('table', { name: 'Top queries' });
    await user.type(screen.getByRole('searchbox', { name: /Search top queries/i }), 'agbada');
    await waitFor(() => expect(rowsOf(screen.getByRole('table', { name: 'Top queries' }))).toHaveLength(3));
    const last = urls.filter((u) => u.pathname.endsWith('/analytics/top-queries')).at(-1);
    expect(last?.searchParams.get('q')).toBe('agbada');
    expect(last?.searchParams.get('market')).toBe('us');
    expect(last?.searchParams.get('from')).toBe('2026-10-28');
    expect(last?.searchParams.get('to')).toBe('2026-11-03');
    await user.click(screen.getByRole('button', { name: /^Query/ }));
    await waitFor(() => expect(urls.filter((u) => u.searchParams.get('sort') === 'query,asc').length).toBeGreaterThan(0));
  });

  it('lists queries that found nothing under No results', async () => {
    const user = userEvent.setup();
    await renderScreen(<SearchAnalyticsView />);
    await user.click(await screen.findByRole('tab', { name: 'No results' }));
    expect(screen.getByText(/Queries with no results are what shoppers wanted and did not find/)).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'No results' });
    await waitFor(() => expect(within(table).getByText('agbada kids')).toBeInTheDocument());
    expect(within(table).getByText('suya spise')).toBeInTheDocument();
    expect(within(table).queryByText('shea butter')).not.toBeInTheDocument();
    expect(nav.search).toContain('tab=zero');
  });

  it('shows click-through by day with a trend', async () => {
    const user = userEvent.setup();
    await renderScreen(<SearchAnalyticsView />);
    await user.click(await screen.findByRole('tab', { name: 'Click-through' }));
    const table = await screen.findByRole('table', { name: 'Click-through' });
    await waitFor(() => expect(rowsOf(table)).toHaveLength(7));
    expect(within(rowsOf(table)[0] as HTMLElement).getByText(/Nov 3, 2026/)).toBeInTheDocument();
    const trend = screen.getByTestId('search-trend');
    expect(within(trend).getAllByRole('img').map((i) => i.getAttribute('aria-label'))).toEqual([
      expect.stringMatching(/^Click-through from .* to /),
      expect.stringMatching(/^No-result rate from .* to /)
    ]);
  });

  it('changes the period and the custom days, and keeps the choices in the address', async () => {
    const urls = recordRequests();
    const user = userEvent.setup();
    nav.set('/search/analytics');
    await renderScreen(<SearchAnalyticsView />);
    await screen.findByRole('table', { name: 'Top queries' });
    expect(screen.getByRole('button', { name: 'Last 30 days' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(screen.getByRole('button', { name: 'Last 7 days' }));
    expect(nav.search).toContain('period=7');
    const seven = presetRange('7');
    await waitFor(() => expect(urls.some((u) => u.pathname.endsWith('/top-queries') && u.searchParams.get('from') === seven.from && u.searchParams.get('to') === seven.to)).toBe(true));

    await user.click(screen.getByRole('button', { name: 'Custom' }));
    const from = await screen.findByLabelText('From');
    const to = screen.getByLabelText('To');
    fireEvent.change(from, { target: { value: '2026-10-01' } });
    fireEvent.change(to, { target: { value: '2026-10-07' } });
    await waitFor(() => expect(urls.some((u) => u.pathname.endsWith('/top-queries') && u.searchParams.get('from') === '2026-10-01' && u.searchParams.get('to') === '2026-10-07')).toBe(true));

    fireEvent.change(from, { target: { value: '2026-10-09' } });
    expect(await screen.findByText('The first day must not be after the last day.')).toBeInTheDocument();
  });

  it('exports the current tab as CSV', async () => {
    const urls = recordRequests();
    const user = userEvent.setup();
    await renderScreen(<SearchAnalyticsView />);
    await screen.findByRole('table', { name: 'Top queries' });
    await user.click(screen.getByRole('button', { name: 'Export CSV' }));
    await waitFor(() => expect(download).toHaveBeenCalledTimes(1));
    const [blob, fileName] = vi.mocked(download).mock.calls[0] as [Blob, string];
    expect(fileName).toBe('search-top-queries-2026-10-28_2026-11-03.csv');
    const csv = await readBlob(blob);
    expect(csv).toContain('Query,Searches,No-result searches,No-result rate (%),Clicks,Click-through (%)');
    expect(csv).toMatch(/\r\nshea butter,\d+,\d+,[\d.]+,\d+,[\d.]+/);
    expect(urls.some((u) => u.pathname.endsWith('/top-queries') && u.searchParams.get('size') === '500')).toBe(true);

    await user.click(screen.getByRole('tab', { name: 'Click-through' }));
    await screen.findByRole('table', { name: 'Click-through' });
    await user.click(screen.getByRole('button', { name: 'Export CSV' }));
    await waitFor(() => expect(download).toHaveBeenCalledTimes(2));
    expect(vi.mocked(download).mock.calls[1]?.[1]).toBe('search-click-through-2026-10-28_2026-11-03.csv');
    await user.click(screen.getByRole('tab', { name: 'Synonym rules' }));
    expect(screen.queryByRole('button', { name: 'Export CSV' })).not.toBeInTheDocument();
  });

  it('adds a synonym for a query that found nothing', async () => {
    const user = userEvent.setup();
    nav.set(`/search/analytics${RANGE}&tab=zero`);
    await renderScreen(<SearchAnalyticsView />);
    await user.click(await screen.findByRole('button', { name: 'Add a synonym for agbada kids' }));

    const sheet = await screen.findByRole('dialog', { name: 'Add a synonym' });
    expect(within(sheet).getByLabelText('What shoppers typed')).toHaveValue('agbada kids');
    expect(within(sheet).getByLabelText('What shoppers typed')).toHaveAttribute('readonly');
    const save = within(sheet).getByRole('button', { name: 'Save synonym' });
    expect(save).toBeDisabled(); // nothing to save yet

    const words = within(sheet).getByLabelText('Words it should match');
    await user.type(words, '   ');
    expect(save).toBeDisabled();
    // Search's own validation comes back on the field.
    await user.type(words, 'x'.repeat(61));
    await user.click(save);
    expect(await within(sheet).findByText('Each word or phrase is at most 60 characters.')).toBeInTheDocument();

    await user.clear(words);
    await user.type(words, 'grand boubou kids, bubu');
    expect(screen.getByTestId('synonym-preview')).toHaveTextContent('agbada kids = grand boubou kids = bubu');
    await user.click(save);
    expect(await screen.findByText('Saved. Search now treats agbada kids = grand boubou kids = bubu as the same thing.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Add a synonym' })).not.toBeInTheDocument());

    await user.click(screen.getByRole('tab', { name: 'Synonym rules' }));
    const rules = await screen.findAllByTestId('synonym-rule');
    expect(rules.some((r) => r.textContent?.startsWith('agbada kids=grand boubou kids=bubu'))).toBe(true);
  });

  it('deletes a synonym rule after confirming', async () => {
    const user = userEvent.setup();
    nav.set(`/search/analytics${RANGE}&tab=rules`);
    await renderScreen(<SearchAnalyticsView />);
    expect(screen.queryByRole('combobox', { name: 'Market' })).not.toBeInTheDocument(); // rules are for every market
    await user.click(await screen.findByRole('button', { name: 'Delete the synonym gari = garri = cassava flakes' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete this synonym?' });
    expect(within(dialog).getByText(/Search will stop treating gari = garri = cassava flakes as the same thing/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('Synonym deleted.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('cassava flakes')).not.toBeInTheDocument());
  });

  it('is read-only without search:synonyms.edit', async () => {
    asRoles('platform.marketing');
    const user = userEvent.setup();
    nav.set(`/search/analytics${RANGE}&tab=zero`);
    await renderScreen(<SearchAnalyticsView />);
    const table = await screen.findByRole('table', { name: 'No results' });
    await waitFor(() => expect(within(table).getByText('agbada kids')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /Add a synonym for/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Action' })).not.toBeInTheDocument();
    expect(screen.getByText(/can view search analytics but not change it/)).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Synonym rules' }));
    expect(await screen.findAllByTestId('synonym-rule')).not.toHaveLength(0);
    expect(screen.queryByRole('button', { name: /Delete the synonym/ })).not.toBeInTheDocument();
    expect(screen.getByText('You can see the rules but not change them.')).toBeInTheDocument();
  });

  it('is closed to roles without search access', async () => {
    asRoles('platform.customer_support');
    await renderScreen(<SearchAnalyticsView />);
    expect(await screen.findByText('You don’t have access to this page')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});

describe('sidebar entry', () => {
  it('is offered with search permissions only', async () => {
    const { mockApi } = await import('./mock-api');
    const keys = async (roles: string | null) => {
      asRoles(roles);
      return (await mockApi().getSession()).navGroups.find((g) => g.key === 'commerce')?.modules.map((m) => m.key) ?? [];
    };
    expect(await keys('platform.catalog_manager')).toContain('search');
    expect(await keys('platform.marketing')).not.toContain('orders');
    expect(await keys('platform.customer_support')).not.toContain('search');
    asRoles('platform.product_owner');
    const modules = (await mockApi().getSession()).navGroups.flatMap((g) => g.modules);
    expect(modules.find((m) => m.key === 'search')).toMatchObject({ label: 'Search analytics', level: 'VIEW' });
  });
});
