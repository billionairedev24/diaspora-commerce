import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

describe('AfroTransact credit and gift cards (S320, S321)', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/payouts');
  });

  it('lists credit adjustments by customer name and lets a different person approve one', async () => {
    const user = userEvent.setup();
    nav.set('/payouts?tab=credit-adjustments');
    await renderScreen(<ResourceTable resource="credit-adjustments" />);
    const table = await screen.findByRole('table', { name: 'Credit adjustments' });
    expect(within(table).getAllByText('Amara Nwosu').length).toBeGreaterThan(0);
    expect(table).not.toHaveTextContent('cus-1000');
    await user.click(within(table).getAllByText('Amara Nwosu')[0]!);
    await user.click(await screen.findByRole('button', { name: 'Approve and post' }));
    await user.click(within(await screen.findByRole('dialog', { name: 'Approve and post' })).getByRole('button', { name: 'Approve and post' }));
    await waitFor(() => expect(screen.getAllByText('Posted').length).toBeGreaterThan(0));
  });

  it('refuses your own proposal and says why', async () => {
    const user = userEvent.setup();
    nav.set('/payouts?tab=credit-adjustments');
    await renderScreen(<ResourceTable resource="credit-adjustments" />);
    await user.click(await screen.findByRole('button', { name: 'New' }));
    const dialog = await screen.findByRole('dialog', { name: "Adjust a customer's credit" });
    expect(within(dialog).getByText(/cannot be paid out or refunded to a card/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText("Customer's email address"), 'adaeze@example.com');
    await user.type(within(dialog).getByLabelText('Amount in dollars'), '250');
    await user.type(within(dialog).getByLabelText(/Why \(at least 15 characters/), 'Make good on a damaged order');
    await user.click(within(dialog).getByRole('button', { name: 'Continue' }));
    // The new request opens in its sheet: its own proposer cannot approve it, and the button says why.
    const approve = await screen.findByRole('button', { name: /^Approve and post/ });
    expect(approve).toBeDisabled();
    expect(approve).toHaveTextContent('You proposed this, so a different person must approve it.');
  });

  it('has no way to pay credit out, refund it to a card or move it to someone else', async () => {
    nav.set('/payouts?tab=credit-adjustments&open=cadj-1');
    await renderScreen(<ResourceTable resource="credit-adjustments" />);
    await screen.findByRole('button', { name: 'Approve and post' });
    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(/cash ?out|pay ?out credit|transfer credit|refund to (the )?card/i);
  });

  it('looks up a gift card by the last four of its code and voids it with a reason', async () => {
    const user = userEvent.setup();
    nav.set('/payouts?tab=gift-cards');
    await renderScreen(<ResourceTable resource="gift-cards" />);
    const table = await screen.findByRole('table', { name: 'Gift cards' });
    expect(within(table).getByText('•••• K3M9')).toBeInTheDocument();
    expect(table).not.toHaveTextContent(/[A-Z0-9]{16}/);
    await user.type(screen.getByRole('searchbox'), 'P7QX');
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Gift cards' })).queryByText('•••• K3M9')).not.toBeInTheDocument());
    await user.clear(screen.getByRole('searchbox'));
    await user.click(await within(screen.getByRole('table', { name: 'Gift cards' })).findByText('•••• K3M9'));
    await user.click(await screen.findByRole('button', { name: 'Void this gift card' }));
    const form = await screen.findByRole('dialog', { name: 'Why is it voided?' });
    await user.type(within(form).getByLabelText(/Reason/), 'Bought by mistake, buyer asked');
    await user.click(within(form).getByRole('button', { name: 'Void' }));
    expect(await screen.findByText(/Voided — Bought by mistake/)).toBeInTheDocument();
  });

  it('does not let an auditor void', async () => {
    asRoles('platform.auditor');
    nav.set('/payouts?tab=gift-cards&open=gc-1');
    await renderScreen(<ResourceTable resource="gift-cards" />);
    const voidCard = await screen.findByRole('button', { name: /Void this gift card/ });
    expect(voidCard).toBeDisabled();
    expect(voidCard).toHaveAttribute('title', 'Needs the ledger:adjust permission.');
  });

  it('shows credit and gift card liabilities apart, next to the ledger accounts', async () => {
    nav.set('/payouts?tab=credit-liabilities');
    await renderScreen(<ResourceTable resource="credit-liabilities" />);
    const table = await screen.findByRole('table', { name: 'Credit & gift card liability' });
    expect(within(table).getByText('Credit owed to customers')).toBeInTheDocument();
    expect(within(table).getByText('Gift cards sold or issued, not yet redeemed')).toBeInTheDocument();
    expect(within(table).getByText('Ledger: customer credit')).toBeInTheDocument();
    expect(within(table).getByText('Ledger: gift card liability')).toBeInTheDocument();
  });

  it('keeps the limits in Settings with a reason on every change', async () => {
    asRoles('platform.finance');
    const user = userEvent.setup();
    nav.set('/settings?section=credit&open=money');
    await renderScreen(<ResourceTable resource="money-settings" urlPrefix="money-settings" />);
    expect(await screen.findByText('Gift card terms (shown on the buy page)')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    const dialog = await screen.findByRole('dialog', { name: 'Change the credit and gift card limits' });
    const save = within(dialog).getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();
    const max = within(dialog).getByLabelText('Largest gift card (dollars)');
    await user.clear(max);
    await user.type(max, '750');
    await user.type(within(dialog).getByLabelText(/Why it changes/), 'Raise the ceiling for the holidays');
    expect(save).toBeEnabled();
    await user.click(save);
    expect((await screen.findAllByText('$750.00')).length).toBeGreaterThan(0);
    expect((await screen.findAllByText('Raise the ceiling for the holidays')).length).toBeGreaterThan(0);
  });
});
