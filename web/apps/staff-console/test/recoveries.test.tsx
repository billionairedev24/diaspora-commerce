import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

describe('Refund recoveries (refunds Stripe would not take back from a paid-out seller)', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/payouts?tab=recoveries');
  });

  it('lists what sellers owe by seller name and order number, never by id', async () => {
    await renderScreen(<ResourceTable resource="recoveries" />);
    const table = await screen.findByRole('table', { name: 'Refund recoveries' });
    expect(within(table).getByText('Kente Naturals')).toBeInTheDocument();
    expect(within(table).getByText('AT-000412')).toBeInTheDocument();
    expect(within(table).getAllByText('balance_insufficient').length).toBeGreaterThan(0);
    expect(table).not.toHaveTextContent(/org-|stf-|rcv-/);
  });

  it('proposes a write-off with a reason, and the proposer cannot approve their own proposal', async () => {
    const user = userEvent.setup();
    nav.set('/payouts?tab=recoveries&open=rcv-1');
    await renderScreen(<ResourceTable resource="recoveries" />);
    await user.click(await screen.findByRole('button', { name: 'Write off' }));
    const form = await screen.findByRole('dialog', { name: 'Write the recovery off' });
    expect(within(form).getByText(/never takes it from the seller's earnings/)).toBeInTheDocument();
    await user.type(within(form).getByLabelText(/Why \(at least 15 characters\)/), 'The seller closed the shop and cannot be reached');
    await user.click(within(form).getByRole('button', { name: 'Propose' }));
    await waitFor(() => expect(screen.getAllByText('Approval of a write-off').length).toBeGreaterThan(0));
    const approve = await screen.findByRole('button', { name: /^Approve and post/ });
    expect(approve).toBeDisabled();
    expect(approve).toHaveTextContent('You proposed this, so a different person must approve it.');
    expect(screen.getByRole('button', { name: /^Write off/ })).toBeDisabled();
  });

  it('asks for the bank reference when the seller paid outside Stripe', async () => {
    const user = userEvent.setup();
    nav.set('/payouts?tab=recoveries&open=rcv-1');
    await renderScreen(<ResourceTable resource="recoveries" />);
    await user.click(await screen.findByRole('button', { name: 'Mark recovered' }));
    const form = await screen.findByRole('dialog', { name: 'The seller paid the platform back' });
    await user.type(within(form).getByLabelText(/Why \(at least 15 characters\)/), 'The seller wired the money on Friday');
    await user.click(within(form).getByRole('button', { name: 'Propose' }));
    expect(await within(form).findByText(/Bank reference of the money received/)).toBeInTheDocument();
    await user.type(within(form).getByLabelText('Bank reference of the money received'), 'WIRE-4471');
    await user.click(within(form).getByRole('button', { name: 'Propose' }));
    await waitFor(() => expect(screen.getAllByText('Approval of money received').length).toBeGreaterThan(0));
  });

  it('lets a different person approve a proposal, which closes the recovery', async () => {
    const user = userEvent.setup();
    nav.set('/payouts?tab=recoveries&open=rcv-2');
    await renderScreen(<ResourceTable resource="recoveries" />);
    await user.click(await screen.findByRole('button', { name: 'Approve and post' }));
    await user.click(within(await screen.findByRole('dialog', { name: 'Approve and post' })).getByRole('button', { name: 'Approve and post' }));
    await waitFor(() => expect(screen.getAllByText('Written off').length).toBeGreaterThan(0));
    expect(screen.getByRole('button', { name: /^Write off/ })).toBeDisabled();
  });

  it('rejecting a proposal keeps the recovery open and records why', async () => {
    const user = userEvent.setup();
    nav.set('/payouts?tab=recoveries&open=rcv-2');
    await renderScreen(<ResourceTable resource="recoveries" />);
    await user.click(await screen.findByRole('button', { name: 'Reject' }));
    const form = await screen.findByRole('dialog', { name: 'Why is it rejected?' });
    await user.type(within(form).getByLabelText(/Explanation/), 'Chase the seller first, please');
    await user.click(within(form).getByRole('button', { name: 'Reject' }));
    expect(await screen.findByText(/Rejected — Chase the seller first/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Write off/ })).toBeEnabled();
  });

  it('does not let an auditor propose or approve', async () => {
    asRoles('platform.auditor');
    nav.set('/payouts?tab=recoveries&open=rcv-1');
    await renderScreen(<ResourceTable resource="recoveries" />);
    expect(await screen.findByRole('button', { name: /^Write off/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^Mark recovered/ })).toBeDisabled();
  });
});
