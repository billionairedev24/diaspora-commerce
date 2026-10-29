import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { nextStep, RunsPanel } from '@/components/dispatch/RunsPanel';

import { asRoles } from './mock-api';
import { renderScreen } from './render';

/* Delivery runs in dispatch (S264), against the staff mocks. */
describe('dispatch: delivery runs', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
  });

  it('knows the step after each lifecycle status', () => {
    expect(nextStep({ runStatus: 'CONFIRMED' })).toBe('COLLECTING');
    expect(nextStep({ runStatus: 'COLLECTING' })).toBe('OUT_FOR_DELIVERY');
    expect(nextStep({ runStatus: 'OUT_FOR_DELIVERY' })).toBe('COMPLETED');
    expect(nextStep({ runStatus: 'COMPLETED' })).toBeNull();
    expect(nextStep({ runStatus: 'CANCELLED' })).toBeNull();
    expect(nextStep({ runStatus: null })).toBeNull();
  });

  it('lists the runs and opens one with seller pickups before the drops and what each order is doing', async () => {
    asRoles('platform.dispatcher');
    const user = userEvent.setup();
    await renderScreen(<RunsPanel />);
    const rows = await screen.findAllByTestId('run-row');
    expect(rows).toHaveLength(3);
    await user.click(screen.getByRole('button', { name: 'Open the Lubbock run' }));
    const detail = await screen.findByTestId('run-detail');
    expect(within(detail).getByText(/Collecting from sellers/)).toBeInTheDocument();
    const stops = within(detail).getAllByTestId('run-stop');
    expect(stops.map((s) => s.getAttribute('data-kind'))).toEqual(['PICKUP', 'PICKUP', 'DROPOFF', 'DROPOFF']);
    expect(stops[2]).toHaveTextContent('LBK-1001');
    expect(stops[2]).toHaveTextContent('Customer’s code needed');
    expect(within(detail).getByTestId('run-progress')).toHaveTextContent('1 of 2');
    expect(within(detail).getByTestId('run-orders')).toHaveTextContent('2 orders: 1 waiting, 1 collected');
  }, 30_000);

  it('plans a run whose planning could not finish and shows the pickup point it delivers to', async () => {
    asRoles('platform.dispatcher');
    const user = userEvent.setup();
    await renderScreen(<RunsPanel />);
    await user.click(await screen.findByRole('button', { name: 'Open the Amarillo run' }));
    const detail = await screen.findByTestId('run-detail');
    expect(within(detail).getByText('No depot is configured for this market')).toBeInTheDocument();
    expect(within(detail).getByTestId('run-pickup-point')).toHaveTextContent('Gateway Mall counter');
    expect(within(detail).getByText('No routes yet.')).toBeInTheDocument();
    await user.click(within(detail).getByRole('button', { name: 'Plan routes' }));
    await waitFor(() => expect(screen.queryByText('No depot is configured for this market')).not.toBeInTheDocument());
    const stops = await screen.findAllByTestId('run-stop');
    expect(stops[0]).toHaveAttribute('data-kind', 'PICKUP');
    expect(stops.slice(1).every((s) => s.getAttribute('data-kind') === 'DROPOFF')).toBe(true);
    expect(within(screen.getByTestId('run-detail')).getByRole('button', { name: 'Plan again' })).toBeInTheDocument();
  }, 30_000);

  it('moves a run by hand only after a confirmation', async () => {
    asRoles('platform.dispatcher');
    const user = userEvent.setup();
    await renderScreen(<RunsPanel />);
    await user.click(await screen.findByRole('button', { name: 'Open the Lubbock run' }));
    await user.click(await screen.findByRole('button', { name: 'Move to: Out for delivery' }));
    expect(screen.getByText('Move this run to “Out for delivery”?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByText('Move this run to “Out for delivery”?')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Move to: Out for delivery' }));
    await user.click(screen.getByRole('button', { name: 'Yes, move it' }));
    await waitFor(() => expect(within(screen.getByTestId('run-detail')).getByRole('button', { name: 'Move to: Completed' })).toBeInTheDocument());
  }, 30_000);

  it('is read-only without delivery:dispatch edit access', async () => {
    asRoles('platform.customer_support');
    const user = userEvent.setup();
    await renderScreen(<RunsPanel />);
    await user.click(await screen.findByRole('button', { name: 'Open the Amarillo run' }));
    const detail = await screen.findByTestId('run-detail');
    expect(within(detail).queryByRole('button', { name: 'Plan routes' })).not.toBeInTheDocument();
    expect(within(detail).queryByRole('button', { name: /^Move to/ })).not.toBeInTheDocument();
  }, 30_000);
});
