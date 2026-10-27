import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { AdvertorialsPanel } from '@/components/runs/AdvertorialsPanel';
import { RunsView } from '@/components/runs/RunsView';

import { asRoles } from './mock-api';
import { renderScreen } from './render';

/* Featured products on an advertorial, orders credited to a run's advertorial, and the UTM visits and sign-ups of a town
   and of one of its runs (S262, S263), against the staff mocks. */

const NOW = new Date('2026-10-10T15:00:00Z');

beforeEach(() => {
  resetStaffDb(NOW);
  asRoles(null);
});
afterEach(() => cleanup());

describe('featured products on an advertorial', () => {
  it('shows the products a variant features, and finds others by name, up to four', async () => {
    const user = userEvent.setup();
    await renderScreen(<AdvertorialsPanel source="WINDOW" sourceId="lbk-7" />);
    expect(await screen.findByTestId('adv-featured-A')).toHaveTextContent('Features: Shea butter, raw, Garri, white');
    expect(screen.queryByTestId('adv-featured-B')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Write a variant' }));
    const dialog = await screen.findByRole('dialog', { name: 'Write a variant' });
    const featured = within(dialog).getByTestId('featured-products');
    expect(featured).toHaveTextContent('No product featured.');
    await user.type(within(featured).getByRole('textbox', { name: 'Find a product' }), 'mama');
    const hits = await within(featured).findByTestId('featured-hits');
    // Found by name or seller, never by code; no price is shown.
    const found = within(hits).getAllByRole('button').map((b) => b.textContent ?? '');
    expect(found).toHaveLength(2);
    expect(found[0]).toContain('Egusi, ground');
    expect(found[1]).toContain('Palm oil, 1 litre');
    expect(found.join(' ')).not.toMatch(/\$|AT[A-Z]{4}\d{4}/);
    await user.click(within(hits).getByRole('button', { name: /Egusi, ground/ }));
    expect(within(featured).getByRole('list', { name: 'Featured products' })).toHaveTextContent('Egusi, ground');
    for (const [typed, title] of [['shea', /Shea butter/], ['garri', /Garri/], ['jollof', /Jollof/]] as const) {
      await user.type(within(featured).getByRole('textbox', { name: 'Find a product' }), typed);
      await user.click(await within(featured).findByRole('button', { name: title }));
    }
    expect(within(featured).getByText('Feature at most 4 products.')).toBeInTheDocument();
    expect(within(featured).queryByRole('textbox', { name: 'Find a product' })).not.toBeInTheDocument();
    await user.click(within(featured).getByRole('button', { name: 'Remove Egusi, ground' }));
    expect(within(featured).getByRole('textbox', { name: 'Find a product' })).toBeInTheDocument();

    await user.type(within(dialog).getByRole('textbox', { name: 'Headline' }), 'Third idea');
    await user.type(within(dialog).getByRole('textbox', { name: 'Button label' }), 'Go');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByTestId('adv-featured-C')).toHaveTextContent('Features: Shea butter, raw, Garri, white, Jollof spice box');
  });

  it('keeps an edit without featured products from clearing them, and clears them when removed', async () => {
    const user = userEvent.setup();
    await renderScreen(<AdvertorialsPanel source="WINDOW" sourceId="lbk-7" />);
    await user.click(within(await screen.findByTestId('adv-A')).getByRole('button', { name: 'Edit' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Remove Garri, white' }));
    await user.click(within(dialog).getByRole('button', { name: 'Remove Shea butter, raw' }));
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.queryByTestId('adv-featured-A')).not.toBeInTheDocument();
  });

  it('shows the orders credited to a run advertorial, not "not tracked"', async () => {
    const runs = await import('@afrotransact/api-client/mocks/staff');
    expect(runs).toBeDefined();
    await renderScreen(<AdvertorialsPanel source="RUN" sourceId="7e5c2a6d-0000-4000-8000-000000000004" />);
    expect(await screen.findByTestId('adv-orders-A')).toHaveTextContent('6');
    expect(screen.getByTestId('adv-results-A')).not.toHaveTextContent('not tracked');
  });
});

describe('UTM attribution in the run screens', () => {
  it('shows visits and sign-ups per source for the town, and for the run picked in its history', async () => {
    const user = userEvent.setup();
    await renderScreen(<RunsView />);
    const town = await screen.findByTestId('location-attribution');
    expect(within(town).getByRole('heading', { name: 'Visits and sign-ups by source: Lubbock' })).toBeInTheDocument();
    const rows = within(town).getAllByRole('row');
    expect(rows.map((r) => r.textContent)).toEqual(
      expect.arrayContaining([expect.stringContaining('whatsapp'), expect.stringContaining('instagram'), expect.stringContaining('email')])
    );
    expect(within(town).getByTestId('attribution-total')).toHaveTextContent('245');
    expect(within(town).getByTestId('attribution-total')).toHaveTextContent('42');
    expect(screen.queryByTestId('run-attribution')).not.toBeInTheDocument();

    const firstRun = (await screen.findAllByTestId(/^run-row-/))[0] as HTMLElement;
    await user.click(within(firstRun).getByRole('button'));
    const run = await screen.findByTestId('run-attribution');
    expect(within(run).getByRole('heading', { name: 'Visits and sign-ups by source: this run' })).toBeInTheDocument();
    expect(within(run).getByTestId('attribution-total')).toHaveTextContent('102');
  });

  it('is readable by roles that can view pre-orders', async () => {
    asRoles('platform.finance');
    await renderScreen(<RunsView />);
    expect(await screen.findByTestId('location-attribution')).toBeInTheDocument();
  });
});
