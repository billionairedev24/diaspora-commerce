import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { beforeEach, describe, expect, it } from 'vitest';

import { AccessView } from '@/components/access/AccessView';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const reasonText = 'Quarter close needs payouts approval';

async function openMatrix(user: ReturnType<typeof userEvent.setup>) {
  const view = await renderScreen(<AccessView />);
  await user.click(await screen.findByRole('tab', { name: 'Module access' }));
  const table = await screen.findByTestId('matrix-table');
  return { ...view, table };
}

describe('Roles and permissions > Module access', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/settings/access');
  });

  it('shows modules by name against roles by name, with the level of each, and no keys', async () => {
    const user = userEvent.setup();
    const { table } = await openMatrix(user);
    expect(within(table).getByRole('columnheader', { name: 'Finance' })).toBeInTheDocument();
    expect(within(table).getByRole('rowheader', { name: 'Payouts & ledger' })).toBeInTheDocument();
    expect(within(table).getByLabelText('Payouts & ledger for Finance')).toHaveValue('approve');
    expect(within(table).getByLabelText('Users & roles for Super Admin')).toHaveValue('full');
    expect(table.textContent).not.toMatch(/platform\./);
    expect(table.textContent).not.toMatch(/\brbac\b|\bpayouts\b|\bsiteCfg\b/);
  });

  it('changes a cell only after a confirmation with a reason, and logs it with the reason', async () => {
    const user = userEvent.setup();
    const { table } = await openMatrix(user);
    await user.selectOptions(within(table).getByLabelText('Payouts & ledger for Finance'), 'full');
    const dialog = await screen.findByRole('dialog', { name: "Change Finance's access to Payouts & ledger?" });
    expect(within(dialog).getByText('From Approve to Full.')).toBeInTheDocument();
    const confirm = within(dialog).getByRole('button', { name: 'Change access' });
    expect(confirm).toBeDisabled();
    // cancelling leaves the cell as it was
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(within(table).getByLabelText('Payouts & ledger for Finance')).toHaveValue('approve');

    await user.selectOptions(within(table).getByLabelText('Payouts & ledger for Finance'), 'full');
    const again = await screen.findByRole('dialog');
    await user.type(within(again).getByLabelText(/^Why/), 'ab');
    expect(within(again).getByRole('button', { name: 'Change access' })).toBeDisabled();
    await user.type(within(again).getByLabelText(/^Why/), 'c ' + reasonText);
    await user.click(within(again).getByRole('button', { name: 'Change access' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(within(screen.getByTestId('matrix-table')).getByLabelText('Payouts & ledger for Finance')).toHaveValue('full'));

    await user.click(screen.getByRole('tab', { name: 'Change log' }));
    const log = await screen.findByRole('table', { name: 'Change log' });
    expect(await within(log).findByText(/approve → full: abc Quarter close needs payouts approval/)).toBeInTheDocument();
    expect(within(log).getAllByText('Ifeoma Okafor').length).toBeGreaterThan(0);
  });

  it('keeps Super Admin at Edit or above on Users & roles and Access control', async () => {
    const user = userEvent.setup();
    const { table } = await openMatrix(user);
    const cell = within(table).getByLabelText('Users & roles for Super Admin');
    expect(within(cell).getByRole('option', { name: 'No access' })).toBeDisabled();
    expect(within(cell).getByRole('option', { name: 'View' })).toBeDisabled();
    expect(within(cell).getByRole('option', { name: 'Edit' })).toBeEnabled();
    // another role can lose the same module
    expect(within(within(table).getByLabelText('Users & roles for Finance')).getByRole('option', { name: 'No access' })).toBeEnabled();
    expect(screen.getByText(/always keeps at least Edit on Users & roles and Access control/)).toBeInTheDocument();
  });

  it('filters by role and module name', async () => {
    const user = userEvent.setup();
    const { table } = await openMatrix(user);
    await user.selectOptions(screen.getByLabelText('Role'), 'platform.finance');
    expect(within(table).getAllByRole('columnheader')).toHaveLength(2);
    expect(within(table).getByRole('columnheader', { name: 'Finance' })).toBeInTheDocument();
    await user.type(screen.getByLabelText('Search modules'), 'payout');
    expect(within(table).getByRole('rowheader', { name: 'Payouts & ledger' })).toBeInTheDocument();
    expect(within(table).queryByRole('rowheader', { name: 'Orders' })).not.toBeInTheDocument();
    await user.clear(screen.getByLabelText('Search modules'));
    await user.type(screen.getByLabelText('Search modules'), 'zzz');
    expect(await screen.findByText('No modules match.')).toBeInTheDocument();
  });

  it('shows an auditor (access:read only) the same grid as text, with no way to change a cell', async () => {
    asRoles('platform.auditor');
    const user = userEvent.setup();
    const { table } = await openMatrix(user);
    expect(screen.getByText('You can look but not change.', { exact: false })).toBeInTheDocument();
    expect(within(table).queryAllByRole('combobox')).toHaveLength(0);
    expect(within(table).getByTestId('cell-payouts-platform.finance')).toHaveTextContent('Approve');
  });

  it('is axe-clean, with the dialog open too', async () => {
    const user = userEvent.setup();
    const { container, table } = await openMatrix(user);
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(results.violations.map((v) => v.id)).toEqual([]);
    await user.selectOptions(within(table).getByLabelText('Orders for Finance'), 'edit');
    const dialog = await screen.findByRole('dialog');
    const open = await axe.run(dialog, { rules: { 'color-contrast': { enabled: false } } });
    expect(open.violations.map((v) => v.id)).toEqual([]);
  }, 30_000);
});
