import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ModuleScreen } from '@/components/module/ModuleScreen';
import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/*
 * Delivery quotes (Customer care, listing rules D20): the queue tabs, who has each request (With / Waiting / SLA /
 * Escalation), claim, then agree an amount (reason required, within the bounds) or decline, with names on the timeline and
 * the actions following permission. Seed: dq-1 and dq-3 wait in the pool, dq-2 is with Amina Bello (26 h, overdue, escalated),
 * dq-4 is ready and waits on the customer, dq-5 declined, dq-6 used.
 */

describe('Delivery quotes', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-11-11T14:00:00Z'));
    asRoles(null);
  });
  afterEach(() => interceptStaff(null));

  it('offers the queue tabs and shows who has each request, for how long and the SLA', async () => {
    const user = userEvent.setup();
    nav.set('/delivery-quotes?status=ALL');
    await renderScreen(<ResourceTable resource="delivery-quotes" statusFilter defaultStatus="MY" />);
    const tabs = await screen.findByRole('group', { name: /status|filter/i });
    for (const label of ['My queue', 'Unassigned', 'Waiting on customer', 'Closed', 'Escalated', 'Overdue', 'All']) {
      expect(within(tabs).getByRole('button', { name: label })).toBeInTheDocument();
    }
    const table = await screen.findByRole('table');
    for (const h of ['With', 'Waiting', 'SLA', 'Escalation', 'Charge']) expect(await within(table).findByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    const held = (await within(table).findByText('Amina Bello')).closest('tr') as HTMLElement;
    expect(within(held).getByText('26 h')).toBeInTheDocument();
    expect(within(held).getByText('Overdue')).toBeInTheDocument();
    expect(within(held).getByText('Level 1')).toBeInTheDocument();
    expect(within(held).getByText('Being quoted')).toBeInTheDocument();
    const ready = (await within(table).findByText('The customer')).closest('tr') as HTMLElement;
    expect(within(ready).getByText('Quote ready')).toBeInTheDocument();
    expect(within(ready).getByText('$22.50')).toBeInTheDocument();

    await user.click(within(tabs).getByRole('button', { name: 'Unassigned' }));
    await waitFor(() => expect(within(screen.getByRole('table')).queryByText('Amina Bello')).not.toBeInTheDocument());
    expect(within(screen.getByRole('table')).getAllByText('Requested').length).toBe(2);
    await user.click(within(tabs).getByRole('button', { name: 'Closed' }));
    await waitFor(() => expect(within(screen.getByRole('table')).getByText('Declined')).toBeInTheDocument());
    expect(within(screen.getByRole('table')).getByText('Used')).toBeInTheDocument();
  });

  it('leads the sheet with where it is and shows the timeline with names, never ids', async () => {
    nav.set('/delivery-quotes?status=ALL&open=dq-2');
    await renderScreen(<ResourceTable resource="delivery-quotes" statusFilter defaultStatus="MY" />);
    const sheet = await screen.findByRole('dialog');
    expect((await within(sheet).findAllByText(/Being quoted · with Amina Bello · waiting 26 h/)).length).toBeGreaterThan(0);
    expect(within(sheet).getByText('Claimed by Amina Bello')).toBeInTheDocument();
    expect(sheet).not.toHaveTextContent(/stf-\d|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/);
  });

  it('claims a request from the pool, then agrees an amount with a reason within the bounds', async () => {
    const user = userEvent.setup();
    nav.set('/delivery-quotes?status=ALL&open=dq-1');
    await renderScreen(<ResourceTable resource="delivery-quotes" statusFilter defaultStatus="MY" />);
    await user.click(await screen.findByRole('button', { name: 'Claim' }));
    const confirm = (await screen.findAllByRole('button', { name: 'Claim' })).at(-1) as HTMLElement;
    await user.click(confirm);
    await waitFor(() => expect(screen.getByRole('dialog').textContent).toMatch(/with Ifeoma Okafor/));

    await user.click(screen.getByRole('button', { name: 'Agree a charge…' }));
    const form = await screen.findByRole('dialog', { name: 'Agree a delivery charge' });
    expect(within(form).getByText(/Between \$5\.00 and \$500\.00/)).toBeInTheDocument();
    // The reason is required.
    await user.type(within(form).getByLabelText(/Delivery charge/), '18.50');
    await user.click(within(form).getByRole('button', { name: 'Send the quote' }));
    expect((await within(form).findAllByText(/required|fill|reason/i)).length).toBeGreaterThan(0);
    // And so are the bounds.
    await user.clear(within(form).getByLabelText(/Delivery charge/));
    await user.type(within(form).getByLabelText(/Delivery charge/), '900');
    await user.type(within(form).getByLabelText(/^Why this amount/), 'Rural route, 140 miles');
    await user.click(within(form).getByRole('button', { name: 'Send the quote' }));
    await waitFor(() => expect(screen.getAllByRole('dialog').map((d) => d.textContent).join('|')).toMatch(/between \$5\.00 and \$500\.00|at most|500/i));
    await user.clear(within(form).getByLabelText(/Delivery charge/));
    await user.type(within(form).getByLabelText(/Delivery charge/), '18.50');
    await user.click(within(form).getByRole('button', { name: 'Send the quote' }));
    await waitFor(() => expect(screen.getByRole('dialog').textContent).toMatch(/Quote ready/));
    const sheet = screen.getByRole('dialog');
    expect(within(sheet).getByText(/Agreed \$18\.50: Rural route, 140 miles/)).toBeInTheDocument();
    expect(within(sheet).getByText('$18.50')).toBeInTheDocument();
    // Answered: nothing more to agree.
    expect(within(sheet).queryByRole('button', { name: 'Agree a charge…' })?.hasAttribute('disabled') ?? true).toBe(true);
  });

  it('declining needs a reason and closes the request', async () => {
    const user = userEvent.setup();
    nav.set('/delivery-quotes?status=ALL&open=dq-3');
    await renderScreen(<ResourceTable resource="delivery-quotes" statusFilter defaultStatus="MY" />);
    await user.click(await screen.findByRole('button', { name: 'Decline' }));
    const form = await screen.findByRole('dialog', { name: 'Decline to quote' });
    await user.click(within(form).getByRole('button', { name: 'Decline' }));
    expect((await within(form).findAllByText(/required|fill|reason/i)).length).toBeGreaterThan(0);
    await user.type(within(form).getByLabelText(/^Why/), 'We do not run to Fort Davis');
    await user.click(within(form).getByRole('button', { name: 'Decline' }));
    await waitFor(() => expect(screen.getByRole('dialog').textContent).toMatch(/Declined: We do not run to Fort Davis/));
  });

  it('a person who only views sees the queue and the holder but no enabled action', async () => {
    asRoles('platform.auditor');
    nav.set('/delivery-quotes?status=ALL&open=dq-2');
    await renderScreen(<ResourceTable resource="delivery-quotes" statusFilter defaultStatus="MY" />);
    const sheet = await screen.findByRole('dialog');
    expect((await within(sheet).findAllByText(/with Amina Bello/)).length).toBeGreaterThan(0);
    for (const name of ['Claim', 'Assign…', 'Release to the pool', 'Escalate now', 'Agree a charge…', 'Decline']) {
      const b = within(sheet).queryByRole('button', { name });
      if (b) expect(b).toBeDisabled();
    }
  });

  it('the module shows the queue and the settings, and the settings explain the bounds', async () => {
    const user = userEvent.setup();
    nav.set('/delivery-quotes');
    await renderScreen(<ModuleScreen module="deliveryQuotes" />);
    expect(await screen.findByRole('tab', { name: 'Delivery quotes' })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'SLA, escalation and amounts' }));
    const table = await screen.findByRole('table');
    expect(await within(table).findByText('1 business day')).toBeInTheDocument();
    expect(within(table).getByText('$5.00 to $500.00')).toBeInTheDocument();
    expect(within(table).getByText('48 h')).toBeInTheDocument();
  });
});
