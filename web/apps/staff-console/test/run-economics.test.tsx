import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { PreordersView } from '@/components/preorders/PreordersView';
import { AdvertorialsPanel } from '@/components/runs/AdvertorialsPanel';
import { EconomicsSettingsView } from '@/components/runs/EconomicsSettingsView';
import { RunsView } from '@/components/runs/RunsView';
import { SETTINGS_SECTIONS } from '@/lib/settings-sections';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const NOW = new Date('2026-10-10T15:00:00Z');

describe('Run economics settings (S265)', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles(null);
  });

  it('is a Money section of Settings hosted by the pre-orders module', () => {
    expect(SETTINGS_SECTIONS.find((s) => s.key === 'run-economics')).toMatchObject({ module: 'preorders', group: 'money', labelKey: 'st.runs.settings.label' });
  });

  it('starts on the deployment defaults and shows what editing would change for a location', async () => {
    const user = userEvent.setup();
    await renderScreen(<EconomicsSettingsView />);
    expect(await screen.findByRole('textbox', { name: 'Driver cost per hour' })).toHaveValue('25.00');
    expect(screen.getByRole('textbox', { name: 'Vehicle cost per mile' })).toHaveValue('0.70');
    expect(screen.getByRole('textbox', { name: 'Minutes per doorstep drop' })).toHaveValue('6');
    expect(screen.getByRole('checkbox', { name: /Count the drive back/ })).toBeChecked();
    expect(screen.getByTestId('economics-meta')).toHaveTextContent('Nobody has changed these yet');
    expect(screen.getByRole('button', { name: 'Save cost model' })).toBeDisabled();
    const effect = await screen.findByTestId('runs-effect');
    await waitFor(() => expect(within(effect).getByTestId('effect-saved')).toHaveTextContent(/Break-even at \d+ orders/));
    const before = within(effect).getByTestId('effect-saved').textContent;
    expect(within(effect).getByTestId('effect-draft')).toHaveTextContent(before ?? '');
    await user.clear(screen.getByRole('textbox', { name: 'Driver cost per hour' }));
    await user.type(screen.getByRole('textbox', { name: 'Driver cost per hour' }), '50');
    expect(within(effect).getByTestId('effect-draft')).not.toHaveTextContent(before ?? '');
    expect(within(effect).getByTestId('effect-saved')).toHaveTextContent(before ?? '');
  });

  it('saves a version only with a reason, then shows who, when, why and the numbers before and after', async () => {
    const user = userEvent.setup();
    await renderScreen(<EconomicsSettingsView />);
    const hour = await screen.findByRole('textbox', { name: 'Driver cost per hour' });
    await user.clear(hour);
    await user.type(hour, '28');
    await user.click(screen.getByRole('button', { name: 'Save cost model' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await within(dialog).findByText('Enter a reason.')).toBeInTheDocument();
    await user.type(within(dialog).getByRole('textbox', { name: 'Reason for the change' }), 'Insurance went up');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Cost model saved')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const meta = await screen.findByText(/Version 1, changed/);
    expect(meta).toBeInTheDocument();
    expect(screen.getByTestId('economics-meta')).toHaveTextContent('Reason: Insurance went up');
    const history = within(await screen.findByRole('list', { name: 'Change history' }));
    expect(history.getByText(/Before: \$25\.00\/h/)).toBeInTheDocument();
    expect(history.getByText(/After: \$28\.00\/h/)).toBeInTheDocument();
    expect(history.getByText('Reason: Insurance went up')).toBeInTheDocument();
    expect((await mockApi().runs.settings()).driverCostPerHourMinor).toBe(2800);
  });

  it('saves the platform fee estimate with a reason and shows it in the history, then prices the window\'s line with it', async () => {
    const user = userEvent.setup();
    await renderScreen(<EconomicsSettingsView />);
    const fee = await screen.findByRole('textbox', { name: 'Platform fee on goods, per order' });
    expect(fee).toHaveValue('0.00');
    await user.clear(fee);
    await user.type(fee, '2.50');
    await user.click(screen.getByRole('button', { name: 'Save cost model' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox', { name: 'Reason for the change' }), 'Commission averages $2.50');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Cost model saved')).toBeInTheDocument();
    const history = within(await screen.findByRole('list', { name: 'Change history' }));
    expect(history.getByText(/After: .*platform fee \$2\.50 per order/)).toBeInTheDocument();
    expect(history.getByText(/Before: \$25\.00\/h/)).not.toHaveTextContent('platform fee');
    expect((await mockApi().runs.settings()).platformFeePerOrderMinor).toBe(250);
    const window = await mockApi().runs.windowEconomics('lbk-7');
    expect(window.platformFees).toMatchObject({ source: 'ESTIMATE', commissionMinor: window.orders * 250 });
  });

  it('refuses a platform fee that is not an amount', async () => {
    const user = userEvent.setup();
    await renderScreen(<EconomicsSettingsView />);
    const fee = await screen.findByRole('textbox', { name: 'Platform fee on goods, per order' });
    await user.type(fee, 'x');
    expect(await screen.findByText(/Use a value from/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save cost model' })).toBeDisabled();
  });

  it('keeps Save off and says what is wrong with a number', async () => {
    const user = userEvent.setup();
    await renderScreen(<EconomicsSettingsView />);
    const stop = await screen.findByRole('textbox', { name: 'Minutes per doorstep drop' });
    await user.clear(stop);
    await user.type(stop, 'six');
    expect(await screen.findByText('Enter minutes like 45 or 6.5')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save cost model' })).toBeDisabled();
    expect(screen.getByTestId('effect-draft')).toHaveTextContent('Fix the numbers above to see this');
    await user.clear(stop);
    await user.type(stop, '2000');
    expect(await screen.findByText('Use a value from 0 to 1440')).toBeInTheDocument();
  });

  it('lets viewers read the numbers but not change them', async () => {
    asRoles('platform.finance');
    await renderScreen(<EconomicsSettingsView />);
    expect(await screen.findByTestId('economics-read-only')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Driver cost per hour' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save cost model' })).not.toBeInTheDocument();
  });
});

describe('Break-even and window economics (S265)', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.operations_manager');
  });

  it('shows the break-even beside the minimum while a window is edited, and whether the minimum reaches it', async () => {
    const user = userEvent.setup();
    nav.set('/preorders/lbk-7/edit');
    await renderScreen(<PreordersView screen={{ kind: 'edit', id: 'lbk-7' }} />);
    const panel = await screen.findByTestId('break-even');
    expect(within(panel).getByTestId('break-even-line')).toHaveTextContent(/Break-even at \d+ orders/);
    expect(within(panel).getByTestId('break-even-verdict')).toHaveTextContent('The minimum of 100 orders reaches break-even.');
    const min = await screen.findByRole('textbox', { name: /^Minimum orders/ });
    await user.clear(min);
    await user.type(min, '3');
    await waitFor(() => expect(screen.getByTestId('break-even-verdict')).toHaveTextContent(/The minimum of 3 orders is below break-even: a run with only the minimum would lose \$/), { timeout: 3000 });
  });

  it('shows the margin at capacity beside the break-even when the capacity is known', async () => {
    nav.set('/preorders/lbk-7/edit');
    await renderScreen(<PreordersView screen={{ kind: 'edit', id: 'lbk-7' }} />);
    const panel = await screen.findByTestId('break-even');
    const line = await within(panel).findByTestId('break-even-capacity');
    // Lubbock is 148 miles out: even a full run at the 7.99 fee loses money, and the panel says so.
    expect(line).toHaveTextContent(/Even when the run is full \(40 orders\) it would lose \$[\d,.]+\./);
    expect(within(panel).getByTestId('pnl-capacity')).toHaveTextContent('At capacity');
  });

  it('shows the P&L of a window on its overview', async () => {
    nav.set('/preorders/lbk-7');
    await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'lbk-7' }} />);
    const card = await screen.findByTestId('window-economics');
    await waitFor(() => expect(within(card).getByTestId('window-orders')).toHaveTextContent(/^\d+ of 100 minimum$/));
    expect(within(card).getByTestId('pnl-orders')).toHaveTextContent('At the orders so far');
    expect(within(card).getByTestId('pnl-model')).toHaveTextContent(/Cost \$[\d,.]+ per run plus \$[\d.]+ per order, against a \$7\.99 delivery fee/);
    // no estimate configured and nothing reported: the line says so instead of showing a zero
    expect(within(card).getByTestId('window-platform-fees-amount')).toHaveTextContent('No figure yet');
    expect(within(card).getByTestId('window-platform-fees-source')).toHaveTextContent('Set a platform fee per order in Economics settings');
  });

  it('reports a cancelled window as orders refunded in full', async () => {
    await mockApi().preorders.cancel('lbk-7', 'No driver available');
    nav.set('/preorders/lbk-7');
    await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'lbk-7' }} />);
    const refunds = await screen.findByTestId('window-refunds');
    expect(refunds).toHaveTextContent(/Orders refunded\s*1\d\d/);
    expect(screen.getByText(/Every order was refunded in full, the delivery fee included/)).toBeInTheDocument();
  });
});

describe('Delivery runs (S265)', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.operations_manager');
  });

  it('shows the orders of every run of a location with subscribers and growth', async () => {
    await renderScreen(<RunsView />);
    const history = await screen.findByTestId('location-history');
    const table = within(history).getByRole('table', { name: 'Runs of this location' });
    expect(within(table).getAllByRole('row')).toHaveLength(5);
    expect(within(table).getByTestId('run-row-CANCELLED')).toHaveTextContent('7 orders');
    expect(within(table).getByTestId('run-row-CANCELLED')).toHaveTextContent('Not met (12)');
    expect(within(table).getAllByTestId('run-row-COMPLETED')).toHaveLength(2);
    const counts = within(history).getByTestId('subscriber-counts');
    expect(counts).toHaveTextContent('Active164');
    expect(counts).toHaveTextContent('Waiting to confirm9');
    expect(within(history).getByRole('region', { name: 'Subscriber growth by month (joined minus left)' })).toBeInTheDocument();
    expect(within(history).getByRole('region', { name: 'Orders per run' })).toBeInTheDocument();
  });

  it('trends orders and margin per run and shows each run\'s cost, margin and whether its minimum covered break-even', async () => {
    await renderScreen(<RunsView />);
    const history = await screen.findByTestId('location-history');
    const trend = within(history).getByTestId('run-trend');
    expect(within(trend).getByRole('region', { name: 'Orders per run' })).toBeInTheDocument();
    expect(within(trend).getByRole('region', { name: 'Margin per run (delivery fees kept minus cost)' })).toBeInTheDocument();
    const table = within(history).getByRole('table', { name: 'Runs of this location' });
    const done = within(table).getAllByTestId('run-row-COMPLETED')[0] as HTMLElement;
    expect(within(done).getByTestId('history-cost')).toHaveTextContent(/^\$[\d,]+\.\d\d$/);
    expect(within(done).getByTestId('history-margin')).toHaveTextContent(/\$[\d,]+\.\d\d/);
    expect(within(done).getByTestId('history-break-even')).toHaveTextContent(/^\d+ orders$/);
    expect(within(done).getByTestId('history-covers')).toHaveTextContent('Minimum below break-even');
    const cancelled = within(table).getByTestId('run-row-CANCELLED');
    expect(within(cancelled).getByTestId('history-cost')).toHaveTextContent('$0.00');
    expect(cancelled).toHaveTextContent('$55.93 in fees');
  });

  it('lists the pre-orders a location delivered and its subscriber growth net of unsubscribes', async () => {
    await renderScreen(<RunsView />);
    const windows = await screen.findByTestId('location-windows');
    expect(within(windows).getByRole('table', { name: 'Pre-orders of this location' })).toBeInTheDocument();
    expect(within(windows).getByTestId('window-row-LBK-7')).toHaveTextContent(/Lubbock Pre-order #7/);
    const growth = await screen.findByTestId('growth-table');
    const oct = within(growth).getByTestId('growth-2026-10');
    expect(oct).toHaveTextContent('164');
    expect(oct).toHaveTextContent('+148');
  });

  it('shows the P&L of a run before, after and when cancelled', async () => {
    const user = userEvent.setup();
    await renderScreen(<RunsView />);
    const history = await screen.findByTestId('location-history');
    const rows = within(history).getAllByRole('row').slice(1);
    const open = rows.find((r) => r.textContent?.includes('Open')) as HTMLElement;
    await user.click(within(open).getByRole('button'));
    const pnl = await screen.findByTestId('run-pnl');
    await waitFor(() => expect(within(pnl).getByTestId('run-pnl-phase')).toHaveTextContent('Estimate before the run'));
    expect(within(pnl).getByTestId('pnl-orders')).toHaveTextContent('At the orders so far');

    const done = rows.find((r) => r.textContent?.includes('Completed')) as HTMLElement;
    await user.click(within(done).getByRole('button'));
    await waitFor(() => expect(screen.getByTestId('run-pnl-phase')).toHaveTextContent('Result after the run'));
    expect(screen.getByTestId('pnl-orders')).toHaveTextContent('At the actual orders');
    // built from the run's real orders: fees by way of delivery, the refund and the break-even at the fee actually paid
    const orders = screen.getByTestId('run-pnl-orders');
    expect(within(orders).getByTestId('run-pnl-orders-refunded')).toHaveTextContent('1');
    expect(within(orders).getByTestId('run-pnl-orders-cancelled')).toHaveTextContent(/^\d+$/);
    expect(screen.getByTestId('run-pnl-fee-DOOR')).toHaveTextContent(/Door/);
    expect(screen.getByTestId('run-pnl-fee-PICKUP')).toHaveTextContent(/Pickup point/);
    expect(screen.getByTestId('run-pnl-fee-refunded')).toHaveTextContent('Handed back to customers');
    expect(screen.getByTestId('run-pnl-fee-kept')).toHaveTextContent('Delivery fees kept');
    expect(screen.getByTestId('run-pnl-cost-basis')).toHaveTextContent('Cost is an estimate');
    expect(screen.getByTestId('run-pnl-actual-break-even')).toHaveTextContent(/At the \$[\d.]+ an order actually paid, break-even is \d+ orders\./);
    // platform fees on goods: their own line, with where the figure comes from
    expect(screen.getByTestId('platform-fees-amount')).toHaveTextContent(/^\$[\d,]+\.\d\d$/);
    expect(screen.getByTestId('platform-fees-source')).toHaveTextContent(/Reported by payments for all \d+ orders/);
    expect(screen.getByTestId('platform-fees')).toHaveTextContent('It is not part of the delivery profit');
    expect(within(screen.getByTestId('run-pnl')).getByTestId('pnl-orders')).not.toHaveTextContent('Platform fees');

    const cancelled = rows.find((r) => r.textContent?.includes('Cancelled')) as HTMLElement;
    await user.click(within(cancelled).getByRole('button'));
    const note = await screen.findByTestId('run-pnl-cancelled');
    expect(note).toHaveTextContent('Every order was refunded in full, the delivery fee included');
    expect(note).toHaveTextContent('7 orders refunded, $55.93 of delivery fees handed back');
    expect(note).not.toHaveTextContent('estimated from the run');
    expect(note).toHaveTextContent('Below the minimum');
    expect(screen.queryByTestId('platform-fees')).not.toBeInTheDocument();
    expect(screen.queryByTestId('pnl-orders')).not.toBeInTheDocument();
  });

  it('switches location', async () => {
    const user = userEvent.setup();
    await renderScreen(<RunsView />);
    await screen.findByTestId('location-history');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Location' }), 'Amarillo');
    await waitFor(() => expect(screen.getByTestId('subscriber-counts')).toHaveTextContent('Active55'));
  });
});

describe('Advertorials (S263)', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.operations_manager');
  });

  it('lists the variants of a pre-order with what is showing now and their results', async () => {
    await renderScreen(<AdvertorialsPanel source="WINDOW" sourceId="lbk-7" />);
    const a = await screen.findByTestId('adv-A');
    const b = await screen.findByTestId('adv-B');
    expect(within(a).getByText('Showing now')).toBeInTheDocument();
    expect(within(a).getByTestId('adv-status-A')).toHaveTextContent('Live');
    expect(within(a).getByText('Follows the details until edited')).toBeInTheDocument();
    expect(within(b).getByTestId('adv-results-B')).toHaveTextContent('Views1,792');
    expect(within(b).getByTestId('adv-results-B')).toHaveTextContent('Click rate15.3%');
    expect(within(b).getByTestId('adv-results-B')).toHaveTextContent('Orders52');
  });

  it('generates from the details, edits, pauses, publishes again and ends one', async () => {
    const user = userEvent.setup();
    await renderScreen(<AdvertorialsPanel source="WINDOW" sourceId="lbk-6" />);
    expect(await screen.findByText('No advertorials yet')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Generate from the details' }));
    const card = await screen.findByTestId('adv-A');
    expect(within(card).getByTestId('adv-status-A')).toHaveTextContent('Live');
    expect(within(card).getByText('Follows the details until edited')).toBeInTheDocument();
    // Its pre-order is delivered, so nothing is shown to visitors.
    expect(within(card).queryByText('Showing now')).not.toBeInTheDocument();

    await user.click(within(card).getByRole('button', { name: 'Edit' }));
    const dialog = await screen.findByRole('dialog');
    const headline = within(dialog).getByRole('textbox', { name: 'Headline' });
    await user.clear(headline);
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await within(dialog).findByText('This is required.')).toBeInTheDocument();
    await user.type(headline, 'Last call for Lubbock');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const edited = await screen.findByTestId('adv-A');
    expect(edited).toHaveTextContent('Last call for Lubbock');
    expect(within(edited).queryByText('Follows the details until edited')).not.toBeInTheDocument();

    await user.click(within(edited).getByRole('button', { name: 'Pause' }));
    await waitFor(() => expect(screen.getByTestId('adv-status-A')).toHaveTextContent('Paused'));
    await user.click(within(screen.getByTestId('adv-A')).getByRole('button', { name: 'Publish' }));
    await waitFor(() => expect(screen.getByTestId('adv-status-A')).toHaveTextContent('Live'));
    await user.click(within(screen.getByTestId('adv-A')).getByRole('button', { name: 'End' }));
    await waitFor(() => expect(screen.getByTestId('adv-status-A')).toHaveTextContent('Ended'));
    expect(within(screen.getByTestId('adv-A')).queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument();
    expect(within(screen.getByTestId('adv-A')).getByText(/Ended by staff/)).toBeInTheDocument();
  });

  it('writes a new variant with the checks the BFF makes', async () => {
    const user = userEvent.setup();
    await renderScreen(<AdvertorialsPanel source="WINDOW" sourceId="lbk-7" />);
    await user.click(await screen.findByRole('button', { name: 'Write a variant' }));
    const dialog = await screen.findByRole('dialog', { name: 'Write a variant' });
    expect(within(dialog).getByRole('textbox', { name: 'Variant' })).toHaveValue('C');
    await user.type(within(dialog).getByRole('textbox', { name: 'Headline' }), 'Third idea');
    await user.type(within(dialog).getByRole('textbox', { name: 'Button label' }), 'Go');
    await user.clear(within(dialog).getByRole('textbox', { name: 'Weight' }));
    await user.type(within(dialog).getByRole('textbox', { name: 'Weight' }), '500');
    expect(await within(dialog).findByText('This is outside the allowed range.')).toBeInTheDocument();
    await user.clear(within(dialog).getByRole('textbox', { name: 'Weight' }));
    await user.type(within(dialog).getByRole('textbox', { name: 'Weight' }), '2');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByTestId('adv-C')).toHaveTextContent('Third idea');
    expect(screen.getByTestId('adv-C')).toHaveTextContent('Weight 2, priority 0');
  });

  it('shows viewers the results but no way to change them', async () => {
    asRoles('platform.finance');
    await renderScreen(<AdvertorialsPanel source="WINDOW" sourceId="lbk-7" />);
    await screen.findByTestId('adv-A');
    expect(screen.queryByRole('button', { name: 'Write a variant' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Pause' })).not.toBeInTheDocument();
  });
});
