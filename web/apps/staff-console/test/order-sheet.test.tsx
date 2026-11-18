import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/*
 * The order sheet: AT-200400 has two packages, AfroTransact's (ready, Fulfilled by AfroTransact) and Patel Grocers' (packed,
 * the seller ships it). Staff set a status from one compact dropdown per shipment; every choice opens one small
 * confirmation. Who moves a shipment depends on its mode: AfroTransact-delivered steps are taken with the delivery
 * permission (the Delivery queue's steps, proof and audit), the seller updates their own shipments, a pickup order is
 * Ready / Collected / Not collected.
 */

const isShipmentStatus = (req: Request) => req.method === 'POST' && new URL(req.url).pathname.endsWith('/orders/AT-200400/actions/shipment-status');
const isDeliveryStep = (req: Request, step: string) => req.method === 'POST' && new URL(req.url).pathname.endsWith(`/deliveries/shp-0-1/${step}`);
const problem = (status: number, code: string, detail: string) =>
  new Response(JSON.stringify({ type: 'about:blank', title: code, status, code, detail }), { status, headers: { 'Content-Type': 'application/problem+json' } });

async function openOrder(order = 'AT-200400') {
  const user = userEvent.setup({ applyAccept: false });
  nav.set(`/orders?open=${order}`);
  await renderScreen(<ResourceTable resource="orders" />);
  const sheet = await screen.findByRole('dialog', { name: `Order ${order}` });
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

  it('lists each shipment as a flat row with the seller, items, who fulfils it and its current status', async () => {
    const { sheet } = await openOrder();
    const rows = within(sheet).getAllByTestId('shipment-row');
    expect(rows).toHaveLength(2);
    const afro = rowOf(sheet, 'AfroTransact');
    expect(within(afro).getByText('Ready')).toBeInTheDocument();
    expect(within(afro).getByText(/Jollof seasoning 200 g, Shea butter, raw 500 g/)).toBeInTheDocument();
    expect(within(afro).getByText('Fulfilled by AfroTransact · delivers it from the warehouse')).toBeInTheDocument();
    expect(within(afro).getByText('Shipment 1 of 2')).toBeInTheDocument();
    const patel = rowOf(sheet, 'Patel Grocers');
    expect(within(patel).getByText('Packed')).toBeInTheDocument();
    expect(within(patel).getByText('Shipped by the seller (retired: history only, no longer supported)')).toBeInTheDocument();
    // No sentence-long buttons, no carrier names, no driver talk.
    expect(within(sheet).queryByRole('button', { name: /Mark picked up/ })).toBeNull();
    expect(within(sheet).queryByRole('button', { name: /More options/ })).toBeNull();
    expect(sheet.textContent?.toLowerCase()).not.toMatch(/driver|ups|fedex|usps|dhl/);
  });

  it('offers a Fulfilled by AfroTransact shipment the delivery steps, in order, with the reason when a step would skip one', async () => {
    const { user, sheet } = await openOrder();
    const menu = await openMenu(user, rowOf(sheet, 'AfroTransact'), 'AfroTransact');
    expect(within(menu).getByRole('menuitem', { name: 'Picked up from the seller' })).not.toHaveAttribute('aria-disabled', 'true');
    expect(within(menu).getByRole('menuitem', { name: 'Cancelled' })).not.toHaveAttribute('aria-disabled', 'true');
    const delivered = within(menu).getByRole('menuitem', { name: /Delivered/ });
    expect(delivered).toHaveAttribute('aria-disabled', 'true');
    expect(delivered).toHaveTextContent('Finish earlier steps first');
    // The menu never shows the generic "Not available from here" for a step the shipment simply has not reached.
    expect(within(menu).getByRole('menuitem', { name: /^In transit/ })).toHaveTextContent('Finish earlier steps first');
  });

  it('says the seller updates a seller-shipped order, with no override for a role that does not hold the permission', async () => {
    asRoles('platform.fulfilment_ops');
    const { user, sheet } = await openOrder();
    const patel = rowOf(sheet, 'Patel Grocers');
    const menu = await openMenu(user, patel, 'Patel Grocers');
    const ready = within(menu).getByRole('menuitem', { name: /Ready/ });
    expect(ready).toHaveAttribute('aria-disabled', 'true');
    expect(ready).toHaveTextContent('Updated by the seller: use Override status');
    expect(within(menu).getByRole('menuitem', { name: /Delivered/ })).toHaveTextContent('Updated by the seller: use Override status');
    await user.keyboard('{Escape}');
    expect(within(patel).queryByRole('button', { name: 'Override status' })).toBeNull();
  });

  it('lists every status as unavailable for a role without the permission, with the accurate reason', async () => {
    asRoles('platform.customer_support');
    const { user, sheet } = await openOrder();
    const menu = await openMenu(user, rowOf(sheet, 'AfroTransact'), 'AfroTransact');
    const items = within(menu).getAllByRole('menuitem');
    expect(items.length).toBeGreaterThan(2);
    items.forEach((i) => expect(i).toHaveAttribute('aria-disabled', 'true'));
    expect(within(menu).getByRole('menuitem', { name: /^Picked up from the seller/ })).toHaveTextContent('You need the delivery permission');
    expect(within(menu).getByRole('menuitem', { name: /^Cancelled/ })).toHaveTextContent('No permission');
  });

  it('takes a delivery step through the Delivery module with one confirmation, then updates the row, the history and says so', async () => {
    const calls: Request[] = [];
    const orderActions: Request[] = [];
    interceptStaff(async (req, next) => {
      if (isDeliveryStep(req, 'picked-up')) calls.push(req.clone());
      if (isShipmentStatus(req)) orderActions.push(req.clone());
      return next(req);
    });
    const { user, sheet } = await openOrder();
    const row = rowOf(sheet, 'AfroTransact');
    await user.click(within(await openMenu(user, row, 'AfroTransact')).getByRole('menuitem', { name: 'Picked up from the seller' }));

    const dialog = await screen.findByRole('dialog', { name: 'Set shipment to Picked up from the seller?' });
    expect(within(dialog).getByText('AT-200400 · AfroTransact · Ready to Picked up from the seller')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeVisible();
    // A step that needs no reason says the note is optional.
    await user.type(within(dialog).getByLabelText('Note (optional)'), '2 boxes from the kitchen');
    await user.click(within(dialog).getByRole('button', { name: 'Set to Picked up from the seller' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Set shipment to Picked up from the seller?' })).toBeNull());
    expect(orderActions).toHaveLength(0);
    expect(calls).toHaveLength(1);
    expect(calls[0]?.headers.get('Idempotency-Key')).toBeTruthy();
    expect(await calls[0]?.json()).toEqual({ note: '2 boxes from the kitchen', expectedStatus: 'ready' });
    await waitFor(() => expect(within(rowOf(sheet, 'AfroTransact')).getByText('Picked up from the seller')).toBeInTheDocument());
    expect(await screen.findByText('AfroTransact: set to Picked up from the seller.')).toBeInTheDocument();
    // The next steps are now on offer; the one just taken is not.
    const menu = await openMenu(user, rowOf(sheet, 'AfroTransact'), 'AfroTransact');
    expect(within(menu).getByRole('menuitem', { name: 'In transit' })).not.toHaveAttribute('aria-disabled', 'true');
    expect(within(menu).getByRole('menuitem', { name: 'Out for delivery' })).not.toHaveAttribute('aria-disabled', 'true');
    // History, newest first, names who did it and the note.
    await user.keyboard('{Escape}');
    await user.click(within(rowOf(sheet, 'AfroTransact')).getByText(/History \(\d+\)/));
    expect(within(rowOf(sheet, 'AfroTransact')).getByText('2 boxes from the kitchen')).toBeVisible();
    expect(within(rowOf(sheet, 'AfroTransact')).getAllByRole('listitem')[0]).toHaveTextContent(/Picked up from the seller · (?!Platform admin)\S/);
  });

  it('opens the proof capture in the sheet for Delivered: a photo is required, the name and note are optional', async () => {
    const calls: Request[] = [];
    interceptStaff(async (req, next) => {
      if (isDeliveryStep(req, 'delivered')) calls.push(req.clone());
      return next(req);
    });
    const { user, sheet } = await openOrder();
    const take = async (name: string) => {
      await user.click(within(await openMenu(user, rowOf(sheet, 'AfroTransact'), 'AfroTransact')).getByRole('menuitem', { name }));
      const d = await screen.findByRole('dialog', { name: new RegExp(`Set shipment to ${name}`) });
      await user.click(within(d).getByRole('button', { name: new RegExp(`^Set to ${name}`) }));
      await waitFor(() => expect(screen.queryByRole('dialog', { name: new RegExp(`Set shipment to ${name}`) })).toBeNull());
    };
    await take('Picked up from the seller');
    await take('Out for delivery');
    await user.click(within(await openMenu(user, rowOf(sheet, 'AfroTransact'), 'AfroTransact')).getByRole('menuitem', { name: 'Delivered' }));

    const proof = await screen.findByRole('dialog', { name: /Mark AT-200400 delivered/ });
    await user.click(within(proof).getByRole('button', { name: 'Mark delivered' }));
    expect(await within(proof).findByText('Add at least one photo as proof of delivery.')).toBeInTheDocument();
    expect(calls).toHaveLength(0);
    await user.upload(within(proof).getByLabelText('Add a photo'), new File([new Uint8Array([1, 2, 3])], 'door.jpg', { type: 'image/jpeg' }));
    expect(await within(proof).findAllByTestId('proof-photo')).toHaveLength(1);
    await user.type(within(proof).getByLabelText('Who received it (optional)'), 'Ada Obi');
    await user.click(within(proof).getByRole('button', { name: 'Mark delivered' }));

    await waitFor(() => expect(calls).toHaveLength(1));
    const body = (await calls[0]?.json()) as { photoAssetIds: string[]; recipientName: string; expectedStatus: string };
    expect(body.photoAssetIds).toHaveLength(1);
    expect(body).toMatchObject({ recipientName: 'Ada Obi', expectedStatus: 'out_for_delivery' });
    await waitFor(() => expect(within(rowOf(sheet, 'AfroTransact')).getByText('Delivered')).toBeInTheDocument());
  }, 60_000);

  it('asks for a reason when a delivery fails and does not send without one', async () => {
    const calls: Request[] = [];
    interceptStaff(async (req, next) => {
      if (isDeliveryStep(req, 'failed')) calls.push(req.clone());
      return next(req);
    });
    const { user, sheet } = await openOrder();
    for (const name of ['Picked up from the seller', 'Out for delivery']) {
      await user.click(within(await openMenu(user, rowOf(sheet, 'AfroTransact'), 'AfroTransact')).getByRole('menuitem', { name }));
      const d = await screen.findByRole('dialog', { name: new RegExp(`Set shipment to ${name}`) });
      await user.click(within(d).getByRole('button', { name: new RegExp(`^Set to ${name}`) }));
      await waitFor(() => expect(screen.queryByRole('dialog', { name: new RegExp(`Set shipment to ${name}`) })).toBeNull());
    }
    await user.click(within(await openMenu(user, rowOf(sheet, 'AfroTransact'), 'AfroTransact')).getByRole('menuitem', { name: 'Delivery failed' }));
    const dialog = await screen.findByRole('dialog', { name: 'Set shipment to Delivery failed?' });
    await user.click(within(dialog).getByRole('button', { name: 'Set to Delivery failed' }));
    expect(await within(dialog).findByText('Say why to continue.')).toBeInTheDocument();
    expect(calls).toHaveLength(0);
    await user.type(within(dialog).getByLabelText('Why (required)'), 'Nobody home, left a card');
    await user.click(within(dialog).getByRole('button', { name: 'Set to Delivery failed' }));
    await waitFor(() => expect(within(rowOf(sheet, 'AfroTransact')).getByText('Delivery failed')).toBeInTheDocument());
    expect(calls).toHaveLength(1);
    // A failed attempt is tried again or the parcel goes back to the seller.
    const menu = await openMenu(user, rowOf(sheet, 'AfroTransact'), 'AfroTransact');
    expect(within(menu).getByRole('menuitem', { name: 'Out for delivery' })).not.toHaveAttribute('aria-disabled', 'true');
    expect(within(menu).getByRole('menuitem', { name: 'Returned to the seller' })).not.toHaveAttribute('aria-disabled', 'true');
  }, 60_000);

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

  it('shows the API’s plain-language error in the dialog and keeps it open (someone moved it first)', async () => {
    interceptStaff(async (req, next) => (isDeliveryStep(req, 'picked-up') ? problem(409, 'CONFLICT', 'Someone already moved this shipment to picked up. Reload to see it.') : next(req)));
    const { user, sheet } = await openOrder();
    await user.click(within(await openMenu(user, rowOf(sheet, 'AfroTransact'), 'AfroTransact')).getByRole('menuitem', { name: 'Picked up from the seller' }));
    const dialog = await screen.findByRole('dialog', { name: 'Set shipment to Picked up from the seller?' });
    await user.click(within(dialog).getByRole('button', { name: 'Set to Picked up from the seller' }));
    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('Couldn’t set the status.');
    expect(alert).toHaveTextContent('Someone already moved this shipment to picked up. Reload to see it.');
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeEnabled();
  });

  it('overrides a seller-shipped order only for the holder of the permission, with a required reason', async () => {
    const calls: Request[] = [];
    interceptStaff(async (req, next) => {
      if (req.method === 'POST' && new URL(req.url).pathname.endsWith('/orders/AT-200400/actions/shipment-override')) calls.push(req.clone());
      return next(req);
    });
    const { user, sheet } = await openOrder();
    const patel = rowOf(sheet, 'Patel Grocers');
    // The override also corrects Fulfilled by AfroTransact (forward only), so that row may offer it; pickup orders never do (below).
    await user.click(within(patel).getByRole('button', { name: 'Override status' }));
    const dialog = await screen.findByRole('dialog', { name: 'Override the shipment status' });
    await user.click(within(dialog).getByRole('button', { name: 'Override' }));
    expect(await within(dialog).findByText('Say why to continue.')).toBeInTheDocument();
    expect(calls).toHaveLength(0);
    await user.type(within(dialog).getByLabelText('Why are you overriding it? (required)'), 'Seller phoned, it left this morning');
    await user.click(within(dialog).getByRole('button', { name: 'Override' }));
    await waitFor(() => expect(calls).toHaveLength(1));
    const body = (await calls[0]?.json()) as { values: { step: string; reason: string } };
    expect(body.values.reason).toBe('Seller phoned, it left this morning');
    expect(body.values.step).toMatch(/^shp-0-2\|/);
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Override the shipment status' })).toBeNull());
    await user.click(within(rowOf(sheet, 'Patel Grocers')).getByText(/History \(\d+\)/));
    expect(within(rowOf(sheet, 'Patel Grocers')).getByText('Seller phoned, it left this morning')).toBeVisible();
  });

  it('offers a pickup order Ready for pickup, Collected and Not collected, and refuses the delivery steps with the reason', async () => {
    const { user, sheet } = await openOrder('AT-200421');
    const row = within(sheet).getAllByTestId('shipment-row')[0] as HTMLElement;
    expect(within(row).getByText(/Seller pickup · the customer collects it/)).toBeInTheDocument();
    await user.click(within(row).getByRole('button', { name: /^Set status of/ }));
    const menu = await screen.findByRole('menu');
    expect(within(menu).getByRole('menuitem', { name: 'Collected' })).not.toHaveAttribute('aria-disabled', 'true');
    expect(within(menu).getByRole('menuitem', { name: 'Not collected' })).not.toHaveAttribute('aria-disabled', 'true');
    const out = within(menu).getByRole('menuitem', { name: /^Out for delivery/ });
    expect(out).toHaveAttribute('aria-disabled', 'true');
    expect(out).toHaveTextContent('Not available for pickup orders');
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
