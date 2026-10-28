import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { areaFromKey, areaOn, setAreasOn } from '@afrotransact/api-client/staff-preorders';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { NO_EDIT_TITLE, NO_SETTINGS_TITLE, preorderAccess } from '@/components/preorders/model';
import { PreordersView } from '@/components/preorders/PreordersView';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const perms = (...p: string[]) => (x: string) => p.includes(x);

describe('Pre-orders access (S274)', () => {
  it('maps the module level and preorders:* permissions to the design roles', () => {
    expect(preorderAccess('FULL', perms('preorders:manage', 'preorders:export', 'preorders:late-add'))).toMatchObject({ role: 'manager', canEdit: true, canSettings: true, canExport: true, canLate: true });
    expect(preorderAccess('EDIT', perms('preorders:manage', 'preorders:export', 'preorders:late-add'))).toMatchObject({ role: 'manager', canEdit: true, canSettings: false });
    expect(preorderAccess('APPROVE', perms('preorders:late-add'))).toMatchObject({ role: 'cs', canEdit: false, canExport: false, canLate: true });
    expect(preorderAccess('VIEW', perms('preorders:export'))).toMatchObject({ role: 'analyst', canEdit: false, canExport: true, canLate: false });
    expect(preorderAccess('VIEW', perms())).toMatchObject({ role: 'viewer', canEdit: false, canExport: false, canLate: false });
  });

  it('city rows override region rows and country / region switches are bulk', () => {
    const areas = [areaFromKey('US/TX'), areaFromKey('US/TX/Laredo', false)];
    expect(areaOn(areas, 'US', 'TX', 'Austin')).toBe(true);
    expect(areaOn(areas, 'US', 'TX', 'Laredo')).toBe(false);
    expect(areaOn(setAreasOn(areas, ['US'], true), 'US', 'TX', 'Laredo')).toBe(true);
    expect(areaOn(setAreasOn(areas, ['US/TX'], false), 'US', 'TX', 'Austin')).toBe(false);
  });
});

describe('Pre-orders back office (S274)', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
    asRoles(null);
  });

  it('lists windows with tab counts and the design pills', async () => {
    nav.set('/preorders');
    await renderScreen(<PreordersView screen={{ kind: 'list' }} />);
    const table = await screen.findByRole('table', { name: 'Pre-orders' });
    await waitFor(() => expect(within(table).getAllByTestId('preorder-row')).toHaveLength(9));
    await waitFor(() => expect(screen.getByTestId('tab-count-all')).toHaveTextContent('9'));
    expect(screen.getByTestId('tab-count-open')).toHaveTextContent('1');
    expect(screen.getByTestId('tab-count-upcoming')).toHaveTextContent('3');
    expect(screen.getByTestId('tab-count-decision')).toHaveTextContent('1');
    expect(screen.getByTestId('tab-count-closed')).toHaveTextContent('2');
    expect(screen.getByTestId('tab-count-draft')).toHaveTextContent('1');
    const row = (name: string) => within(table).getByRole('link', { name }).closest('tr') as HTMLElement;
    expect(row('Lubbock Pre-order #7')).toHaveTextContent('Open');
    expect(row('Amarillo Pre-order #4')).toHaveTextContent('Closed · delivering');
    expect(row('Amarillo Pre-order #5')).toHaveTextContent('Paused');
    expect(row('Laredo Pre-order #1')).toHaveTextContent('Area off');
    expect(row('Midland–Odessa Pre-order #2')).toHaveTextContent('Draft');
    expect(row('Lubbock Pre-order #6')).toHaveTextContent('Delivered');
    expect(row('Odessa Pre-order #1')).toHaveTextContent('Needs a decision');
    expect(row('Odessa Pre-order #1')).toHaveTextContent(/\d+ of 10/);
    expect(row('Lubbock Pre-order #7')).toHaveTextContent('140 of 100');
    expect(screen.getByTestId('decision-notice')).toHaveTextContent('1 pre-order closed below its minimum and needs a decision.');
    expect(screen.getByRole('tab', { name: /Scheduled/ })).toHaveAttribute('href', '/preorders?status=upcoming');
    expect(screen.getByRole('button', { name: 'Open a pre-order' })).toBeEnabled();
  });

  it('filters the list by tab on the server', async () => {
    nav.set('/preorders?status=closed');
    await renderScreen(<PreordersView screen={{ kind: 'list' }} />);
    await waitFor(() => expect(screen.getAllByTestId('preorder-row')).toHaveLength(2));
    expect(screen.getByRole('tab', { name: /Closed/ })).toHaveAttribute('aria-selected', 'true');
  });

  it('gates actions for customer service: late add only, no create, edit, export or settings', async () => {
    asRoles('platform.customer_support');
    nav.set('/preorders/ama-4');
    await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'ama-4' }} />);
    expect(await screen.findByRole('heading', { name: 'Amarillo Pre-order #4' })).toBeInTheDocument();
    expect(screen.getByTestId('role-note')).toHaveTextContent('Customer service: can view pre-orders and add late orders');
    expect(screen.getByRole('button', { name: 'Open a pre-order' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Open a pre-order' })).toHaveAttribute('title', NO_EDIT_TITLE);
    expect(screen.getByRole('button', { name: 'Late add' })).toBeEnabled();
    expect(screen.queryByRole('link', { name: 'Edit' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Pause' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Export orders/ })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /Pre-order on/ })).toBeDisabled());
    expect(screen.getByRole('button', { name: /Pre-order on/ })).toHaveAttribute('title', NO_SETTINGS_TITLE);
  });

  it('lets operations edit and export but not change settings; finance only exports', async () => {
    asRoles('platform.operations_manager');
    nav.set('/preorders/lbk-7');
    const { unmount } = await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'lbk-7' }} />);
    expect(await screen.findByRole('link', { name: 'Edit' })).toHaveAttribute('href', '/preorders/lbk-7/edit');
    expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Export orders/ })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /Pre-order on/ })).toBeDisabled());
    unmount();
    asRoles('platform.finance');
    await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'lbk-7' }} />);
    expect(await screen.findByRole('button', { name: /Export orders/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Late add' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Edit' })).not.toBeInTheDocument();
    expect(screen.getByTestId('role-note')).toHaveTextContent('Analyst: view only, plus exports and analytics.');
  });

  it('shows the overview KPIs and the orders tab with its one search', async () => {
    const user = userEvent.setup();
    nav.set('/preorders/lbk-7');
    await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'lbk-7' }} />);
    expect(await screen.findByTestId('kpi-Pre-orders')).toHaveTextContent(/\d+/);
    expect(screen.getByRole('tab', { name: 'Orders (142)' })).toHaveAttribute('href', '/preorders/lbk-7?tab=orders');
    nav.set('/preorders/lbk-7?tab=orders');
    await waitFor(() => expect(screen.getAllByTestId('order-row')).toHaveLength(12));
    expect(screen.getByText('Showing 1–12 of 142')).toBeInTheDocument();
    // One search over every column: the status is not one of delivery-runs' search fields.
    await user.type(screen.getByRole('searchbox', { name: 'Search pre-order orders' }), 'cancelled');
    await waitFor(() => expect(screen.getAllByTestId('order-row').every((r) => r.textContent?.includes('Cancelled'))).toBe(true));
  });

  it('counts the SMS with the tracked link and shows it as sent', async () => {
    const user = userEvent.setup();
    nav.set('/preorders/mid-2/edit');
    await renderScreen(<PreordersView screen={{ kind: 'edit', id: 'mid-2' }} />);
    expect(await screen.findByRole('heading', { name: 'Edit · Midland–Odessa Pre-order #2' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Comms/ }));
    const preview = await screen.findByTestId('sms-preview');
    await waitFor(() => expect(preview).toHaveTextContent(/\/pre-orders\?src=sms&po=[A-Z]{3}-\d+&cr=announce/));
    expect(preview).not.toHaveTextContent('{link}');
    const sent = preview.textContent ?? '';
    expect(screen.getByTestId('sms-count')).toHaveTextContent(`${sent.length}/160`);
    const box = screen.getByRole('textbox', { name: /SMS copy/ });
    await user.clear(box);
    await user.type(box, `${'x'.repeat(140)} {{link}`);
    expect(screen.getByTestId('sms-count').className).toContain('text-red-600');
  });

  it('blocks publishing until every check passes', async () => {
    const user = userEvent.setup();
    nav.set('/preorders/mid-2/edit');
    await renderScreen(<PreordersView screen={{ kind: 'edit', id: 'mid-2' }} />);
    expect(await screen.findByRole('heading', { name: 'Edit · Midland–Odessa Pre-order #2' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Review/ }));
    const checks = await screen.findByRole('list', { name: 'Checks' });
    expect(within(checks).getAllByRole('listitem').filter((li) => li.dataset.ok === 'false').map((li) => li.textContent)).toEqual(['Storefront hero uploaded (required)']);
    expect(screen.getByRole('button', { name: '1 check to fix' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: /Details/ }));
    await user.clear(screen.getByRole('textbox', { name: /^Name/ }));
    await user.clear(screen.getByRole('textbox', { name: /^Header ribbon text/ }));
    await user.click(screen.getByRole('button', { name: /Review/ }));
    expect(await screen.findByRole('button', { name: '3 checks to fix' })).toBeDisabled();
  });

  it('enables publish when the checks pass (a live window with a hero)', async () => {
    const user = userEvent.setup();
    nav.set('/preorders/lbk-7/edit');
    await renderScreen(<PreordersView screen={{ kind: 'edit', id: 'lbk-7' }} />);
    await screen.findByRole('heading', { name: /Edit · Lubbock Pre-order #7/ });
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: /Review/ }));
    expect(await screen.findByRole('button', { name: 'Publish pre-order' })).toBeEnabled();
    expect(screen.getByText('Door $7.99 · Pickup free')).toBeInTheDocument();
  });

  it('validates a late add before it can be saved', async () => {
    asRoles('platform.customer_support');
    const user = userEvent.setup();
    nav.set('/preorders/ama-4');
    await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'ama-4' }} />);
    await user.click(await screen.findByRole('button', { name: 'Late add' }));
    const dialog = await screen.findByRole('dialog', { name: 'Late add · Amarillo Pre-order #4' });
    const submit = () => within(dialog).getByRole('button', { name: /^Add \$[\d.]+ order$/ });
    expect(submit()).toBeDisabled();
    const people = within(dialog).getByRole('combobox', { name: 'Matching customers' });
    await waitFor(() => expect(within(people).getAllByRole('option').length).toBeGreaterThan(1));
    await user.selectOptions(people, 'c0');
    await user.selectOptions(within(dialog).getByRole('combobox', { name: /^Deliver to/ }), 'a1');
    await user.click(await within(dialog).findByRole('button', { name: /Ijebu Garri, 4 lb/ }));
    expect(submit()).toBeDisabled(); // phone not confirmed yet
    await user.click(within(dialog).getByRole('checkbox', { name: /Phone read back and confirmed/ }));
    expect(within(dialog).getByRole('button', { name: 'Add $20.98 order' })).toBeEnabled(); // $12.99 + $7.99 door
    await user.selectOptions(within(dialog).getByRole('combobox', { name: /^Deliver to/ }), 'new');
    expect(submit()).toBeDisabled(); // a new address must be verified first
    await user.type(within(dialog).getByRole('combobox', { name: /^Street/ }), '12 Elm St');
    await user.type(within(dialog).getByRole('textbox', { name: /^Postal code/ }), '79101');
    await user.click(within(dialog).getByRole('button', { name: 'Verify address' }));
    expect(await within(dialog).findByRole('button', { name: 'Verified ✓' })).toBeInTheDocument();
    await user.click(submit());
    expect(await screen.findByText(/Late order AMA-L90 added and logged/)).toBeInTheDocument();
    expect(nav.search).toBe('tab=orders');
  });

  it('points the fees to Delivery fees and keeps payment settings read-only below Full access', async () => {
    asRoles('platform.operations_manager');
    nav.set('/preorders/settings');
    await renderScreen(<PreordersView screen={{ kind: 'settings' }} />);
    const moved = await screen.findByTestId('fees-moved');
    expect(within(moved).getByRole('link', { name: 'Open Delivery fees' })).toHaveAttribute('href', '/settings?section=delivery-fees');
    expect(screen.queryByRole('textbox', { name: 'USD door delivery fee' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save payment & fees' })).not.toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Master switch' })).toBeDisabled();
  });
});
