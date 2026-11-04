import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { toZonedLocal, zonedInstant } from '@/components/runs/model';
import { RunsView } from '@/components/runs/RunsView';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';

/* Delivery runs from the staff console (S260) against the staff mocks: the list by stage, the runs that need a decision,
   a new run with its area and break-even, the run sheet with its timeline and actions, and read-only for everyone else. */

const NOW = new Date('2026-10-10T15:00:00Z');
const AMARILLO = '4b2f9d3a-0000-4000-8000-00000000000b';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  resetStaffDb(NOW);
  asRoles(null);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('time in the location’s zone', () => {
  it('converts wall-clock time in a zone to an instant and back, across daylight saving', () => {
    expect(zonedInstant('2026-11-05T20:00', 'America/Chicago')).toBe('2026-11-06T02:00:00.000Z');
    expect(zonedInstant('2026-07-05T20:00', 'America/Chicago')).toBe('2026-07-06T01:00:00.000Z');
    expect(zonedInstant('2026-10-10T09:00', 'UTC')).toBe('2026-10-10T09:00:00.000Z');
    // A time the clocks skip is still read as a time that exists.
    expect(zonedInstant('2026-03-08T02:30', 'America/Chicago')).toMatch(/^2026-03-08T0[78]:30:00.000Z$/);
    expect(zonedInstant('not a time', 'UTC')).toBeNull();
    expect(toZonedLocal('2026-11-06T02:00:00Z', 'America/Chicago')).toBe('2026-11-05T20:00');
    expect(toZonedLocal('2026-11-06T02:00:00Z', 'Africa/Lagos')).toBe('2026-11-06T03:00');
    expect(toZonedLocal(null, 'UTC')).toBe('');
  });
});

describe('the runs of a location', () => {
  it('lists them by stage with the orders against the minimum', async () => {
    const user = userEvent.setup();
    await renderScreen(<RunsView />);
    const list = await screen.findByTestId('run-list');
    expect(within(list).getByTestId('run-tab-count-all')).toHaveTextContent('4');
    expect(within(list).getByTestId('run-tab-count-open')).toHaveTextContent('1');
    expect(within(list).getByTestId('run-tab-count-done')).toHaveTextContent('2');
    expect(within(list).getByTestId('run-tab-count-cancelled')).toHaveTextContent('1');
    expect(within(list).getByTestId('run-tab-count-attention')).toHaveTextContent('0');
    const open = within(list).getByTestId('run-line-OPEN');
    expect(open).toHaveTextContent('19 of 12 orders');
    expect(within(open).getByTestId('orders-meter')).toHaveAttribute('data-met', 'true');
    await user.click(within(list).getByRole('tab', { name: /Cancelled/ }));
    expect(within(list).getAllByTestId(/^run-line-/)).toHaveLength(1);
    expect(within(list).getByTestId('run-line-CANCELLED')).toBeInTheDocument();
    await user.click(within(list).getByRole('tab', { name: /Needs a decision/ }));
    expect(await within(list).findByTestId('run-list-empty')).toHaveTextContent('No runs in this stage.');
  });

  it('shows the runs that need a decision across locations, and what the cutoff will do to them', async () => {
    const user = userEvent.setup();
    await renderScreen(<RunsView />);
    const panel = await screen.findByTestId('runs-need-decision');
    expect(panel).toHaveTextContent('1 run needs a decision');
    const row = within(panel).getByTestId('needs-decision-row');
    expect(row).toHaveTextContent('Amarillo');
    expect(row).toHaveTextContent('5 of 12 orders');
    expect(row).toHaveTextContent('extended once by 7 days');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Location' }), 'Amarillo');
    const list = await screen.findByTestId('run-list');
    await waitFor(() => expect(within(list).getByTestId('run-tab-count-attention')).toHaveTextContent('1'));
    await user.click(within(list).getByRole('tab', { name: /Needs a decision/ }));
    const line = within(list).getByTestId('run-line-OPEN');
    expect(line).toHaveTextContent('Needs a decision');
    expect(line).toHaveAttribute('data-attention', 'true');
  });
});

describe('a run on the right', () => {
  const openShort = async () => {
    const user = userEvent.setup();
    await renderScreen(<RunsView />);
    const panel = await screen.findByTestId('runs-need-decision');
    await user.click(within(panel).getByRole('button', { name: 'Review' }));
    const run = await screen.findByTestId('run-sheet');
    return { user, run };
  };

  it('says what is wrong, where the run is in its life and how many orders it has against the minimum and capacity', async () => {
    const { run } = await openShort();
    const outlook = within(run).getByTestId('run-outlook');
    expect(outlook).toHaveAttribute('data-kind', 'AT_RISK');
    expect(outlook).toHaveTextContent('Needs a decision: 7 orders short');
    expect(outlook).toHaveTextContent('extended once by 7 days');
    const steps = within(within(run).getByRole('list', { name: 'Status timeline' })).getAllByRole('listitem');
    expect(steps.map((s) => s.getAttribute('data-step'))).toEqual(['DRAFT', 'ANNOUNCED', 'OPEN', 'CONFIRMED', 'COLLECTING', 'OUT_FOR_DELIVERY', 'COMPLETED']);
    expect(steps[2]).toHaveAttribute('aria-current', 'step');
    expect(steps[3]).toHaveAttribute('data-done', 'false');
    const counts = within(run).getByTestId('run-counts');
    expect(counts).toHaveTextContent('5 of 12 orders');
    expect(counts).toHaveTextContent('Below the minimum');
    expect(within(counts).getByTestId('run-capacity')).toHaveTextContent('40 · 35 left');
    expect(within(counts).getByTestId('run-break-even-orders')).toHaveTextContent(/^\d+$/);
    expect(within(run).getByTestId('run-details')).toHaveTextContent('The delivery zone drawn for Amarillo');
    expect(within(run).getByTestId('run-details')).toHaveTextContent('Two sellers deliver to the hub on Thursday evening');
    expect(within(run).getByTestId('run-orders')).toHaveTextContent('AT-');
    expect(within(run).getByTestId('run-log')).toHaveTextContent('Nobody has extended, confirmed or cancelled this run yet.');
    const actions = within(run).getByTestId('run-actions');
    for (const name of ['Edit', 'Extend cutoff', 'Confirm below the minimum', 'Cancel run']) expect(within(actions).getByRole('button', { name })).toBeEnabled();
    expect(within(actions).queryByRole('button', { name: 'Announce' })).not.toBeInTheDocument();
  });

  it('confirms below the minimum only with a reason, then logs who did it and why', async () => {
    const { user, run } = await openShort();
    await user.click(within(run).getByRole('button', { name: 'Confirm below the minimum' }));
    const dialog = await screen.findByRole('dialog', { name: 'Confirm this run now?' });
    expect(dialog).toHaveTextContent('5 of 12 orders, below its minimum');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm run' }));
    expect(await within(dialog).findByText('Enter a reason.')).toBeInTheDocument();
    expect((await mockApi().runs.runs({ locationId: AMARILLO, status: ['CONFIRMED'] })).length).toBe(0);
    await user.type(within(dialog).getByRole('textbox', { name: 'Why confirm below the minimum?' }), 'The sellers already packed');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm run' }));
    expect(await screen.findByText('Run confirmed')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Confirm this run now?' })).not.toBeInTheDocument());
    const log = await screen.findByTestId('run-log-CONFIRM_OVERRIDE');
    expect(log).toHaveTextContent('Confirmed below the minimum');
    expect(log).toHaveTextContent('Reason: The sellers already packed');
    expect(log).toHaveTextContent('5 of 12 orders at the time');
    expect(log).not.toHaveTextContent('System');
    await waitFor(() => expect(screen.getByTestId('run-sheet-status')).toHaveTextContent('Confirmed'));
    expect(screen.queryByTestId('run-outlook')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Extend cutoff' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel run' })).toBeInTheDocument();
  });

  it('extends the cutoff with a reason and shows the new cutoff and the entry in the log', async () => {
    const { user, run } = await openShort();
    await user.click(within(run).getByRole('button', { name: 'Extend cutoff' }));
    const dialog = await screen.findByRole('dialog', { name: 'Extend the cutoff' });
    expect(within(dialog).getByLabelText('New cutoff')).toHaveValue('2026-10-18T00:00');
    await user.click(within(dialog).getByRole('button', { name: 'Extend' }));
    expect(await within(dialog).findByText('Enter a reason.')).toBeInTheDocument();
    await user.clear(within(dialog).getByLabelText('New cutoff'));
    await user.type(within(dialog).getByLabelText('New cutoff'), '2026-10-09T10:00');
    await user.type(within(dialog).getByRole('textbox', { name: 'Why are you extending?' }), 'Two sellers need another day');
    await user.click(within(dialog).getByRole('button', { name: 'Extend' }));
    expect(await within(dialog).findByText('Choose a time after the current cutoff.')).toBeInTheDocument();
    await user.clear(within(dialog).getByLabelText('New cutoff'));
    await user.type(within(dialog).getByLabelText('New cutoff'), '2026-10-14T10:00');
    await user.click(within(dialog).getByRole('button', { name: 'Extend' }));
    expect(await screen.findByText('Cutoff moved')).toBeInTheDocument();
    const log = await screen.findByTestId('run-log-EXTEND');
    expect(log).toHaveTextContent('Cutoff extended');
    expect(log).toHaveTextContent('Reason: Two sellers need another day');
    expect(log).toHaveTextContent('Cutoff moved from');
    await waitFor(() => expect(screen.getByTestId('run-sheet-status')).toHaveTextContent('Extended'));
    expect(screen.getByTestId('run-details')).toHaveTextContent('Originally');
  });

  it('cancels with a reason, tells staff every order is refunded, and keeps the reason in the log', async () => {
    const { user, run } = await openShort();
    await user.click(within(run).getByRole('button', { name: 'Cancel run' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this run?' });
    expect(dialog).toHaveTextContent('All 5 orders on this run are refunded in full, delivery fee included');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel run and refund' }));
    expect(await within(dialog).findByText('Enter a reason.')).toBeInTheDocument();
    await user.type(within(dialog).getByRole('textbox', { name: 'Reason (customers are shown it)' }), 'Tropical storm warning');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel run and refund' }));
    expect(await screen.findByText('Run cancelled; the orders are being refunded')).toBeInTheDocument();
    const outlook = await screen.findByTestId('run-outlook');
    expect(outlook).toHaveAttribute('data-kind', 'CANCELLED');
    expect(outlook).toHaveTextContent('Its 5 orders were refunded in full, delivery fee included');
    expect(outlook).toHaveTextContent('Reason: Tropical storm warning');
    expect(await screen.findByTestId('run-log-CANCEL')).toHaveTextContent('Cancelled and refunded');
    expect(screen.getByTestId('timeline-cancelled')).toBeInTheDocument();
    expect(screen.queryByTestId('run-actions')).not.toBeInTheDocument();
    expect(screen.getByTestId('run-no-actions')).toBeInTheDocument();
    const orders = within(screen.getByTestId('run-orders'));
    expect(orders.getAllByText('Cancelled and refunded').length).toBeGreaterThan(0);
  });
});

describe('a new run', () => {
  const openForm = async () => {
    const user = userEvent.setup();
    await renderScreen(<RunsView />);
    await user.click(await screen.findByRole('button', { name: 'New run' }));
    const form = await screen.findByTestId('run-form');
    return { user, form };
  };

  it('takes the area from the chosen location and shows the break-even next to the minimum', async () => {
    const { user, form } = await openForm();
    const area = within(form).getByTestId('run-form-area');
    expect(area).toHaveTextContent('15 miles around Lubbock');
    expect(area).toHaveTextContent('Times are in America/Chicago.');
    await user.selectOptions(within(form).getByRole('combobox', { name: 'Location' }), 'Amarillo');
    expect(within(form).getByTestId('run-form-area')).toHaveTextContent('The delivery zone drawn for Amarillo');
    expect(within(form).getByLabelText('Minimum orders')).toHaveValue('12');
    const panel = await within(form).findByTestId('run-break-even');
    expect(within(panel).getByTestId('run-break-even-line')).toHaveTextContent(/Break-even at \d+ orders/);
    expect(within(panel).getByTestId('run-break-even-verdict')).toHaveTextContent('below break-even');
    const fee = within(form).getByRole('radio', { name: /Use the delivery fee rule/ });
    expect(fee).toBeChecked();
    await user.clear(within(form).getByLabelText('Minimum orders'));
    await user.type(within(form).getByLabelText('Minimum orders'), '90');
    await waitFor(() => expect(within(form).getByTestId('run-break-even-verdict')).toHaveTextContent('reaches break-even'));
  });

  it('refuses what cannot be scheduled field by field, then schedules a draft and moves it to announced and open', async () => {
    const { user, form } = await openForm();
    const cutoff = within(form).getByLabelText('Orders close');
    await user.clear(cutoff);
    await user.type(cutoff, '2026-10-17T11:00');
    await user.clear(within(form).getByLabelText('Minimum orders'));
    await user.type(within(form).getByLabelText('Minimum orders'), 'lots');
    await user.click(within(form).getByRole('button', { name: 'Create draft' }));
    expect(await within(form).findByText('Orders have to close before delivery starts')).toBeInTheDocument();
    expect(within(form).getByText('Enter a whole number')).toBeInTheDocument();
    await user.clear(cutoff);
    await user.type(cutoff, '2026-10-14T20:00');
    await user.clear(within(form).getByLabelText('Minimum orders'));
    await user.type(within(form).getByLabelText('Minimum orders'), '15');
    await user.click(within(form).getByRole('radio', { name: 'Set a fee for this run' }));
    await user.type(within(form).getByLabelText('Fee per order (dollars)'), '8.99');
    await user.type(within(form).getByLabelText('Notes'), 'Bring the cold bags');
    await user.click(within(form).getByRole('button', { name: 'Create draft' }));
    expect(await screen.findByText('Run scheduled as a draft')).toBeInTheDocument();
    const run = await screen.findByTestId('run-sheet');
    expect(within(run).getByTestId('run-sheet-status')).toHaveTextContent('Draft');
    expect(within(run).getByTestId('run-details')).toHaveTextContent('$8.99 per order');
    expect(within(run).getByTestId('run-details')).toHaveTextContent('15 miles around Lubbock');
    expect(within(run).getByTestId('run-details')).toHaveTextContent('Bring the cold bags');
    expect(within(run).getByTestId('run-counts')).toHaveTextContent('0 of 15 orders');
    await user.click(within(run).getByRole('button', { name: 'Announce' }));
    await waitFor(() => expect(screen.getByTestId('run-sheet-status')).toHaveTextContent('Announced'));
    await user.click(screen.getByRole('button', { name: 'Open for orders' }));
    await waitFor(() => expect(screen.getByTestId('run-sheet-status')).toHaveTextContent('Open'));
    expect(screen.getByTestId('run-outlook')).toHaveAttribute('data-kind', 'FILLING');
    expect((await mockApi().runs.runs({ locationId: '4b2f9d3a-0000-4000-8000-00000000000a', status: ['OPEN'] })).some((r) => r.minOrders === 15)).toBe(true);
  });

  it('keeps Save off on an edit until something changed, and locks the fee and cutoff of an open run', async () => {
    const user = userEvent.setup();
    const created = await mockApi().runs.createRun({
      locationId: '4b2f9d3a-0000-4000-8000-00000000000a',
      windowStart: '2026-10-17T15:00:00Z',
      windowEnd: '2026-10-17T19:00:00Z',
      cutoffAt: '2026-10-15T01:00:00Z',
      minOrders: 12
    });
    await renderScreen(<RunsView />);
    await screen.findByTestId('run-list');
    await user.click((await screen.findAllByRole('button', { name: /Open the Lubbock run/ }))[0] as HTMLElement);
    const run = await screen.findByTestId('run-sheet');
    await user.click(await within(run).findByRole('button', { name: 'Edit' }));
    const form = await screen.findByTestId('run-form');
    const save = within(form).getByRole('button', { name: 'Save changes' });
    expect(save).toBeDisabled();
    expect(within(form).queryByRole('combobox', { name: 'Location' })).not.toBeInTheDocument();
    await user.clear(within(form).getByLabelText('Minimum orders'));
    await user.type(within(form).getByLabelText('Minimum orders'), '14');
    expect(save).toBeEnabled();
    await user.clear(within(form).getByLabelText('Minimum orders'));
    await user.type(within(form).getByLabelText('Minimum orders'), '12');
    expect(save).toBeDisabled();
    await user.type(within(form).getByLabelText('Notes'), 'Gate code 4417');
    await user.click(save);
    expect(await screen.findByText('Run saved')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('run-details')).toHaveTextContent('Gate code 4417'));
    expect((await mockApi().runs.run(created.id)).notes).toBe('Gate code 4417');

    await mockApi().runs.openRun(created.id);
    await user.click(await within(screen.getByTestId('run-actions')).findByRole('button', { name: 'Edit' }));
    const open = await screen.findByTestId('run-form');
    expect(within(open).getByLabelText('Orders close')).toBeDisabled();
    expect(within(open).getByRole('radio', { name: 'Set a fee for this run' })).toBeDisabled();
    expect(within(open).getByRole('combobox', { name: 'Drop-off' })).toBeDisabled();
    expect(within(open).getAllByText('An open run keeps its fee, cutoff and drop-off. Extend the cutoff instead.').length).toBeGreaterThan(0);
  });
});

describe('without the right to manage pre-orders', () => {
  it('lets Finance read every run and change none', async () => {
    asRoles('platform.finance');
    const user = userEvent.setup();
    await renderScreen(<RunsView />);
    const newRun = await screen.findByRole('button', { name: 'New run' });
    expect(newRun).toBeDisabled();
    expect(newRun).toHaveAttribute('title', 'Scheduling runs needs pre-order management access.');
    await user.click((await screen.findAllByRole('button', { name: /Open the Lubbock run/ }))[0] as HTMLElement);
    const run = await screen.findByTestId('run-sheet');
    expect(within(run).getByTestId('run-read-only')).toHaveTextContent('Changing runs needs pre-order management access.');
    expect(within(run).queryByTestId('run-actions')).not.toBeInTheDocument();
    expect(within(run).queryByRole('button', { name: /Cancel run|Extend cutoff|Confirm/ })).not.toBeInTheDocument();
    expect(within(run).getByTestId('run-log')).toBeInTheDocument();
    expect(within(run).getByTestId('run-orders')).toBeInTheDocument();
  });
});
