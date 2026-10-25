import { getSellerDb, MOCK_RUN_AMARILLO, MOCK_RUN_LUBBOCK, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { RunPickupDetailView, RunPickupsView } from '@/components/orders/RunPickups';

import { asRole } from './mock-api';
import { renderScreen } from './render';

/* Delivery run pick lists (S264) in the Seller Center, against the seller mocks. */
describe('Delivery run pick lists', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('lists the runs that collect from the seller with the pickup time and the counts', async () => {
    await renderScreen(<RunPickupsView />);
    const runs = await screen.findAllByTestId('run-pickup');
    expect(runs).toHaveLength(2);
    const lubbock = runs[0] as HTMLElement;
    expect(within(lubbock).getByTestId('run-pickup-time')).toHaveTextContent(/Driver collects/);
    expect(within(lubbock).getByTestId('run-pickup-time')).toHaveTextContent('Delivered to customers’ doors');
    expect(lubbock).toHaveTextContent('1 of 3 orders');
    expect(runs[1]).toHaveTextContent('Customers collect at a pickup point');
    expect(screen.getByRole('link', { name: 'Open the pick list for the Lubbock run' })).toHaveAttribute('href', `/orders/run-pickups/${MOCK_RUN_LUBBOCK}`);
    expect(screen.getByText('Lubbock, TX run')).toBeInTheDocument();
    expect(screen.getByText('Driver collecting')).toBeInTheDocument();
    expect(screen.getByText('Pack for pickup')).toBeInTheDocument();
  });

  it('says so when no run needs the seller yet', async () => {
    const db = getSellerDb();
    Object.keys(db.runPickups).forEach((k) => (db.runPickups[k] = []));
    await renderScreen(<RunPickupsView />);
    expect(await screen.findByText('No delivery runs need your items yet')).toBeInTheDocument();
  });

  it('shows one run: the total per product, then a bag per order with what is collected', async () => {
    await renderScreen(<RunPickupDetailView runId={MOCK_RUN_LUBBOCK} />);
    const table = await screen.findByTestId('run-pick-list');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((r) => r.textContent)).toEqual(['Gari 1kgATGARI00013', 'Palm oil 1LATPALM00023', 'Suya spice 200gATSPICE0033']);
    const bags = screen.getAllByTestId('run-bag');
    expect(bags).toHaveLength(3);
    expect(bags[0]).toHaveTextContent('Order LBK-1001');
    expect(bags[0]).toHaveTextContent('Collected');
    expect(bags[1]).toHaveTextContent('Waiting for the driver');
    expect(bags[2]).toHaveTextContent('3 ×');
    expect(screen.getByText('Driver collecting')).toBeInTheDocument();
  });

  it('tells a seller to pack before the pickup and prints the list', async () => {
    const print = vi.fn();
    vi.stubGlobal('print', print);
    const user = userEvent.setup();
    await renderScreen(<RunPickupDetailView runId={MOCK_RUN_AMARILLO} />);
    expect(await screen.findByText('Pack before the driver comes')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Print' }));
    await waitFor(() => expect(print).toHaveBeenCalledTimes(1));
    vi.unstubAllGlobals();
  });

  it('answers a run that is not the seller’s with the error state', async () => {
    await renderScreen(<RunPickupDetailView runId="00000000-0000-4000-8000-000000000000" />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
