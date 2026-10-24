import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { PreordersView } from '@/components/preorders/PreordersView';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

describe('Pre-orders: minimum, decisions and refunds (ADR 0005 §4)', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
    asRoles('platform.operations_manager');
  });

  it('files a window that closed below its minimum under Needs a decision', async () => {
    nav.set('/preorders?status=decision');
    await renderScreen(<PreordersView screen={{ kind: 'list' }} />);
    await waitFor(() => expect(screen.getAllByTestId('preorder-row')).toHaveLength(1));
    expect(screen.getByRole('tab', { name: /Needs a decision/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: /Needs a decision/ })).toHaveAttribute('href', '/preorders?status=decision');
    expect(screen.queryByTestId('decision-notice')).not.toBeInTheDocument();
  });

  it('shows the minimum on the wizard and takes the platform default when left blank', async () => {
    nav.set('/preorders/lbk-7/edit');
    await renderScreen(<PreordersView screen={{ kind: 'edit', id: 'lbk-7' }} />);
    const field = await screen.findByRole('textbox', { name: /^Minimum orders/ });
    expect(field).toHaveValue('100');
    const user = userEvent.setup();
    await user.clear(field);
    await user.type(field, 'x');
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('Enter the minimum as a whole number from 0 to 100000.')).toBeInTheDocument();
    await user.clear(field);
    expect(field).toHaveAttribute('placeholder', 'Default 10');
  });

  it('lets a manager cancel a pre-order below its minimum only with a reason, then follows the refunds', async () => {
    const user = userEvent.setup();
    nav.set('/preorders/ode-1');
    await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'ode-1' }} />);
    const banner = await screen.findByTestId('decision-banner');
    expect(banner).toHaveTextContent('This pre-order closed below its minimum');
    expect(banner).toHaveTextContent(/It closed with \d+ of the 10 orders it needs/);
    expect(await screen.findByTestId('detail-status')).toHaveTextContent('Needs a decision');
    expect(await screen.findByTestId('minimum-line')).toHaveTextContent(/\d+ of 10 orders needed/);
    expect(await screen.findByTestId('decision-log')).toHaveTextContent('Closed below minimum');

    await user.click(within(banner).getByRole('button', { name: 'Cancel and refund everyone' }));
    const dialog = await screen.findByRole('dialog', { name: /^Cancel Odessa Pre-order #1\?/ });
    await user.click(within(dialog).getByRole('button', { name: 'Cancel pre-order and refund' }));
    expect(await within(dialog).findByText('Say why: the reason is logged with your name.')).toBeInTheDocument();
    expect(screen.getByTestId('detail-status')).toHaveTextContent('Needs a decision');

    await user.type(within(dialog).getByRole('textbox', { name: /^Reason/ }), 'Not enough orders to make the trip');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel pre-order and refund' }));
    await waitFor(() => expect(screen.getByTestId('detail-status')).toHaveTextContent('Cancelled'));
    expect(screen.queryByTestId('decision-banner')).not.toBeInTheDocument();
    expect(await screen.findByText(/Odessa Pre-order #1 cancelled; refunds started/)).toBeInTheDocument();
    const progress = await screen.findByTestId('refund-progress');
    expect(progress).toHaveTextContent('Refund progress');
    expect(progress).toHaveTextContent(/Refunding · \d+/);
    const log = await screen.findByTestId('decision-log');
    await waitFor(() => expect(log).toHaveTextContent('Not enough orders to make the trip'));
    expect(log).toHaveTextContent('Cancelled');
  });

  it('proceeds below the minimum with a reason', async () => {
    const user = userEvent.setup();
    nav.set('/preorders/ode-1');
    await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'ode-1' }} />);
    await user.click(await screen.findByRole('button', { name: 'Go ahead anyway' }));
    const dialog = await screen.findByRole('dialog', { name: /^Go ahead with Odessa Pre-order #1\?/ });
    await user.type(within(dialog).getByRole('textbox', { name: /^Reason/ }), 'The orders still cover the drive');
    await user.click(within(dialog).getByRole('button', { name: 'Go ahead' }));
    await waitFor(() => expect(screen.getByTestId('detail-status')).toHaveTextContent('Closed · delivering'));
    expect(screen.queryByTestId('decision-banner')).not.toBeInTheDocument();
  });

  it('offers closing early once the minimum is met, and cancelling a live pre-order', async () => {
    const user = userEvent.setup();
    nav.set('/preorders/lbk-7');
    await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'lbk-7' }} />);
    expect(await screen.findByTestId('minimum-line')).toHaveTextContent('Minimum of 100 orders met (140 so far)');
    expect(screen.getByRole('button', { name: 'Cancel pre-order' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Close early' }));
    const dialog = await screen.findByRole('dialog', { name: /^Close Lubbock Pre-order #7 early\?/ });
    await user.type(within(dialog).getByRole('textbox', { name: /^Reason/ }), 'Seller lots are gone');
    await user.click(within(dialog).getByRole('button', { name: 'Close now' }));
    await waitFor(() => expect(screen.getByTestId('detail-status')).toHaveTextContent('Closed · delivering'));
    expect(screen.queryByRole('button', { name: 'Close early' })).not.toBeInTheDocument();
  });

  it('shows each order’s refund status on the orders tab of a cancelled pre-order', async () => {
    const user = userEvent.setup();
    nav.set('/preorders/lbk-7');
    await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'lbk-7' }} />);
    await user.click(await screen.findByRole('button', { name: 'Cancel pre-order' }));
    const dialog = await screen.findByRole('dialog', { name: /^Cancel Lubbock Pre-order #7\?/ });
    await user.type(within(dialog).getByRole('textbox', { name: /^Reason/ }), 'Seller stock fell through');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel pre-order and refund' }));
    await waitFor(() => expect(screen.getByTestId('detail-status')).toHaveTextContent('Cancelled'));
    nav.set('/preorders/lbk-7?tab=orders');
    await waitFor(() => expect(screen.getAllByTestId('order-row').length).toBeGreaterThan(0));
    await waitFor(() => expect(screen.getAllByTestId('refund-PENDING').length).toBeGreaterThan(0));
  });

  it('hides the decisions from people who cannot manage pre-orders, but they can read the outcome', async () => {
    asRoles('platform.finance');
    nav.set('/preorders/ode-1');
    await renderScreen(<PreordersView screen={{ kind: 'detail', id: 'ode-1' }} />);
    const banner = await screen.findByTestId('decision-banner');
    expect(within(banner).queryByRole('button')).not.toBeInTheDocument();
    expect(banner).toHaveTextContent('Only pre-order managers can decide.');
    expect(screen.queryByRole('button', { name: 'Cancel pre-order' })).not.toBeInTheDocument();
    expect(await screen.findByTestId('decision-log')).toBeInTheDocument();
  });
});
