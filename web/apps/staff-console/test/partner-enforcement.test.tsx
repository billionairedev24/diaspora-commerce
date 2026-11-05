import { getStaffDb, resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { chooseRowAction, rowMenuItems } from './row-menu';
import { nav } from './setup';

const statusOf = (id: string) => getStaffDb().records.organisations.find((o) => o.id === id)?.fields.status;

describe('Sellers & providers: suspend and reinstate', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-11-03T12:00:00Z'));
    asRoles('platform.trust_safety');
    nav.set('/organisations');
  });

  it('offers Suspend… on an approved row and Reinstate… on a suspended one, with the status as a badge', async () => {
    await renderScreen(<ResourceTable resource="organisations" />);
    await screen.findByText('Lagos Spice Co');
    expect(await rowMenuItems('Kente Naturals')).toEqual(['Suspend…']);
    await userEvent.setup().keyboard('{Escape}');
    expect(await rowMenuItems('Lagos Spice Co')).toEqual(['Reinstate…']);
    expect(screen.getAllByText('Suspended').length).toBeGreaterThan(0);
  });

  it('gives no menu to staff without sellers:enforce', async () => {
    asRoles('platform.finance');
    await renderScreen(<ResourceTable resource="organisations" />);
    await screen.findByText('Kente Naturals');
    expect(screen.queryByRole('button', { name: /^Actions for/ })).not.toBeInTheDocument();
  });

  it('suspends from the row menu: the reason rule is on screen while it is short, then the status changes', async () => {
    const user = userEvent.setup();
    await renderScreen(<ResourceTable resource="organisations" />);
    await screen.findByText('Kente Naturals');
    await chooseRowAction('Kente Naturals', 'Suspend…');
    expect(nav.search).toContain('open=org-kente');
    const dialog = await screen.findByRole('dialog', { name: 'Suspend this organisation' });
    // The effect on the seller is stated before anything is typed.
    expect(within(dialog).getByText(/listings are hidden from the marketplace and its payouts are paused/)).toBeInTheDocument();
    await user.selectOptions(within(dialog).getByLabelText('Reason'), 'FRAUD_RISK');
    await user.type(within(dialog).getByLabelText(/What happened/), 'updates');
    expect(within(dialog).getByText('Add a few more words: at least 10 characters (7 of 10)')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Suspend' }));
    expect(statusOf('org-kente')).toBe('APPROVED'); // refused with the reason, nothing was sent
    await user.type(within(dialog).getByLabelText(/What happened/), ' - chargebacks on three orders');
    await user.click(within(dialog).getByRole('button', { name: 'Suspend' }));
    await waitFor(() => expect(statusOf('org-kente')).toBe('SUSPENDED'));
    expect(getStaffDb().audit.some((a) => a.action === 'organisations.suspend' && a.entityId === 'org-kente')).toBe(true);
  });

  it('reinstates from the sheet, with the same reason rule', async () => {
    const user = userEvent.setup();
    nav.set('/organisations?open=org-lagos-spice');
    await renderScreen(<ResourceTable resource="organisations" />);
    await user.click(await screen.findByRole('button', { name: 'Reinstate…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reinstate this organisation' });
    expect(within(dialog).getByText(/Its listings are shown again and its payouts resume/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/Why it is safe/), 'short');
    expect(within(dialog).getByText('Add a few more words: at least 10 characters (5 of 10)')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/Why it is safe/), ' and the documents are in');
    await user.click(within(dialog).getByRole('button', { name: 'Reinstate' }));
    await waitFor(() => expect(statusOf('org-lagos-spice')).toBe('APPROVED'));
  });

  it('shows Suspend… on the sheet of an approved organisation and greys out Reinstate…', async () => {
    nav.set('/organisations?open=org-kente');
    await renderScreen(<ResourceTable resource="organisations" />);
    expect(await screen.findByRole('button', { name: 'Suspend…' })).toBeEnabled();
    expect(screen.getByRole('button', { name: /^Reinstate…/ })).toBeDisabled();
  });
});
