import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

describe('Referral program (S322)', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/payouts');
  });

  it('lists referrals by name with status, rewards and what AfroTransact paid, never ids', async () => {
    nav.set('/payouts?tab=referrals');
    await renderScreen(<ResourceTable resource="referrals" urlPrefix="referrals" statusFilter />);
    const table = await screen.findByRole('table', { name: 'Referrals' });
    expect(within(table).getAllByText('Amara Nwosu').length).toBeGreaterThan(0);
    expect(within(table).getByText('Same device')).toBeInTheDocument();
    expect(within(table).getByText('Referrer $10.00 credit · Friend $10.00 credit')).toBeInTheDocument();
    expect(within(table).getByText('Referrer $10.00 credit (pending) · Friend 14 membership days (deferred)')).toBeInTheDocument();
    expect(within(table).getByText('$20.00')).toBeInTheDocument();
    expect(table).not.toHaveTextContent(/cus-\d+/);
  });

  it('shows the review queue with one tap on the In review chip', async () => {
    const user = userEvent.setup();
    nav.set('/payouts?tab=referrals');
    await renderScreen(<ResourceTable resource="referrals" urlPrefix="referrals" statusFilter />);
    const chips = await screen.findByRole('group', { name: 'Status' });
    expect(within(chips).getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(within(chips).getByRole('button', { name: 'In review' }));
    const table = await screen.findByRole('table', { name: 'Referrals' });
    await waitFor(() => expect(within(table).queryByText('Referrer $10.00 credit · Friend $10.00 credit')).not.toBeInTheDocument());
    expect(within(table).getByText('Same device')).toBeInTheDocument();
    expect(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'In review' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('approves a flagged referral with a reason of at least 15 characters', async () => {
    const user = userEvent.setup();
    nav.set('/payouts?tab=referrals&open=ref-1');
    await renderScreen(<ResourceTable resource="referrals" />);
    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    const form = await screen.findByRole('dialog', { name: 'Why is it fine?' });
    await user.type(within(form).getByLabelText(/Reason/), 'short');
    await user.click(within(form).getByRole('button', { name: 'Approve' }));
    expect(await within(form).findByText(/at least 15 characters/i)).toBeInTheDocument();
    await user.clear(within(form).getByLabelText(/Reason/));
    await user.type(within(form).getByLabelText(/Reason/), 'Two housemates, checked by phone');
    await user.click(within(form).getByRole('button', { name: 'Approve' }));
    expect(await screen.findByText(/Approved — Two housemates, checked by phone/)).toBeInTheDocument();
    expect(screen.getAllByText('Waiting').length).toBeGreaterThan(0);
  });

  it('rejects with a reason, and a rejected referral can be neither approved nor rejected again', async () => {
    const user = userEvent.setup();
    nav.set('/payouts?tab=referrals&open=ref-1');
    await renderScreen(<ResourceTable resource="referrals" />);
    await user.click(await screen.findByRole('button', { name: 'Reject' }));
    const form = await screen.findByRole('dialog', { name: 'Why is it rejected?' });
    await user.type(within(form).getByLabelText(/Reason/), 'The same household made a second account');
    await user.click(within(form).getByRole('button', { name: 'Reject' }));
    expect(await screen.findByText(/Rejected — The same household made a second account/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /^Reject/ })).toBeDisabled());
    expect(screen.getByRole('button', { name: /^Approve/ })).toBeDisabled();
  });

  it('does not let an auditor decide', async () => {
    asRoles('platform.auditor');
    nav.set('/payouts?tab=referrals&open=ref-1');
    await renderScreen(<ResourceTable resource="referrals" />);
    const approve = await screen.findByRole('button', { name: /^Approve/ });
    expect(approve).toBeDisabled();
    expect(approve).toHaveAttribute('title', 'Needs the ledger:adjust permission.');
  });

  it('configures each side separately in two sections, and Save waits for a change and a reason', async () => {
    asRoles('platform.finance');
    const user = userEvent.setup();
    nav.set('/settings?section=referrals&open=referral');
    await renderScreen(<ResourceTable resource="referral-settings" urlPrefix="referral-settings" />);
    expect(await screen.findByText('What the program has cost so far')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    const dialog = await screen.findByRole('dialog', { name: 'Change the referral program' });
    expect(within(dialog).getByRole('heading', { name: 'What the customer who shares earns' })).toBeInTheDocument();
    expect(within(dialog).getByRole('heading', { name: 'What the friend who joins earns' })).toBeInTheDocument();
    expect(within(dialog).getByText(/Credit only buys products and services and is never cash/)).toBeInTheDocument();
    const save = within(dialog).getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();

    const [sharerReward, friendReward] = within(dialog).getAllByLabelText('Reward');
    await user.selectOptions(sharerReward!, 'MEMBERSHIP_DAYS');
    expect(save).toBeEnabled();
    const [, friendCredit] = within(dialog).getAllByLabelText(/^Credit \(dollars/);
    await user.clear(friendCredit!);
    await user.type(friendCredit!, '12.5');
    const [sharerDays] = within(dialog).getAllByLabelText(/^Membership days/);
    await user.clear(sharerDays!);
    await user.type(sharerDays!, '14');
    expect(friendReward).toHaveValue('CREDIT');
    await user.type(within(dialog).getByLabelText(/Why it changes/), 'Membership for sharers, 12.50 for friends');
    await user.click(save);
    expect((await screen.findAllByText('14 membership days')).length).toBeGreaterThan(0);
    expect((await screen.findAllByText('$12.50 credit')).length).toBeGreaterThan(0);
    expect((await screen.findAllByText('Membership for sharers, 12.50 for friends')).length).toBeGreaterThan(0);
  });

  it('puts the settings under Money in Settings', async () => {
    const { SETTINGS_SECTIONS } = await import('@/lib/settings-sections');
    const section = SETTINGS_SECTIONS.find((s) => s.key === 'referrals');
    expect(section).toMatchObject({ group: 'money', module: 'payouts', label: 'Referral program' });
  });
});
