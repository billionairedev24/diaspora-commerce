import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ModuleView } from '@/components/module/ModuleView';
import { FormFields } from '@/components/resources/FormFields';
import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

describe('server-driven tables and detail sheets', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/payouts');
  });

  it('opens a payout sheet from the table and approves it after confirmation', async () => {
    asRoles('platform.finance');
    const user = userEvent.setup();
    await renderScreen(<ResourceTable resource="payouts" />);
    const table = await screen.findByRole('table', { name: 'Payout requests' });
    // A row click opens the sheet (the checkbox never does).
    await user.click(await within(table).findByText('pay-700'));
    expect(nav.search).toBe('open=pay-700');
    const approve = await screen.findByRole('button', { name: 'Approve & pay' });
    await user.click(approve);
    await user.click(within(await screen.findByRole('dialog', { name: 'Approve & pay' })).getByRole('button', { name: 'Approve & pay' }));
    await waitFor(() => expect(screen.getAllByText('Approved').length).toBeGreaterThan(0));
    expect(await screen.findByText(/Approved — Stripe payout created/)).toBeInTheDocument();
  });

  it('shows actions the role may not run as locked, with the reason', async () => {
    asRoles('platform.auditor');
    nav.set('/payouts?open=pay-700');
    await renderScreen(<ResourceTable resource="payouts" />);
    const approve = await screen.findByRole('button', { name: /Approve & pay/ });
    expect(approve).toBeDisabled();
    expect(approve).toHaveAttribute('title', 'Needs the payouts:approve permission.');
    expect(screen.getByText(/Locked actions need a permission/)).toBeInTheDocument();
  });

  it('validates the create form before it reaches the BFF', async () => {
    const user = userEvent.setup();
    nav.set('/fees?tab=tax-rates');
    await renderScreen(<ModuleView module="fees" />);
    await user.click(await screen.findByRole('button', { name: 'New' }));
    const dialog = await screen.findByRole('dialog', { name: 'New local rate' });
    await user.type(within(dialog).getByLabelText('State'), 'Texas');
    await user.click(within(dialog).getByRole('button', { name: 'Add rate' }));
    // The error shows under the field and in the summary at the top, which links to the field (S194).
    expect(within(dialog).getByRole('link', { name: 'Two-letter state code, e.g. TX' })).toHaveAttribute('href', '#fld-state');
    expect(within(dialog).getAllByText('Two-letter state code, e.g. TX')).toHaveLength(2);
    expect(within(dialog).getAllByText('Required').length).toBeGreaterThan(0);
    await user.clear(within(dialog).getByLabelText('State'));
    await user.type(within(dialog).getByLabelText('State'), 'TX');
    await user.type(within(dialog).getByLabelText('ZIP or prefix'), '772');
    await user.type(within(dialog).getByLabelText('Name'), 'Pasadena');
    await user.type(within(dialog).getByLabelText('Rate'), '0.02');
    await user.click(within(dialog).getByRole('button', { name: 'Add rate' }));
    await waitFor(() => expect(nav.search).toContain('open=local%3ATX%3A772'));
  });
});

describe('forms that name their change fields', () => {
  it('keep Save disabled until one of them differs from what the form opened with', async () => {
    const user = userEvent.setup();
    const form = { title: 'Change who calculates sales tax', submitLabel: 'Change method', changeKeys: ['method'], fields: [
      { key: 'method', label: 'Method', type: 'select' as const, required: true, value: 'RULES', options: [{ value: 'RULES', label: 'Our own rules' }, { value: 'STRIPE_TAX', label: 'Stripe Tax' }] },
      { key: 'reason', label: 'Why', type: 'textarea' as const, required: true }
    ] };
    render(<FormFields form={form} onSubmit={vi.fn()} />);
    const save = screen.getByRole('button', { name: 'Change method' });
    expect(save).toBeDisabled();
    await user.type(screen.getByLabelText(/Why/), 'Reviewing the method this quarter');
    expect(save).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('Method'), 'STRIPE_TAX');
    expect(save).toBeEnabled();
  });
});
