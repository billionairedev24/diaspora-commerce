import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { DeliveryFeesView } from '@/components/fees/DeliveryFeesView';

import { asRoles } from './mock-api';
import { renderScreen } from './render';

const section = async (name: string) => within(await screen.findByRole('region', { name }));

describe('Delivery fees (ADR 0005 §3)', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
    asRoles(null);
  });

  it('shows the three methods for the currency with who last changed each and why', async () => {
    await renderScreen(<DeliveryFeesView />);
    const afro = await section('AfroTransact delivery');
    expect(afro.getByRole('textbox', { name: 'Band 1 up to (miles)' })).toHaveValue('100');
    expect(afro.getByRole('textbox', { name: 'Band 1 fee' })).toHaveValue('7.99');
    expect(afro.getByText('AfroTransact delivers up to 100 miles.')).toBeInTheDocument();
    expect(afro.getByText(/Version 1, changed .* by migration/)).toBeInTheDocument();
    const carrier = await section('Carrier shipping');
    expect(carrier.getByRole('textbox', { name: 'Fallback fee (no quote)' })).toHaveValue('5.99');
    expect(carrier.getByRole('textbox', { name: 'Free shipping above' })).toHaveValue('35.00');
    const pickup = await section('Pickup');
    expect(pickup.getByRole('textbox', { name: 'Pickup fee' })).toHaveValue('0.00');
  });

  it('adds distance bands, asks for a reason, then saves a new version that shows in the history', async () => {
    const user = userEvent.setup();
    await renderScreen(<DeliveryFeesView />);
    const afro = await section('AfroTransact delivery');
    const save = afro.getByRole('button', { name: 'Save AfroTransact delivery' });
    expect(save).toBeDisabled();
    await user.clear(afro.getByRole('textbox', { name: 'Band 1 up to (miles)' }));
    await user.type(afro.getByRole('textbox', { name: 'Band 1 up to (miles)' }), '25');
    await user.click(afro.getByRole('button', { name: 'Add a distance band' }));
    expect(afro.getByRole('textbox', { name: 'Band 2 up to (miles)' })).toHaveValue('50');
    await user.clear(afro.getByRole('textbox', { name: 'Band 2 up to (miles)' }));
    await user.type(afro.getByRole('textbox', { name: 'Band 2 up to (miles)' }), '100');
    await user.type(afro.getByRole('textbox', { name: 'Band 2 fee' }), '12.50');
    await user.click(save);
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Save fee' }));
    expect(await within(dialog).findByText('Enter a reason.')).toBeInTheDocument();
    await user.type(within(dialog).getByRole('textbox', { name: 'Reason for the change' }), 'Fuel costs rose');
    await user.click(within(dialog).getByRole('button', { name: 'Save fee' }));
    expect(await screen.findByText('Delivery fee saved')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const saved = await section('AfroTransact delivery');
    expect(await saved.findByText(/Version 2, changed/)).toBeInTheDocument();
    expect(saved.getByText('Reason: Fuel costs rose')).toBeInTheDocument();
    const history = within(await screen.findByRole('list', { name: 'Change history' }));
    expect(history.getByText(/AfroTransact delivery USD, version 2/)).toBeInTheDocument();
    expect(history.getByText(/Fuel costs rose/)).toBeInTheDocument();
  });

  it('keeps Save off and says what is wrong with a distance that does not grow', async () => {
    const user = userEvent.setup();
    await renderScreen(<DeliveryFeesView />);
    const afro = await section('AfroTransact delivery');
    await user.click(afro.getByRole('button', { name: 'Add a distance band' }));
    await user.clear(afro.getByRole('textbox', { name: 'Band 2 up to (miles)' }));
    await user.type(afro.getByRole('textbox', { name: 'Band 2 up to (miles)' }), '60');
    await user.type(afro.getByRole('textbox', { name: 'Band 2 fee' }), 'abc');
    expect(afro.getByText('Must be more than 100 miles.')).toBeInTheDocument();
    expect(afro.getByText('Enter an amount like 7.99.')).toBeInTheDocument();
    expect(afro.getByRole('button', { name: 'Save AfroTransact delivery' })).toBeDisabled();
    await user.click(afro.getByRole('button', { name: 'Discard changes' }));
    expect(afro.queryByRole('textbox', { name: 'Band 2 fee' })).not.toBeInTheDocument();
  });

  it('saves carrier and pickup fees with their own reason', async () => {
    const user = userEvent.setup();
    await renderScreen(<DeliveryFeesView />);
    const carrier = await section('Carrier shipping');
    await user.clear(carrier.getByRole('textbox', { name: 'Handling added to the quote' }));
    await user.type(carrier.getByRole('textbox', { name: 'Handling added to the quote' }), '1.50');
    await user.clear(carrier.getByRole('textbox', { name: 'Free shipping above' }));
    await user.click(carrier.getByRole('button', { name: 'Save Carrier shipping' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox', { name: 'Reason for the change' }), 'Carrier surcharge');
    await user.click(within(dialog).getByRole('button', { name: 'Save fee' }));
    const saved = await section('Carrier shipping');
    expect(await saved.findByText(/Version 2, changed/)).toBeInTheDocument();
    expect(saved.getByRole('textbox', { name: 'Free shipping above' })).toHaveValue('');
    const pickup = await section('Pickup');
    expect(pickup.getByText(/Version 1/)).toBeInTheDocument();
  });

  it('previews what a customer would pay, including beyond coverage', async () => {
    const user = userEvent.setup();
    await renderScreen(<DeliveryFeesView />);
    const tryIt = await section('Try it');
    await user.type(tryIt.getByRole('textbox', { name: 'Distance (miles)' }), '40');
    await user.click(tryIt.getByRole('button', { name: 'Show the fee' }));
    expect(await tryIt.findByText('The customer pays $7.99')).toBeInTheDocument();
    expect(tryIt.getByText(/AfroTransact delivery, rule version 1, rule/)).toBeInTheDocument();
    await user.clear(tryIt.getByRole('textbox', { name: 'Distance (miles)' }));
    await user.type(tryIt.getByRole('textbox', { name: 'Distance (miles)' }), '400');
    await user.click(tryIt.getByRole('button', { name: 'Show the fee' }));
    expect(await tryIt.findByText(/Not offered: this distance is beyond AfroTransact delivery/)).toBeInTheDocument();
  });

  it('adds a currency to set up its fees', async () => {
    const user = userEvent.setup();
    await renderScreen(<DeliveryFeesView />);
    await section('Pickup');
    await user.selectOptions(screen.getByLabelText('Add a currency'), 'GBP');
    await user.click(screen.getByRole('button', { name: 'Add' }));
    expect(screen.getByLabelText('Currency')).toHaveValue('GBP');
    const afro = await section('AfroTransact delivery');
    expect(afro.getByText('Not set in GBP yet.')).toBeInTheDocument();
  });

  it('is read-only for staff who cannot manage pre-orders', async () => {
    asRoles('platform.customer_support');
    await renderScreen(<DeliveryFeesView />);
    expect(await screen.findByTestId('fees-read-only')).toBeInTheDocument();
    const afro = await section('AfroTransact delivery');
    expect(afro.getByRole('textbox', { name: 'Band 1 fee' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /^Save / })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Add a currency')).not.toBeInTheDocument();
  });
});
