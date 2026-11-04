import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/*
 * The order sheet: AT-200400 has two packages, AfroTransact's (ready, by carrier) and Patel Grocers' (packed, seller
 * fulfilled). Staff set a status from one compact dropdown per shipment; every choice opens one small confirmation.
 */

const isShipmentStatus = (req: Request) => req.method === 'POST' && new URL(req.url).pathname.endsWith('/orders/AT-200400/actions/shipment-status');
const problem = (status: number, code: string, detail: string) =>
  new Response(JSON.stringify({ type: 'about:blank', title: code, status, code, detail }), { status, headers: { 'Content-Type': 'application/problem+json' } });

async function openOrder() {
  const user = userEvent.setup();
  nav.set('/orders?open=AT-200400');
  await renderScreen(<ResourceTable resource="orders" />);
  const sheet = await screen.findByRole('dialog', { name: 'Order AT-200400' });
  await within(sheet).findAllByTestId('shipment-row');
  return { user, sheet };
}
const rowOf = (sheet: HTMLElement, seller: string): HTMLElement => {
  const row = within(sheet)
    .getAllByTestId('shipment-row')
    .find((r) => within(r).queryByText(seller, { selector: 'strong' }));
  if (!row) throw new Error(`no shipment row for ${seller}`);
  return row;
};
const openMenu = async (user: ReturnType<typeof userEvent.setup>, row: HTMLElement, seller: string) => {
  await user.click(within(row).getByRole('button', { name: `Set status of ${seller}’s shipment` }));
  return screen.findByRole('menu');
};

describe('Order sheet: shipments', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-12T14:00:00Z'));
    asRoles(null);
  });
  afterEach(() => interceptStaff(null));

  it('lists each shipment as a flat row with the seller, items, how it travels and its current status', async () => {
    const { sheet } = await openOrder();
    const rows = within(sheet).getAllByTestId('shipment-row');
    expect(rows).toHaveLength(2);
    const afro = rowOf(sheet, 'AfroTransact');
    expect(within(afro).getByText('Ready')).toBeInTheDocument();
    expect(within(afro).getByText(/Jollof seasoning 200 g, Shea butter, raw 500 g/)).toBeInTheDocument();
    expect(within(afro).getByText('Packed by AfroTransact · Carrier')).toBeInTheDocument();
    expect(within(afro).getByText('Shipment 1 of 2')).toBeInTheDocument();
    const patel = rowOf(sheet, 'Patel Grocers');
    expect(within(patel).getByText('Packed')).toBeInTheDocument();
    expect(within(patel).getByText('Packed by the seller · Carrier')).toBeInTheDocument();
    // No sentence-long buttons any more.
    expect(within(sheet).queryByRole('button', { name: /Mark picked up/ })).toBeNull();
    expect(within(sheet).queryByRole('button', { name: /More options/ })).toBeNull();
  });

  it('lists the valid statuses and the unavailable ones with a short reason', async () => {
    const { user, sheet } = await openOrder();
    const menu = await openMenu(user, rowOf(sheet, 'AfroTransact'), 'AfroTransact');
    expect(within(menu).getByRole('menuitem', { name: 'Picked up' })).not.toHaveAttribute('aria-disabled', 'true');
    expect(within(menu).getByRole('menuitem', { name: 'Cancelled' })).not.toHaveAttribute('aria-disabled', 'true');
    const delivered = within(menu).getByRole('menuitem', { name: /Delivered/ });
    expect(delivered).toHaveAttribute('aria-disabled', 'true');
    expect(delivered).toHaveTextContent('Finish earlier steps first');
    await user.keyboard('{Escape}');

    const patelMenu = await openMenu(user, rowOf(sheet, 'Patel Grocers'), 'Patel Grocers');
    const ready = within(patelMenu).getByRole('menuitem', { name: /Ready/ });
    expect(ready).toHaveAttribute('aria-disabled', 'true');
    expect(ready).toHaveTextContent('Only the seller does this');
    expect(within(patelMenu).getByRole('menuitem', { name: 'Cancelled' })).not.toHaveAttribute('aria-disabled', 'true');
  });

  it('lists every status as unavailable for a role without the permission', async () => {
    asRoles('platform.customer_support');
    const { user, sheet } = await openOrder();
    const menu = await openMenu(user, rowOf(sheet, 'AfroTransact'), 'AfroTransact');
    const items = within(menu).getAllByRole('menuitem');
    expect(items.length).toBeGreaterThan(2);
    items.forEach((i) => {
      expect(i).toHaveAttribute('aria-disabled', 'true');
      expect(i).toHaveTextContent('No permission');
    });
  });

  it('confirms in one compact dialog with the verb on the button, then updates the row and says so', async () => {
    const posts: Request[] = [];
    interceptStaff(async (req, next) => {
      if (isShipmentStatus(req)) posts.push(req.clone());
      return next(req);
    });
    const { user, sheet } = await openOrder();
    const row = rowOf(sheet, 'AfroTransact');
    await user.click(within(await openMenu(user, row, 'AfroTransact')).getByRole('menuitem', { name: 'Picked up' }));

    const dialog = await screen.findByRole('dialog', { name: 'Set shipment to Picked up?' });
    expect(within(dialog).getByText('AT-200400 · AfroTransact · Ready to Picked up')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeVisible();
    // A step that needs no reason says the note is optional.
    expect(within(dialog).getByLabelText('Note (optional)')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Set to Picked up' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Set shipment to Picked up?' })).toBeNull());
    expect(posts).toHaveLength(1);
    expect(posts[0]?.headers.get('Idempotency-Key')).toBeTruthy();
    expect(await posts[0]?.json()).toEqual({ values: { step: 'shp-0-1|PICKED_UP' } });
    await waitFor(() => expect(within(rowOf(sheet, 'AfroTransact')).getByText('Picked up')).toBeInTheDocument());
    expect(await screen.findByText('AfroTransact: set to Picked up.')).toBeInTheDocument();
  });

  it('asks for a note on an exceptional step and does not send without one', async () => {
    let sent = 0;
    interceptStaff(async (req, next) => {
      if (isShipmentStatus(req)) sent += 1;
      return next(req);
    });
    const { user, sheet } = await openOrder();
    await user.click(within(await openMenu(user, rowOf(sheet, 'Patel Grocers'), 'Patel Grocers')).getByRole('menuitem', { name: 'Cancelled' }));
    const dialog = await screen.findByRole('dialog', { name: 'Set shipment to Cancelled?' });
    await user.click(within(dialog).getByRole('button', { name: 'Set to Cancelled' }));
    expect(await within(dialog).findByText('Say why to continue.')).toBeInTheDocument();
    expect(sent).toBe(0);

    await user.type(within(dialog).getByLabelText('Why (required)'), 'Seller is out of stock');
    await user.click(within(dialog).getByRole('button', { name: 'Set to Cancelled' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Set shipment to Cancelled?' })).toBeNull());
    expect(sent).toBe(1);
    const patel = rowOf(sheet, 'Patel Grocers');
    expect(within(patel).getByText('Cancelled')).toBeInTheDocument();

    // History: collapsed, newest first, with who and the note.
    const history = within(patel).getByText('History (4)');
    await user.click(history);
    expect(within(patel).getByText('Seller is out of stock')).toBeVisible();
    expect(within(patel).getAllByRole('listitem')[0]).toHaveTextContent('Cancelled · Platform admin');
  });

  it('shows the API’s plain-language error in the dialog and keeps it open', async () => {
    interceptStaff(async (req, next) => (isShipmentStatus(req) ? problem(409, 'CONFLICT', 'Packed by someone else a moment ago. Reload to see where it is now.') : next(req)));
    const { user, sheet } = await openOrder();
    await user.click(within(await openMenu(user, rowOf(sheet, 'AfroTransact'), 'AfroTransact')).getByRole('menuitem', { name: 'Picked up' }));
    const dialog = await screen.findByRole('dialog', { name: 'Set shipment to Picked up?' });
    await user.click(within(dialog).getByRole('button', { name: 'Set to Picked up' }));
    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('Couldn’t set the status.');
    expect(alert).toHaveTextContent('Packed by someone else a moment ago. Reload to see where it is now.');
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeEnabled();
  });
});

describe('Order sheet: layout', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-12T14:00:00Z'));
    asRoles(null);
  });

  it('leads with the header, then shipments, then facts grouped by topic', async () => {
    const { sheet } = await openOrder();
    const headings = within(sheet)
      .getAllByRole('heading', { level: 3 })
      .map((h) => h.textContent);
    expect(headings.slice(0, 3)).toEqual(['Shipments', 'Customer', 'Payment']);
    // Header: status chip, total, then the customer's name and when it was placed.
    expect(within(sheet).getByText(/^\S.* · Placed /)).toBeInTheDocument();
    expect(within(sheet).getByText('Open')).toBeInTheDocument();
  });

  it('shows no ids and no empty rows, and keeps ids under a collapsed Technical details with copy buttons', async () => {
    const { sheet } = await openOrder();
    const technical = within(sheet).getByTestId('technical-details');
    expect(technical).not.toHaveAttribute('open');
    expect(within(technical).getByText('Technical details')).toBeInTheDocument();
    expect(within(technical).getByRole('button', { name: 'Copy Order ID' })).toBeInTheDocument();
    expect(within(technical).getByRole('button', { name: 'Copy Customer ID' })).toBeInTheDocument();

    // Outside Technical details: no UUID, no payment id, no "—" placeholder, no internal field names.
    const clone = sheet.cloneNode(true) as HTMLElement;
    clone.querySelector('[data-testid="technical-details"]')?.remove();
    const text = clone.textContent ?? '';
    expect(text).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/);
    expect(text).not.toMatch(/Payment id|Partial|Attribution|—/);
  });

  it('puts the order-level actions in one "Order actions" menu', async () => {
    const { user, sheet } = await openOrder();
    expect(within(sheet).queryByRole('button', { name: 'More options' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Order actions' }));
    const menu = await screen.findByRole('menu');
    expect(within(menu).getByRole('menuitem', { name: 'Copy order number' })).toBeInTheDocument();
    expect(within(menu).getByRole('menuitem', { name: 'Open in customer support' })).toBeInTheDocument();
  });
});
