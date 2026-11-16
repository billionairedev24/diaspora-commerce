import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { DeliveryProofLink } from '@/components/orders/DeliveryProofCard';
import { OrderDetailView } from '@/components/orders/OrderDetailView';
import { ShipmentCode } from '@/components/orders/OrderParts';
import { OrdersView } from '@/components/orders/OrdersView';
import { PickupDetails } from '@/components/orders/PickupDetails';

import { installMockBff, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

describe('Your orders in the flat account language (mock BFF)', () => {
  it('has the page title with a breadcrumb, three tabs, a search box and a time range', async () => {
    await signInDemo();
    renderWithProviders(<OrdersView />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Your orders' })).toBeInTheDocument();
    const crumb = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumb).getByRole('link', { name: 'Your account' })).toHaveAttribute('href', '/account');
    const tabs = screen.getByRole('navigation', { name: 'Order views' });
    expect(within(tabs).getByRole('button', { name: 'Orders' })).toHaveAttribute('aria-current', 'page');
    expect(within(tabs).getByRole('button', { name: /Not yet shipped/ })).toBeInTheDocument();
    expect(within(tabs).getByRole('button', { name: /Cancelled orders/ })).toBeInTheDocument();
    expect(screen.getByTestId('orders-search')).toBeInTheDocument();
    expect(screen.getByTestId('orders-range')).toHaveValue('3m');
  });

  it('shows each order as a flat block: placed, total, ship to, order number and its shipments with their actions', async () => {
    await signInDemo();
    renderWithProviders(<OrdersView />);
    const orders = await screen.findAllByTestId('order');
    expect(orders).toHaveLength(2);
    const first = within(orders[0] as HTMLElement);
    expect(first.getByText('Order placed')).toBeInTheDocument();
    expect(first.getByText('Total')).toBeInTheDocument();
    expect(first.getByText('Ship to')).toBeInTheDocument();
    expect(first.getByRole('link', { name: 'View order details' })).toHaveAttribute('href', '/orders/AT-2026-104221');
    expect(first.getByText(/^Delivered /)).toBeInTheDocument();
    expect(first.getByText(/Sold by Tamale Shea Co-op/)).toBeInTheDocument();
    expect(first.getByRole('link', { name: 'Get help with this order' })).toHaveAttribute('href', '/account/help/new?order=AT-2026-104221');
    expect(first.getByRole('button', { name: 'Buy it again' })).toBeInTheDocument();
    // A delivered package is not tracked, and there is no self-service return or refund.
    expect(first.queryByRole('link', { name: 'Track package' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /return|refund/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /return|refund/i })).not.toBeInTheDocument();
  });

  it('opens the recipient name and address from the Ship to link', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<OrdersView />);
    const orders = await screen.findAllByTestId('order');
    await user.click(within(orders[0] as HTMLElement).getByTestId('order-ship-to'));
    const pop = await within(orders[0] as HTMLElement).findByTestId('order-ship-to-popover');
    expect(await within(pop).findByText('Adaeze Nwosu')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(within(orders[0] as HTMLElement).queryByTestId('order-ship-to-popover')).not.toBeInTheDocument();
  });

  it('searches the loaded orders by item and tells when nothing matches', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<OrdersView />);
    await screen.findAllByTestId('order');
    await user.type(screen.getByTestId('orders-search'), '100873');
    await waitFor(() => expect(screen.getAllByTestId('order')).toHaveLength(1));
    await user.clear(screen.getByTestId('orders-search'));
    await user.type(screen.getByTestId('orders-search'), 'zzz');
    expect(await screen.findByTestId('orders-empty')).toHaveTextContent('No loaded orders match.');
  });

  it('puts delivered orders in none of the Not yet shipped and Cancelled tabs', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<OrdersView />);
    await screen.findAllByTestId('order');
    await user.click(screen.getByTestId('orders-tab-NOT_SHIPPED'));
    expect(await screen.findByTestId('orders-empty')).toHaveTextContent('You have no orders waiting to ship.');
    await user.click(screen.getByTestId('orders-tab-CANCELLED'));
    expect(await screen.findByTestId('orders-empty')).toHaveTextContent('You have no cancelled orders in this time range.');
  });

  it('says so, like Amazon, when the time range has no orders, and offers to change it or keep shopping', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<OrdersView />);
    await screen.findAllByTestId('order');
    await user.selectOptions(screen.getByTestId('orders-range'), 'y2024');
    const empty = await screen.findByTestId('orders-empty');
    expect(empty).toHaveTextContent('You have not placed any orders in 2024.');
    expect(within(empty).getByRole('link', { name: 'Keep shopping' })).toHaveAttribute('href', '/');
    await user.click(within(empty).getByRole('button', { name: 'Change the time range' }));
    expect(screen.getByTestId('orders-range')).toHaveFocus();
  });

  it('adds the items of a shipment to the cart with Buy it again', async () => {
    const api = await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<OrdersView />);
    const orders = await screen.findAllByTestId('order');
    await user.click(within(orders[0] as HTMLElement).getByRole('button', { name: 'Buy it again' }));
    await waitFor(async () => expect((await api.getCart()).itemCount).toBeGreaterThan(0));
  });
});

describe('Order details in the flat account language (mock BFF)', () => {
  it('has the breadcrumb Your account › Your orders › Order …, and Ship to, Payment method and Order summary without boxes', async () => {
    await signInDemo();
    renderWithProviders(<OrderDetailView orderNumber="AT-2026-104221" />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Order details' })).toBeInTheDocument();
    const crumb = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumb).getByRole('link', { name: 'Your account' })).toHaveAttribute('href', '/account');
    expect(within(crumb).getByRole('link', { name: 'Your orders' })).toHaveAttribute('href', '/orders');
    expect(within(crumb).getByText('Order AT-2026-104221')).toHaveAttribute('aria-current', 'page');
    for (const name of ['Ship to', 'Payment method', 'Order summary']) expect(await screen.findByRole('heading', { level: 2, name })).toBeInTheDocument();
    expect(screen.getByTestId('order-detail-summary')).toHaveTextContent('Total');
  });

  it('lists the items of a delivered shipment with quantity and price, help via support, and no return button', async () => {
    await signInDemo();
    renderWithProviders(<OrderDetailView orderNumber="AT-2026-104221" />);
    const shipment = await screen.findByTestId('shipment');
    const rows = within(shipment).getAllByTestId('order-line');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent(/1 × \$/);
    expect(within(shipment).getByTestId('order-get-help')).toHaveAttribute('href', '/account/help/new?order=AT-2026-104221');
    expect(within(shipment).queryByRole('button', { name: /return|refund/i })).not.toBeInTheDocument();
    expect(within(shipment).queryByRole('link', { name: 'Track package' })).not.toBeInTheDocument();
  });
});

describe('pieces of a shipment', () => {
  it('prints a delivery code plainly, and a pickup code with its own words', () => {
    renderWithProviders(
      <>
        <ShipmentCode code="4821" />
        <ShipmentCode code="7733" pickup />
      </>
    );
    const [delivery, pickup] = screen.getAllByTestId('shipment-code');
    expect(delivery).toHaveTextContent('Delivery code4821give it to the person delivering your order at the door');
    expect(pickup).toHaveTextContent('Pickup code7733show it when you collect your order');
  });

  it('opens the delivery proof from a plain link', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeliveryProofLink proof={{ method: 'CUSTOMER', recipientName: null, relationship: null, safePlace: null, photoUrl: null, photoUrls: [], signatureUrl: null, at: '2026-10-25T15:00:00Z' }} />);
    await user.click(screen.getByRole('button', { name: 'View delivery proof' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'Proof of delivery' })).toBeInTheDocument();
    expect(within(dialog).getByText(/Handed to you/)).toBeInTheDocument();
  });

  it('shows a proof by AfroTransact staff: every photo, who received it, and when', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <DeliveryProofLink
        proof={{ method: 'PHOTO', recipientName: 'Ada Obi', relationship: null, safePlace: null, photoUrl: 'https://media.example/p1', photoUrls: ['https://media.example/p1', 'https://media.example/p2'], signatureUrl: null, at: '2026-11-14T16:00:00Z' }}
      />
    );
    await user.click(screen.getByRole('button', { name: 'View delivery proof' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Received by Ada Obi/)).toBeInTheDocument();
    expect(within(dialog).getAllByRole('img')).toHaveLength(2);
    expect(within(dialog).getByText('Photo 1')).toBeInTheDocument();
    expect(within(dialog).getByText('Photo 2')).toBeInTheDocument();
  });

  it('says only that AfroTransact delivered it when nobody was named', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <DeliveryProofLink proof={{ method: 'PHOTO', recipientName: null, relationship: null, safePlace: null, photoUrl: 'https://media.example/p1', photoUrls: ['https://media.example/p1'], signatureUrl: null, at: '2026-11-14T16:00:00Z' }} />
    );
    await user.click(screen.getByRole('button', { name: 'View delivery proof' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Delivered by AfroTransact/)).toBeInTheDocument();
    expect(within(dialog).getByText('Photo from the door')).toBeInTheDocument();
  });

  it('names the pickup point, its address and hours', () => {
    renderWithProviders(<PickupDetails pickup={{ id: 'pp1', name: 'Houston FC', address: '4410 Richmond Ave', hours: 'Mon–Sat 9–7', ready: 'Ready today', setBy: 'platform' }} />);
    expect(screen.getByTestId('order-pickup')).toHaveTextContent('Pick up at Houston FC');
    expect(screen.getByTestId('order-pickup')).toHaveTextContent('4410 Richmond Ave');
  });
});

describe('honest wording per fulfilment mode (mock BFF)', () => {
  it('says AfroTransact delivered it, with a proof link, only for an order AfroTransact delivered', async () => {
    await signInDemo();
    renderWithProviders(<OrderDetailView orderNumber="AT-2026-104221" />);
    const shipment = await screen.findByTestId('shipment');
    expect(within(shipment).getByText('Delivered by AfroTransact')).toBeInTheDocument();
    expect(within(shipment).queryByText('Shipped by the seller')).not.toBeInTheDocument();
  });

  it('an old order the seller shipped themselves (history) says so, names no carrier and never shows delivery proof', async () => {
    await signInDemo();
    renderWithProviders(<OrderDetailView orderNumber="AT-2026-100873" />);
    const shipment = await screen.findByTestId('shipment');
    expect(within(shipment).getByText('Shipped by the seller')).toBeInTheDocument();
    expect(within(shipment).queryByText(/AfroTransact/)).not.toBeInTheDocument();
    expect(within(shipment).queryByTestId('order-view-proof')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/\b(usps|ups|fedex|dhl)\b/i);
  });

  it('a seller who offers neither pickup nor Fulfilled by AfroTransact cannot be bought: no delivery option, flagged, order blocked', async () => {
    const api = await signInDemo();
    const seller = await api.getProduct('AT000000P5');
    await api.addToCart({ offerId: seller.buyBox?.offerId ?? '', quantity: 1 });
    const sum = await api.getCheckoutSummary();
    const pkg = sum.packages.find((p) => p.fulfilment === 'SELLER');
    expect(pkg?.deliveryOptions.map((o) => o.key)).toEqual([]);
    expect(pkg?.noFulfilment).toBe(true);
    expect(sum.canPlaceOrder).toBe(false);
    expect(sum.deliveryBlockedReason).toMatch(/offers neither pickup near you nor delivery/);
  });
});
