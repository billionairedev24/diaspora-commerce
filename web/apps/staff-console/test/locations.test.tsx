import { ApiError } from '@afrotransact/api-client';
import { getStaffDb, resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { type StaffMarketsApi } from '@afrotransact/api-client/staff-markets';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationTree, type TreeOps } from '@/components/locations/LocationTree';
import {
  appendCities,
  countrySummary,
  DEFAULT_STATUSES,
  isVersionConflict,
  regionRemovalBlock,
  regionSummary,
  removalBlock,
  type TreeCountry,
  visible,
  withCityStatus
} from '@/components/locations/tree-model';
import { marketStatusOf, MarketsView, treeStatusOf } from '@/components/markets/MarketsView';
import { PreordersView } from '@/components/preorders/PreordersView';

import { asRoles } from './mock-api';
import { renderScreen, renderWithProviders } from './render';
import { nav } from './setup';

const city = (name: string, status: 'LIVE' | 'PLANNED' | 'OFF' = 'LIVE', dependents: string[] = []) => ({ name, status, dependents });

describe('location tree model', () => {
  it('shows only live and planned places unless switched-off ones are asked for', () => {
    const nodes = [city('Houston'), city('El Paso', 'PLANNED'), city('Laredo', 'OFF')];
    expect(visible(nodes, false).map((c) => c.name)).toEqual(['Houston', 'El Paso']);
    expect(visible(nodes, true)).toHaveLength(3);
  });

  it('appends cities to the existing list instead of replacing it', () => {
    const before = [city('Houston'), city('Dallas', 'PLANNED')];
    const after = appendCities(before, ['Katy', 'houston', ' Plano ', 'Katy'], 'PLANNED');
    expect(after.map((c) => c.name)).toEqual(['Houston', 'Dallas', 'Katy', 'Plano']);
    expect(after[0]?.status).toBe('LIVE'); // listed cities keep their status
    expect(withCityStatus(after, ['katy'], 'LIVE').find((c) => c.name === 'Katy')?.status).toBe('LIVE');
  });

  it('summarises states and countries with their counts', () => {
    const tx = { code: 'TX', name: 'Texas', status: 'LIVE' as const, everyCity: false, cities: [city('A'), city('B'), city('C'), city('D'), city('E'), city('F', 'PLANNED')], dependents: 0 };
    expect(regionSummary(tx)).toBe('6 cities · 5 live');
    expect(regionSummary({ ...tx, everyCity: true })).toBe('Every city · 6 listed · 5 live');
    expect(regionSummary({ ...tx, cities: [] })).toBe('No cities yet');
    const us: TreeCountry = { code: 'US', name: 'United States', status: 'LIVE', regionLabel: 'State', regions: [tx, { ...tx, code: 'OH', status: 'PLANNED', cities: [] }] };
    expect(countrySummary(us)).toBe('1 state live · 1 planned · 6 cities');
  });

  it('guards removals of places an open pre-order uses', () => {
    expect(removalBlock(city('Lubbock', 'LIVE', ['LBK-7']))).toMatch(/LBK-7 sells here.*Switch it off/);
    expect(removalBlock(city('Katy'))).toBeNull();
    expect(regionRemovalBlock({ code: 'TX', name: 'Texas', status: 'LIVE', everyCity: true, cities: [], dependents: 2 })).toMatch(/2 open pre-orders use Texas/);
    expect(isVersionConflict(new ApiError({ type: 'about:blank', title: 'Changed', status: 412 }))).toBe(true);
    expect(isVersionConflict(new ApiError({ type: 'about:blank', title: 'Conflict', status: 409 }))).toBe(false);
  });

  it('maps content country statuses onto the tree', () => {
    expect(treeStatusOf('COMING_SOON')).toBe('PLANNED');
    expect(treeStatusOf('PAUSED')).toBe('OFF');
    expect(marketStatusOf('OFF', 'LIVE')).toBe('PAUSED');
    expect(marketStatusOf('OFF', 'COMING_SOON')).toBe('PLANNED');
    expect(marketStatusOf('PLANNED', 'PLANNED')).toBe('COMING_SOON');
  });
});

const COUNTRIES: TreeCountry[] = [
  {
    code: 'US',
    name: 'United States',
    status: 'LIVE',
    regionLabel: 'State',
    regions: [
      { code: 'TX', name: 'Texas', status: 'LIVE', everyCity: false, cities: [city('Houston'), city('Lubbock', 'LIVE', ['LBK-7']), city('Laredo', 'OFF')], dependents: 1 },
      { code: 'OH', name: 'Ohio', status: 'PLANNED', everyCity: false, cities: [], dependents: 0 },
      { code: 'AK', name: 'Alaska', status: 'OFF', everyCity: false, cities: [], dependents: 0 }
    ]
  },
  { code: 'FR', name: 'France', status: 'OFF', regionLabel: 'Region', regions: [] }
];

function Harness({ ops, initial = COUNTRIES }: { ops: TreeOps; initial?: TreeCountry[] }) {
  const [open, setOpen] = useState(new Set(['US', 'US/TX']));
  const places = { places: vi.fn(async () => ({ items: [{ code: 'Katy', name: 'Katy' }, { code: 'Kyle', name: 'Kyle' }], label: 'City', total: 2 })) } as unknown as Pick<StaffMarketsApi, 'places'>;
  return (
    <LocationTree
      label="Locations"
      countries={initial}
      statuses={{ region: DEFAULT_STATUSES, city: DEFAULT_STATUSES }}
      newCityStatus="PLANNED"
      canEdit
      ops={ops}
      places={places}
      expanded={open}
      onExpand={(k, on) =>
        setOpen((cur) => {
          const n = new Set(cur);
          if (on) n.add(k);
          else n.delete(k);
          return n;
        })
      }
    />
  );
}

const fakeOps = (over: Partial<TreeOps> = {}): TreeOps => ({
  addRegion: vi.fn(async () => undefined),
  setRegionStatus: vi.fn(async () => undefined),
  addCities: vi.fn(async () => undefined),
  setCityStatus: vi.fn(async () => undefined),
  removeCity: vi.fn(async () => undefined),
  refresh: vi.fn(async () => undefined),
  ...over
});

describe('LocationTree', () => {
  it('lists only live and planned places by default, and switched-off ones on request', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness ops={fakeOps()} />);
    const tree = screen.getByRole('tree', { name: 'Locations' });
    expect(within(tree).getAllByTestId('tree-country')).toHaveLength(1);
    expect(within(tree).getAllByTestId('tree-region').map((r) => r.getAttribute('aria-label'))).toEqual(['Texas, Live, 3 cities · 2 live', 'Ohio, Planned, No cities yet']);
    expect(within(tree).getAllByTestId('tree-city')).toHaveLength(2); // Laredo is off
    await user.click(screen.getByRole('checkbox', { name: /Show switched-off/ }));
    expect(within(tree).getAllByTestId('tree-country')).toHaveLength(2);
    expect(within(tree).getAllByTestId('tree-city')).toHaveLength(3);
    expect(within(tree).getByRole('treeitem', { name: 'Alaska, Off, No cities yet' })).toBeInTheDocument();
  });

  it('switches a city in place (optimistic) and in bulk', async () => {
    const user = userEvent.setup();
    let release: () => void = () => undefined;
    const setCityStatus = vi.fn(() => new Promise<void>((r) => (release = r)));
    renderWithProviders(<Harness ops={fakeOps({ setCityStatus })} />);
    const houston = screen.getByRole('radiogroup', { name: 'Houston status' });
    await user.click(within(houston).getByRole('radio', { name: 'Planned' }));
    expect(setCityStatus).toHaveBeenCalledWith(expect.objectContaining({ code: 'US' }), expect.objectContaining({ code: 'TX' }), ['Houston'], 'PLANNED');
    expect(within(houston).getByRole('radio', { name: 'Planned' })).toHaveAttribute('aria-checked', 'true'); // before the server answers
    release();
    await user.click(screen.getByRole('checkbox', { name: 'Select all' }));
    await user.click(screen.getByRole('button', { name: 'Switch selected to Off' }));
    expect(setCityStatus).toHaveBeenLastCalledWith(expect.anything(), expect.anything(), ['Houston', 'Lubbock'], 'OFF');
  });

  it('adds cities inline and sends only the new ones (the list is appended)', async () => {
    const user = userEvent.setup();
    const ops = fakeOps({ addCities: vi.fn(() => new Promise<void>(() => undefined)) });
    renderWithProviders(<Harness ops={ops} />);
    const texas = screen.getByRole('treeitem', { name: /^Texas/ });
    await user.click(within(texas).getByRole('button', { name: 'Add cities' }));
    const adder = await screen.findByTestId('city-adder');
    await user.click(await within(adder).findByRole('checkbox', { name: 'Katy' }));
    await user.click(within(adder).getByRole('checkbox', { name: 'Kyle' }));
    await user.click(within(adder).getByRole('button', { name: 'Add 2 cities' }));
    expect(ops.addCities).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ code: 'TX' }), [
      { name: 'Katy', status: 'PLANNED' },
      { name: 'Kyle', status: 'PLANNED' }
    ]);
    expect(within(texas).getAllByTestId('tree-city').map((c) => c.getAttribute('aria-label'))).toEqual(['Houston, Live', 'Lubbock, Live', 'Katy, Planned', 'Kyle, Planned']);
  });

  it('refuses to remove a city an open pre-order sells to and offers switching it off', async () => {
    const user = userEvent.setup();
    const ops = fakeOps();
    renderWithProviders(<Harness ops={ops} />);
    await user.click(screen.getByRole('button', { name: 'Remove Lubbock' }));
    expect(ops.removeCity).not.toHaveBeenCalled();
    expect(await screen.findByText(/LBK-7 sells here/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Switch off' }));
    expect(ops.setCityStatus).toHaveBeenCalledWith(expect.anything(), expect.anything(), ['Lubbock'], 'OFF');
    await user.click(screen.getByRole('button', { name: 'Remove Houston' }));
    expect(ops.removeCity).toHaveBeenCalledWith(expect.anything(), expect.anything(), expect.objectContaining({ name: 'Houston' }));
    await user.click(await screen.findByRole('button', { name: 'Undo' }));
    expect(ops.addCities).toHaveBeenCalledWith(expect.anything(), expect.anything(), [{ name: 'Houston', status: 'LIVE' }]);
  });

  it('rolls back and reloads with a clear message on a version conflict (412)', async () => {
    const user = userEvent.setup();
    const conflict = new ApiError({ type: 'about:blank', title: 'Changed by someone else', status: 412, detail: 'Texas was changed by someone else.' });
    const ops = fakeOps({ setRegionStatus: vi.fn(async () => Promise.reject(conflict)) });
    renderWithProviders(<Harness ops={ops} />);
    const texas = screen.getByRole('radiogroup', { name: 'Texas status' });
    await user.click(within(texas).getByRole('radio', { name: 'Planned' }));
    expect(await screen.findByText(/Texas was changed by someone else\. We reloaded the latest/)).toBeInTheDocument();
    expect(ops.refresh).toHaveBeenCalled();
    expect(within(texas).getByRole('radio', { name: 'Live' })).toHaveAttribute('aria-checked', 'true');
  });

  it('moves through the tree with the arrow keys', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness ops={fakeOps()} />);
    const us = screen.getByRole('treeitem', { name: /^United States/ });
    expect(us).toHaveAttribute('aria-expanded', 'true');
    us.focus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('treeitem', { name: /^Texas/ })).toHaveFocus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('treeitem', { name: 'Houston, Live' })).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(screen.getByRole('treeitem', { name: /^Texas/ })).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(screen.getByRole('treeitem', { name: /^Texas/ })).toHaveAttribute('aria-expanded', 'false');
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('treeitem', { name: /^Texas/ })).toHaveAttribute('aria-expanded', 'true');
  });
});

describe('Settings → Markets & locations', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
    asRoles(null);
    nav.set('/markets');
  });

  it('expands Texas, adds two cities, switches one live, removes one with undo and adds a state', async () => {
    const user = userEvent.setup();
    await renderScreen(<MarketsView />);
    const tree = await screen.findByRole('tree', { name: 'Markets & locations' });
    await waitFor(() => expect(within(tree).getAllByTestId('tree-country').map((c) => c.getAttribute('aria-label'))).toEqual(['United States, Live', 'Canada, Coming soon']));
    await user.click(within(tree).getByRole('button', { name: 'Open United States' }));
    await user.click(await within(tree).findByRole('button', { name: 'Open Texas' }));
    const texas = () => within(tree).getByRole('treeitem', { name: /^Texas/ });
    await waitFor(() => expect(texas()).toHaveAttribute('aria-label', 'Texas, Live, 6 cities · 5 live'));

    await user.click(within(texas()).getByRole('button', { name: 'Add cities' }));
    const adder = await within(texas()).findByTestId('city-adder');
    await user.click(await within(adder).findByRole('checkbox', { name: 'Amarillo' }));
    await user.click(within(adder).getByRole('checkbox', { name: 'Midland' }));
    await user.click(within(adder).getByRole('button', { name: 'Add 2 cities' }));
    await waitFor(() => expect(texas()).toHaveAttribute('aria-label', 'Texas, Live, 8 cities · 5 live'));
    const tx = () => getStaffDb().markets.countries.find((c) => c.code === 'US')?.regions.find((r) => r.code === 'TX');
    expect(tx()?.cities.map((c) => c.name)).toEqual(['Houston', 'Dallas', 'Austin', 'San Antonio', 'Lubbock', 'El Paso', 'Amarillo', 'Midland']);

    await user.click(within(within(texas()).getByRole('radiogroup', { name: 'Amarillo status' })).getByRole('radio', { name: 'Live' }));
    await waitFor(() => expect(tx()?.cities.find((c) => c.name === 'Amarillo')?.status).toBe('LIVE'));

    await user.click(within(texas()).getByRole('button', { name: 'Remove Midland' }));
    await waitFor(() => expect(tx()?.cities.some((c) => c.name === 'Midland')).toBe(false));
    await user.click(await screen.findByRole('button', { name: 'Undo' }));
    await waitFor(() => expect(tx()?.cities.find((c) => c.name === 'Midland')?.status).toBe('PLANNED'));
    expect(tx()?.cities.map((c) => c.name)).toEqual(['Houston', 'Dallas', 'Austin', 'San Antonio', 'Lubbock', 'El Paso', 'Amarillo', 'Midland']);

    // Lubbock has an open pre-order: it can't be removed.
    await user.click(within(texas()).getByRole('button', { name: 'Remove Lubbock' }));
    expect(await screen.findByText(/Lubbock: LBK-7 sells here/)).toBeInTheDocument();
    expect(tx()?.cities.some((c) => c.name === 'Lubbock')).toBe(true);

    await user.click(within(tree).getByRole('button', { name: 'Add a state' }));
    const picker = await screen.findByTestId('place-picker');
    await user.type(within(picker).getByRole('combobox', { name: 'Add a state to United States' }), 'michi');
    await user.click(await within(picker).findByRole('button', { name: /Michigan/ }));
    await waitFor(() => expect(within(tree).getByRole('treeitem', { name: /^Michigan, Planned/ })).toBeInTheDocument());
    expect(await within(within(tree).getByRole('treeitem', { name: /^Michigan/ })).findByTestId('city-adder')).toBeInTheDocument(); // ready for its cities
    expect(getStaffDb().markets.countries.find((c) => c.code === 'US')?.regions.find((r) => r.code === 'MI')?.status).toBe('PLANNED');
    expect(screen.getByTestId('markets-publish')).toHaveTextContent('Unpublished changes: US');
  });

  it('is read-only without content:markets.edit', async () => {
    asRoles('platform.operations_manager');
    await renderScreen(<MarketsView />);
    const tree = await screen.findByRole('tree', { name: 'Markets & locations' });
    await waitFor(() => expect(within(tree).getByRole('radiogroup', { name: 'United States status' })).toBeInTheDocument());
    within(within(tree).getByRole('radiogroup', { name: 'United States status' })).getAllByRole('radio').forEach((r) => expect(r).toBeDisabled());
    expect(screen.queryByRole('button', { name: 'Add a country' })).not.toBeInTheDocument();
  });
});

describe('Pre-orders → Pre-order areas use the same tree', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
    asRoles(null);
    nav.set('/preorders/settings');
  });

  it('adds cities to Texas inline, switches one off and keeps Lubbock (an open pre-order sells there)', async () => {
    const user = userEvent.setup();
    await renderScreen(<PreordersView screen={{ kind: 'settings' }} />);
    const tree = await screen.findByRole('tree', { name: 'Pre-order areas' });
    expect(screen.getByTestId('area-count')).toHaveTextContent('8 on · 1 off');
    await user.click(await within(tree).findByRole('button', { name: 'Open United States' }));
    await user.click(await within(tree).findByRole('button', { name: 'Open Texas' }));
    const texas = () => within(tree).getByRole('treeitem', { name: /^Texas/ });
    await waitFor(() => expect(within(texas()).getAllByTestId('tree-city')).toHaveLength(5)); // Laredo is off: hidden
    await user.click(within(texas()).getByRole('button', { name: 'Add cities' }));
    const adder = await within(texas()).findByTestId('city-adder');
    await user.type(within(adder).getByRole('textbox'), 'hou');
    await user.click(await within(adder).findByRole('checkbox', { name: 'Houston' }));
    await user.click(within(adder).getByRole('button', { name: 'Add 1 city' }));
    await waitFor(() => expect(getStaffDb().preorders.areas.find((a) => a.key === 'US/TX/Houston')?.on).toBe(true));

    await user.click(within(within(texas()).getByRole('radiogroup', { name: 'Odessa status' })).getByRole('radio', { name: 'Off' }));
    await waitFor(() => expect(getStaffDb().preorders.areas.find((a) => a.key === 'US/TX/Odessa')?.on).toBe(false));
    await waitFor(() => expect(screen.getByTestId('area-count')).toHaveTextContent('8 on · 2 off'));

    await user.click(within(texas()).getByRole('button', { name: 'Remove Lubbock' }));
    expect(await screen.findByText(/Lubbock: Lubbock Pre-order #7 sells here/)).toBeInTheDocument();
    expect(getStaffDb().preorders.areas.some((a) => a.key === 'US/TX/Lubbock')).toBe(true);
    expect(getStaffDb().preorders.areas.some((a) => a.key === 'CA/ON/Toronto')).toBe(true);
  });
});
