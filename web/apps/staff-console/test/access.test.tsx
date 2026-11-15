import { addMockPermission, getStaffDb, resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { beforeEach, describe, expect, it } from 'vitest';

import { AccessView } from '@/components/access/AccessView';
import { groupPermissions, moduleLabel, permissionLabel, sourceOf } from '@/lib/access';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const reasonText = 'Quarter close needs this';

async function openRole(user: ReturnType<typeof userEvent.setup>, name: string) {
  const table = await screen.findByRole('table', { name: 'Roles' });
  await user.click(await within(table).findByText(name));
  return screen.findByTestId('role-detail');
}

const sw = (scope: HTMLElement, label: string) => within(scope).getByRole('switch', { name: label });

describe('Roles and permissions', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/settings/access');
  });

  it('says how long a change takes to reach a sign-in, on every tab', async () => {
    await renderScreen(<AccessView />);
    expect(await screen.findByText(/within about 5 minutes/)).toBeInTheDocument();
  });

  it('lists the roles of a portal with their permission counts, and switches portal', async () => {
    const user = userEvent.setup();
    await renderScreen(<AccessView />);
    const table = await screen.findByRole('table', { name: 'Roles' });
    const finance = (await within(table).findByText('Finance')).closest('tr') as HTMLElement;
    expect(within(finance).getByText('Payouts, ledger, fees and refunds')).toBeInTheDocument();
    expect(within(finance).getByText('25')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Roles for'), 'seller');
    const sellers = await screen.findByRole('table', { name: 'Roles' });
    expect(await within(sellers).findByText('Owner')).toBeInTheDocument();
    expect(within(sellers).queryByText('Finance Manager')).not.toBeInTheDocument();
    expect(screen.getByText(/applies to every seller business/)).toBeInTheDocument();
  });

  it('opens a role: who has it by name, and its permissions grouped by module with plain labels', async () => {
    const user = userEvent.setup();
    await renderScreen(<AccessView />);
    const detail = await openRole(user, 'Finance');
    expect(within(detail).getByRole('heading', { name: 'Finance', level: 2 })).toBeInTheDocument();
    const members = await within(detail).findByTestId('role-members');
    expect(within(members).getByText('Musa Bello')).toBeInTheDocument();
    expect(within(members).queryByText(/stf-/)).not.toBeInTheDocument();
    const orders = within(detail).getByTestId('perm-group-orders');
    expect(within(orders).getByText('Refunds: issue')).toBeInTheDocument();
    expect(within(orders).getByText('Issue refunds')).toBeInTheDocument();
    expect(sw(detail, 'Refunds: issue for Finance')).toBeChecked();
    expect(sw(detail, 'Catalog: write for Finance')).not.toBeChecked();
    expect(within(orders).getByText(/of \d+ on/)).toBeInTheDocument();
  });

  it('turns a permission off and on with a confirmation and a required reason, and records it', async () => {
    const user = userEvent.setup();
    await renderScreen(<AccessView />);
    const detail = await openRole(user, 'Finance');
    await user.click(sw(detail, 'Refunds: issue for Finance'));
    const dialog = await screen.findByRole('dialog', { name: 'Remove “Refunds: issue” from Finance?' });
    expect(within(dialog).getByText(/loses this within about 5 minutes/)).toBeInTheDocument();
    const confirm = within(dialog).getByRole('button', { name: 'Remove permission' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/^Why/), 'ab');
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/^Why/), 'c quarter close');

    expect(confirm).toBeEnabled();
    await user.click(confirm);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(sw(screen.getByTestId('role-detail'), 'Refunds: issue for Finance')).not.toBeChecked());
    expect(await screen.findByText('“Refunds: issue” removed from Finance')).toBeInTheDocument();

    await user.click(sw(screen.getByTestId('role-detail'), 'Refunds: issue for Finance'));
    const again = await screen.findByRole('dialog', { name: 'Add “Refunds: issue” to Finance?' });
    // a reason belongs to one change: the second dialog starts empty
    expect(within(again).getByLabelText(/^Why/)).toHaveValue('');
    await user.type(within(again).getByLabelText(/^Why/), reasonText);
    await user.click(within(again).getByRole('button', { name: 'Add permission' }));
    await waitFor(() => expect(sw(screen.getByTestId('role-detail'), 'Refunds: issue for Finance')).toBeChecked());

    // both changes are in the change log with the reason, filtered to this role
    await user.click(screen.getByRole('button', { name: 'All roles' }));
    await user.click(await screen.findByRole('tab', { name: 'Change log' }));
    await user.selectOptions(await screen.findByLabelText('Role'), 'platform.finance');
    const log = await screen.findByRole('table', { name: 'Change log' });
    expect(await within(log).findByText(reasonText)).toBeInTheDocument();
    expect(within(log).getAllByText('Ifeoma Okafor').length).toBeGreaterThanOrEqual(2);
    expect(within(log).getByText('Role permission removed: Refunds: issue', { exact: false })).toBeInTheDocument();
  });

  it('filters a role’s permissions by search, by on/off and by area', async () => {
    const user = userEvent.setup();
    await renderScreen(<AccessView />);
    const detail = await openRole(user, 'Finance');
    await user.type(within(detail).getByLabelText('Search permissions'), 'refund');
    expect(within(detail).getByText('Refunds: issue')).toBeInTheDocument();
    expect(within(detail).queryByText('Payouts: read')).not.toBeInTheDocument();
    await user.clear(within(detail).getByLabelText('Search permissions'));
    await user.selectOptions(within(detail).getByLabelText('Show'), 'off');
    expect(within(detail).queryByText('Refunds: issue')).not.toBeInTheDocument();
    expect(within(detail).getByText('Catalog: write')).toBeInTheDocument();
    await user.selectOptions(within(detail).getByLabelText('Area'), 'delivery');
    expect(within(detail).queryByText('Catalog: write')).not.toBeInTheDocument();
    expect(within(detail).getByText('Delivery: operate')).toBeInTheDocument();
  });

  it('shows permissions added later (delivery:read, delivery:manage) from the catalogue, with nothing hard-coded', async () => {
    const user = userEvent.setup();
    addMockPermission(getStaffDb(), new Date(), { key: 'delivery:read', module: 'delivery', description: 'View delivery runs, stops and drivers' });
    addMockPermission(getStaffDb(), new Date(), { key: 'delivery:manage', module: 'delivery', description: 'Create and change delivery runs' });
    await renderScreen(<AccessView />);
    const detail = await openRole(user, 'Dispatcher');
    const group = within(detail).getByTestId('perm-group-delivery');
    expect(within(group).getByText('Delivery: read')).toBeInTheDocument();
    expect(within(group).getByText('View delivery runs, stops and drivers')).toBeInTheDocument();
    expect(within(group).getByText('Delivery: manage')).toBeInTheDocument();
    await user.click(sw(detail, 'Delivery: manage for Dispatcher'));
    const dialog = await screen.findByRole('dialog', { name: 'Add “Delivery: manage” to Dispatcher?' });
    await user.type(within(dialog).getByLabelText(/^Why/), 'Dispatchers run deliveries');
    await user.click(within(dialog).getByRole('button', { name: 'Add permission' }));
    await waitFor(() => expect(sw(screen.getByTestId('role-detail'), 'Delivery: manage for Dispatcher')).toBeChecked());
  });

  it('an auditor with only access:read sees the same screen, read-only', async () => {
    asRoles('platform.auditor');
    const user = userEvent.setup();
    await renderScreen(<AccessView />);
    const detail = await openRole(user, 'Finance');
    expect(within(detail).getByText('You can look but not change. Changing roles and permissions needs the access:admin permission.')).toBeInTheDocument();
    for (const s of within(detail).getAllByRole('switch')) expect(s).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'All roles' }));
    await user.click(await screen.findByRole('tab', { name: 'People' }));
    await user.type(await screen.findByRole('searchbox'), 'Grace');
    await user.click(await screen.findByText('Grace Adjei'));
    const person = await screen.findByTestId('person-detail');
    await within(person).findByTestId('permission-groups');
    expect(within(person).queryByTestId('grant-direct')).not.toBeInTheDocument();
    expect(within(person).queryByRole('button', { name: 'Take away' })).not.toBeInTheDocument();
  });

  it('finds a person by name and shows what they can do, from which role, then grants and takes away a direct permission', async () => {
    const user = userEvent.setup();
    await renderScreen(<AccessView />);
    await user.click(await screen.findByRole('tab', { name: 'People' }));
    await user.type(await screen.findByRole('searchbox'), 'grace');
    await user.click(await screen.findByText('Grace Adjei'));
    const person = await screen.findByTestId('person-detail');
    expect(within(person).getByRole('heading', { name: 'Grace Adjei', level: 2 })).toBeInTheDocument();
    expect(await within(person).findByTestId('source-refunds:issue')).toHaveTextContent('From Customer Support');
    expect(within(person).queryByTestId('source-payouts:approve')).not.toBeInTheDocument();

    await user.selectOptions(within(person).getByLabelText('Grant a permission directly'), 'payouts:approve');
    await user.click(within(person).getByRole('button', { name: 'Grant' }));
    const dialog = await screen.findByRole('dialog', { name: 'Give Grace Adjei “Payouts: approve”?' });
    await user.type(within(dialog).getByLabelText(/^Why/), 'Covers finance this week');
    await user.click(within(dialog).getByRole('button', { name: 'Grant' }));
    expect(await within(await screen.findByTestId('person-detail')).findByTestId('source-payouts:approve')).toHaveTextContent('Granted directly');
    expect(await screen.findByText('“Payouts: approve” granted to Grace Adjei')).toBeInTheDocument();

    await user.click(within(screen.getByTestId('person-detail')).getByRole('button', { name: 'Take away' }));
    const revoke = await screen.findByRole('dialog', { name: 'Take “Payouts: approve” from Grace Adjei?' });
    await user.type(within(revoke).getByLabelText(/^Why/), 'Back from cover');
    await user.click(within(revoke).getByRole('button', { name: 'Take away' }));
    await waitFor(() => expect(within(screen.getByTestId('person-detail')).queryByTestId('source-payouts:approve')).not.toBeInTheDocument());
    // their changes are listed by name, newest first
    const changes = within(screen.getByTestId('person-detail')).getByTestId('person-log');
    expect(within(changes).getAllByRole('listitem')[0]).toHaveTextContent('Permission revoked');
  });

  it('is axe-clean: the roles list and a role', async () => {
    const user = userEvent.setup();
    const { container } = await renderScreen(<AccessView />);
    await screen.findByRole('table', { name: 'Roles' });
    const list = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(list.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`)).toEqual([]);
    await openRole(user, 'Finance');
    await screen.findByTestId('perm-group-orders');
    const role = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(role.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`)).toEqual([]);
  });
});

describe('the catalogue helpers', () => {
  it('turn a key into words and group by the catalogue module', () => {
    expect(permissionLabel('refunds:issue')).toBe('Refunds: issue');
    expect(permissionLabel('delivery:dispatch:read')).toBe('Delivery: dispatch read');
    expect(permissionLabel('content:site.view')).toBe('Content: site view');
    expect(moduleLabel('fulfilment')).toBe('Fulfilment');
    const groups = groupPermissions([{ key: 'b:x', module: 'beta', description: '' }, { key: 'a:y', module: 'alpha', description: '' }], ['zzz:unknown']);
    expect(groups.map((g) => g.label)).toEqual(['Alpha', 'Beta', 'Other']);
  });

  it('say where a permission comes from', () => {
    const roles = new Map([['platform.finance', { key: 'platform.finance', portal: 'platform', name: 'Finance', description: '', permissions: ['refunds:issue'], permissionCount: 1 }]]);
    const person = { roles: [{ key: 'platform.finance', name: 'Finance' }], directGrants: ['delivery:read'] };
    expect(sourceOf(person, roles, 'refunds:issue')).toEqual({ roles: ['Finance'], direct: false });
    expect(sourceOf(person, roles, 'delivery:read')).toEqual({ roles: [], direct: true });
  });
});
