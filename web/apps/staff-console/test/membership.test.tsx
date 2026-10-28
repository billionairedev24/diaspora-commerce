import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';
import { SettingsHub } from '@/components/settings/SettingsHub';
import { ConsoleShell } from '@/components/shell/ConsoleShell';
import { CustomerPanel } from '@/components/support/CustomerPanel';

import { asRoles } from './mock-api';
import { renderScreen, renderWithProviders } from './render';
import { nav } from './setup';

describe('membership tiers in Settings (S323)', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/settings?section=membership');
  });

  it('is a Money section of Settings with the tiers and the billing rules', async () => {
    renderWithProviders(
      <ConsoleShell>
        <SettingsHub />
      </ConsoleShell>
    );
    const tiers = await screen.findByRole('table', { name: 'Membership tiers' });
    expect(within(tiers).getByText('Plus')).toBeInTheDocument();
    expect(within(tiers).getByText('Premium')).toBeInTheDocument();
    expect(within(tiers).getByText('$9.99')).toBeInTheDocument();
    expect(within(tiers).getByText(/Free AfroTransact delivery, Free carrier shipping, Cashback as credit, Priority support/)).toBeInTheDocument();
    expect(await screen.findByRole('table', { name: 'Membership billing rules' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Members, churn, the cost of the benefits/ })).toHaveAttribute('href', '/payouts?tab=memberships');
  });

  it('opens a tier in a sheet that lists its benefits and who changed it and why', async () => {
    const user = userEvent.setup();
    await renderScreen(<ResourceTable resource="membership-tiers" />);
    await user.click(await screen.findByText('Plus'));
    const sheet = await screen.findByRole('dialog', { name: 'Plus membership' });
    expect(within(sheet).getByText(/Free AfroTransact delivery: On orders from \$25\.00/)).toBeInTheDocument();
    expect(within(sheet).getByText(/Cashback as credit: 2% of the goods back as credit/)).toBeInTheDocument();
    expect(within(sheet).getByText('Open Plus to every shopper')).toBeInTheDocument();
    expect(within(sheet).getByRole('button', { name: /^Retire/ })).toBeEnabled();
    expect(within(sheet).getByRole('button', { name: /^Publish/ })).toBeDisabled();
  });

  it('keeps Save disabled until something changes, asks for a reason, and records the change', async () => {
    const user = userEvent.setup();
    await renderScreen(<ResourceTable resource="membership-tiers" />);
    await user.click(await screen.findByText('Plus'));
    const sheet = await screen.findByRole('dialog', { name: 'Plus membership' });
    await user.click(within(sheet).getByRole('button', { name: 'Edit' }));
    const form = await screen.findByRole('dialog', { name: 'Change the Plus tier' });
    const save = within(form).getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();

    const percent = within(form).getByLabelText(/Cashback: percent of the goods/);
    await user.clear(percent);
    await user.type(percent, '3');
    expect(save).toBeEnabled();
    await user.clear(percent);
    await user.type(percent, '2');
    expect(save).toBeDisabled(); // back to what it was: nothing to save

    await user.clear(percent);
    await user.type(percent, '3');
    await user.click(within(form).getByLabelText('Early access to pre-order windows and delivery runs'));
    await user.clear(within(form).getByLabelText(/Early access: hours before everyone else/));
    await user.type(within(form).getByLabelText(/Early access: hours before everyone else/), '24');
    await user.click(save);
    expect((await within(form).findAllByText('Required')).length).toBeGreaterThan(0); // a reason is required

    await user.type(within(form).getByLabelText(/Why it changes/), 'Raise the cashback for the holidays');
    await user.click(save);
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Change the Plus tier' })).not.toBeInTheDocument());
    const after = await screen.findByRole('dialog', { name: 'Plus membership' });
    expect(within(after).getByText(/3% of the goods back as credit/)).toBeInTheDocument();
    expect(within(after).getByText(/Early access to pre-orders and runs: 24 hours before everyone else/)).toBeInTheDocument();
    expect(within(after).getByText('Raise the cashback for the holidays')).toBeInTheDocument();
  }, 30_000);

  it('adds a tier as a draft and publishes it with a reason', async () => {
    const user = userEvent.setup();
    await renderScreen(<ResourceTable resource="membership-tiers" />);
    await user.click(await screen.findByText('Premium'));
    const sheet = await screen.findByRole('dialog', { name: 'Premium membership' });
    expect(within(sheet).getByRole('button', { name: /^Retire/ })).toBeDisabled(); // a draft cannot be retired
    await user.click(within(sheet).getByRole('button', { name: /^Publish/ }));
    const why = await screen.findByRole('dialog', { name: 'Why?' });
    await user.type(within(why).getByLabelText(/Reason/), 'Open the bigger tier to everyone');
    await user.click(within(why).getByRole('button', { name: 'Publish' }));
    expect(await screen.findByText(/Published — Open the bigger tier to everyone/)).toBeInTheDocument();
  }, 20_000);

  it('changes the refund window and the retry days with a reason', async () => {
    const user = userEvent.setup();
    await renderScreen(<ResourceTable resource="membership-settings" />);
    await user.click(await screen.findByText('Platform Admin'));
    const sheet = await screen.findByRole('dialog', { name: 'Membership billing rules' });
    await user.click(within(sheet).getByRole('button', { name: 'Edit' }));
    const form = await screen.findByRole('dialog', { name: 'Change the membership billing rules' });
    expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled();
    const days = within(form).getByLabelText(/Full refund when canceling within/);
    await user.clear(days);
    await user.type(days, '14');
    await user.type(within(form).getByLabelText(/Why it changes/), 'Match the returns window');
    await user.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Change the membership billing rules' })).not.toBeInTheDocument());
    expect(await screen.findByText('Match the returns window')).toBeInTheDocument();
  }, 20_000);

  it('does not let an auditor change a tier', async () => {
    asRoles('platform.auditor');
    const user = userEvent.setup();
    nav.set('/settings?section=membership');
    await renderScreen(<ResourceTable resource="membership-tiers" />);
    await user.click((await screen.findAllByText('Plus'))[0]!);
    const sheet = await screen.findByRole('dialog', { name: 'Plus membership' });
    expect(within(sheet).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    expect(within(sheet).getByRole('button', { name: /^Retire/ })).toBeDisabled();
  });
});

describe('members, churn and cost of the benefits (S323)', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
  });

  it('lists members by name and tier, finds them by tier name and refunds a fee with a reason', async () => {
    const user = userEvent.setup();
    nav.set('/payouts?tab=memberships');
    await renderScreen(<ResourceTable resource="memberships" />);
    const table = await screen.findByRole('table', { name: 'Members' });
    expect(within(table).getByText('Amara Nwosu')).toBeInTheDocument();
    expect(table).not.toHaveTextContent('cus-1000');
    expect(within(table).getAllByText('Plus').length).toBeGreaterThan(0);
    await user.type(screen.getByRole('searchbox'), 'Plus');
    expect(within(screen.getByRole('table', { name: 'Members' })).getByText('Amara Nwosu')).toBeInTheDocument();

    await user.click(within(screen.getByRole('table', { name: 'Members' })).getByText('Amara Nwosu'));
    const sheet = await screen.findByRole('dialog', { name: 'Amara Nwosu · Plus' });
    expect(within(sheet).getByText('Charges')).toBeInTheDocument();
    await user.click(within(sheet).getByRole('button', { name: 'Refund the latest fee to the card' }));
    const form = await screen.findByRole('dialog', { name: 'Why is the fee refunded?' });
    await user.type(within(form).getByLabelText(/Reason/), 'Charged twice by mistake');
    await user.click(within(form).getByRole('button', { name: 'Refund' }));
    expect(await screen.findByText(/Fee refunded — Charged twice by mistake/)).toBeInTheDocument();
  }, 30_000);

  it('reports members, churn, what the benefits cost and recurring revenue', async () => {
    nav.set('/payouts?tab=membership-report');
    await renderScreen(<ResourceTable resource="membership-report" />);
    const table = await screen.findByRole('table', { name: 'Membership: members, churn, cost and revenue' });
    for (const line of ['Members now', 'Monthly recurring revenue', 'Annual recurring revenue', 'Cost of the benefits', 'Credit granted to members', 'Churn', 'Plus tier']) {
      expect(within(table).getByText(line)).toBeInTheDocument();
    }
    expect(within(table).getAllByText('$119.88').length).toBeGreaterThan(0);
  });
});

describe('priority support for members (S323)', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles('platform.customer_support');
  });

  it('shows the member, the priority-support flag and free returns in the customer view', async () => {
    await renderScreen(<CustomerPanel customerId="cus-1000" />);
    const flag = await screen.findByTestId('support-membership');
    expect(flag).toHaveTextContent('Plus member');
    expect(flag).toHaveTextContent('Priority support: handle first');
    expect(flag).not.toHaveTextContent('Free returns');
  });

  it('shows nothing for a customer who is not a member', async () => {
    await renderScreen(<CustomerPanel customerId="cus-1002" />);
    await screen.findByText(/Refund limit/);
    expect(screen.queryByTestId('support-membership')).not.toBeInTheDocument();
  });
});
