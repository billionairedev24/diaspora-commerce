import { getStaffDb, resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { areaFromKey, strandedTargets, switchStateOf } from '@afrotransact/api-client/staff-preorders';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { PreordersView } from '@/components/preorders/PreordersView';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const windowOf = (id: string) => getStaffDb().preorders.windows.find((w) => w.id === id);

describe('Each pre-order has its own on/off switch (master AND window AND area)', () => {
  it('reads the window switch first; the master switch only holds windows that are on', () => {
    const open = { draft: false, active: true, status: 'OPEN' as const };
    expect(switchStateOf(open, true, true)).toBe('ON');
    expect(switchStateOf({ ...open, active: false, status: 'PAUSED' }, true, true)).toBe('PAUSED');
    expect(switchStateOf({ ...open, active: false, status: 'PAUSED' }, false, true)).toBe('PAUSED');
    expect(switchStateOf(open, false, true)).toBe('GLOBAL_OFF');
    expect(switchStateOf(open, true, false)).toBe('AREA_OFF');
    expect(switchStateOf({ ...open, draft: true, status: 'DRAFT' }, true, true)).toBe('NOT_LIVE');
    expect(switchStateOf({ ...open, status: 'DELIVERED' }, true, true)).toBe('NOT_LIVE');
  });

  it('refuses to strand a live pre-order: removing its city strands it, switching off or Every city does not', () => {
    const before = [areaFromKey('US/TX/Lubbock'), areaFromKey('US/TX/El Paso')];
    expect(strandedTargets(before, [areaFromKey('US/TX/El Paso')], ['US/TX/Lubbock'])).toEqual(['US/TX/Lubbock']);
    expect(strandedTargets(before, [areaFromKey('US/TX/Lubbock', false), areaFromKey('US/TX/El Paso')], ['US/TX/Lubbock'])).toEqual([]);
    expect(strandedTargets(before, [areaFromKey('US/TX')], ['US/TX/Lubbock'])).toEqual([]);
  });
});

describe('Pre-orders list and detail switches', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
    asRoles(null);
  });

  it('switches one pre-order off from its row; the others and the master switch stay on', async () => {
    const user = userEvent.setup();
    nav.set('/preorders');
    await renderScreen(<PreordersView screen={{ kind: 'list' }} />);
    const table = await screen.findByRole('table', { name: 'Pre-orders' });
    const row = (name: string) => within(table).getByRole('link', { name }).closest('tr') as HTMLElement;
    await waitFor(() => expect(row('Lubbock Pre-order #7')).toBeInTheDocument());
    await user.click(within(row('Lubbock Pre-order #7')).getByRole('switch', { name: 'Switch Lubbock Pre-order #7 on or off' }));

    await waitFor(() => expect(within(row('Lubbock Pre-order #7')).getByTestId('window-switch')).toHaveAttribute('data-state', 'PAUSED'));
    expect(row('Lubbock Pre-order #7')).toHaveTextContent('Paused');
    expect(within(row('El Paso Pre-order #3')).getByTestId('window-switch')).toHaveAttribute('data-state', 'ON');
    expect(within(row('El Paso Pre-order #3')).getByRole('switch')).toHaveAttribute('aria-checked', 'true');
    expect(windowOf('lbk-7')?.active).toBe(false);
    expect(windowOf('elp-3')?.active).toBe(true);
    expect(getStaffDb().preorders.settings.enabled).toBe(true);
    expect(screen.getByRole('button', { name: /Pre-order on/ })).toHaveAttribute('aria-pressed', 'true');
  });

  it('shows "Off (global switch)" for windows the master switch holds, distinct from a paused window, and never rewrites them', async () => {
    const user = userEvent.setup();
    windowOf('lbk-7')!.active = false;
    nav.set('/preorders');
    await renderScreen(<PreordersView screen={{ kind: 'list' }} />);
    const table = await screen.findByRole('table', { name: 'Pre-orders' });
    const row = (name: string) => within(table).getByRole('link', { name }).closest('tr') as HTMLElement;
    await user.click(await screen.findByRole('button', { name: /Pre-order on/ }));
    await waitFor(() => expect(row('El Paso Pre-order #3')).toHaveTextContent('Off (global switch)'));
    expect(within(row('Lubbock Pre-order #7')).getByTestId('window-switch')).toHaveAttribute('data-state', 'PAUSED');
    expect(row('Lubbock Pre-order #7')).not.toHaveTextContent('global switch');
    expect(windowOf('elp-3')?.active).toBe(true);
    await user.click(screen.getByRole('button', { name: /Pre-order off/ }));
    await waitFor(() => expect(within(row('El Paso Pre-order #3')).getByTestId('window-switch')).toHaveAttribute('data-state', 'ON'));
    expect(within(row('Lubbock Pre-order #7')).getByTestId('window-switch')).toHaveAttribute('data-state', 'PAUSED');
  });

  it('switches selected pre-orders off in bulk and skips drafts', async () => {
    const user = userEvent.setup();
    nav.set('/preorders');
    await renderScreen(<PreordersView screen={{ kind: 'list' }} />);
    const table = await screen.findByRole('table', { name: 'Pre-orders' });
    const row = (name: string) => within(table).getByRole('link', { name }).closest('tr') as HTMLElement;
    await waitFor(() => expect(row('El Paso Pre-order #3')).toBeInTheDocument());
    for (const name of ['Lubbock Pre-order #7', 'El Paso Pre-order #3', 'Midland–Odessa Pre-order #2']) await user.click(within(row(name)).getByRole('checkbox'));
    await user.click(within(screen.getByRole('region', { name: 'Selection' })).getByRole('button', { name: 'Switch off' }));
    await waitFor(() => expect(windowOf('elp-3')?.active).toBe(false));
    expect(windowOf('lbk-7')?.active).toBe(false);
    expect(windowOf('tor-1')?.active).toBe(true);
    expect(windowOf('mid-2')?.draft).toBe(true);
  });

  it('pauses and resumes from the detail page with its own switch', async () => {
    const user = userEvent.setup();
    nav.set('/preorders/lbk-7');
    await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'lbk-7' }} />);
    await user.click(await screen.findByRole('switch', { name: 'Switch Lubbock Pre-order #7 on or off' }));
    await waitFor(() => expect(screen.getByTestId('detail-status')).toHaveTextContent('Paused'));
    await user.click(screen.getByRole('button', { name: 'Resume' }));
    await waitFor(() => expect(screen.getByTestId('detail-status')).toHaveTextContent('Open'));
    expect(windowOf('lbk-7')?.active).toBe(true);
  });

  it('cannot switch as customer service', async () => {
    asRoles('platform.customer_support');
    nav.set('/preorders');
    await renderScreen(<PreordersView screen={{ kind: 'list' }} />);
    const table = await screen.findByRole('table', { name: 'Pre-orders' });
    await waitFor(() => expect(within(table).getAllByRole('switch').length).toBeGreaterThan(0));
    within(table).getAllByRole('switch').forEach((s) => expect(s).toBeDisabled());
  });
});

describe('Editing a state’s cities in place', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
    asRoles(null);
  });

  it('adds cities, switches one off, and keeps cities a live pre-order sells to', async () => {
    const user = userEvent.setup();
    nav.set('/preorders/settings');
    await renderScreen(<PreordersView screen={{ kind: 'settings' }} />);
    const state = await screen.findByRole('combobox', { name: 'State to edit' });
    await within(state).findByRole('option', { name: 'Texas' });
    await user.selectOptions(state, 'TX');
    await user.click(screen.getByRole('button', { name: 'Edit cities' }));
    const editor = await screen.findByTestId('region-editor');
    await waitFor(() => expect(within(editor).getAllByTestId('region-city').length).toBeGreaterThan(3));
    expect(within(editor).getByRole('button', { name: 'Remove Lubbock' })).toBeDisabled();
    expect(within(editor).getByText(/Lubbock Pre-order #7.*sell/)).toBeInTheDocument();

    await user.type(within(editor).getByRole('textbox', { name: 'Search cities in Texas' }), 'hou');
    await user.click(await within(editor).findByRole('checkbox', { name: 'Houston' }));
    await user.click(within(editor).getByRole('button', { name: /Add 1 selected/ }));
    await user.click(within(editor).getByRole('switch', { name: 'Switch Odessa on or off' }));
    await user.click(within(editor).getByRole('button', { name: 'Remove Amarillo' }));
    await user.click(within(editor).getByRole('button', { name: 'Save cities' }));

    await waitFor(() => expect(getStaffDb().preorders.areas.find((a) => a.key === 'US/TX/Houston')?.on).toBe(true));
    const areas = getStaffDb().preorders.areas;
    expect(areas.find((a) => a.key === 'US/TX/Odessa')?.on).toBe(false);
    expect(areas.some((a) => a.key === 'US/TX/Lubbock')).toBe(true);
    expect(areas.some((a) => a.key === 'CA/ON/Toronto')).toBe(true);
  });
});
