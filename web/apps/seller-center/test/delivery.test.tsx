import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { FulfilmentSheet } from '@/components/listings/FulfilmentSheet';
import { FeesView } from '@/components/money/FeesView';
import { OrderSheet } from '@/components/orders/OrderSheet';
import { LocationsView } from '@/components/settings/LocationsView';
import { ShippingView } from '@/components/settings/ShippingView';

import { asRole, mockApi } from './mock-api';
import { renderScreen } from './render';

/* AfroTransact Delivery from the seller's store (S255) in the Seller Center, against the seller mocks. */
/** Turns driver pickup on for a card and opens Monday 9 to 5 (nothing is prefilled). */
async function turnOnWithMonday(user: ReturnType<typeof userEvent.setup>, card: HTMLElement) {
  await user.click(within(card).getByRole('switch', { name: /AfroTransact picks up here/ }));
  await user.click(within(card).getByRole('switch', { name: 'Monday' }));
  await user.selectOptions(within(card).getByLabelText('From, Monday'), '09:00');
  await user.selectOptions(within(card).getByLabelText('To, Monday'), '17:00');
}

describe('AfroTransact pickup and delivery fee', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('shows where geo placed each location (Settings > Locations)', async () => {
    await renderScreen(<LocationsView />);
    const rows = await screen.findAllByTestId('ship-from-address');
    expect(rows).toHaveLength(2);
    expect(within(rows[0] as HTMLElement).getByTestId('ship-from-position')).toHaveTextContent('29.6713, -95.5559');
    // Pickup and shipping hours are on their own pages, not here.
    expect(within(rows[0] as HTMLElement).queryByRole('form')).toBeNull();
    expect(within(rows[0] as HTMLElement).getByRole('link', { name: 'Pickup settings' })).toHaveAttribute('href', '/settings/pickup');
  });

  it('turns AfroTransact driver pickup on with hours (Settings > Shipping & delivery): one switch, the week, one Save', async () => {
    const user = userEvent.setup();
    await renderScreen(<ShippingView />);
    const cards = await screen.findAllByTestId('shipping-location');
    const katy = within(cards[1] as HTMLElement).getByRole('form', { name: 'AfroTransact pickup' });
    const toggle = within(katy).getByRole('switch', { name: /AfroTransact picks up here/ });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    await user.click(toggle);
    // Nothing is prefilled: seven closed days, and Save waits for a valid day.
    expect(within(katy).getAllByTestId('weekly-row')).toHaveLength(7);
    expect(within(katy).queryAllByRole('combobox')).toHaveLength(0);
    const save = within(katy).getByRole('button', { name: 'Save pickup settings' });
    expect(save).toBeDisabled();
    await user.click(within(katy).getByRole('switch', { name: 'Monday' }));
    await user.selectOptions(within(katy).getByLabelText('From, Monday'), '10:00');
    await user.selectOptions(within(katy).getByLabelText('To, Monday'), '09:00');
    expect(within(katy).getByTestId('driver-validation')).toHaveTextContent('To must be later than from.');
    expect(save).toBeDisabled();
    await user.selectOptions(within(katy).getByLabelText('To, Monday'), '15:00');
    await user.click(within(katy).getByRole('button', { name: 'Copy to weekdays' }));
    expect(save).toBeEnabled();
    await user.click(save);
    await waitFor(async () => expect((await mockApi().listShipFrom())[1]?.afrotransactPickup.enabled).toBe(true));
    expect((await mockApi().listShipFrom())[1]?.afrotransactPickup.hours).toHaveLength(5);
  });

  it('says plainly who fulfils the orders, and a seller who turns AfroTransact off must confirm they ship them themselves', async () => {
    const user = userEvent.setup();
    await renderScreen(<ShippingView />);
    const cards = await screen.findAllByTestId('shipping-location');
    const mama = within(cards[0] as HTMLElement).getByRole('form', { name: 'AfroTransact pickup' });
    const toggle = within(mama).getByRole('switch', { name: /AfroTransact picks up here/ });
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    expect(within(mama).getByTestId('fulfilment-mode')).toHaveTextContent('Fulfilled by AfroTransact');
    expect(within(mama).queryByTestId('self-ship-ack')).toBeNull();
    await user.click(toggle);
    expect(within(mama).getByTestId('fulfilment-mode')).toHaveTextContent('You ship the orders from this location yourself');
    const save = within(mama).getByRole('button', { name: 'Save pickup settings' });
    expect(save).toBeDisabled();
    expect(within(mama).getByText('Tick the box to confirm you will ship these orders yourself.')).toBeInTheDocument();
    await user.click(within(within(mama).getByTestId('self-ship-ack')).getByRole('checkbox'));
    expect(save).toBeEnabled();
    await user.click(save);
    await waitFor(async () => expect((await mockApi().listShipFrom())[0]?.afrotransactPickup.enabled).toBe(false));
  });

  it('explains why driver pickup can’t be turned on: outside the area, not geocoded', async () => {
    const user = userEvent.setup();
    const api = mockApi();
    await api.createShipFrom({ name: 'Ade Okafor', line1: '500 Congress Ave', line2: '', city: 'Austin', state: 'TX', postalCode: '78701', phone: '', label: 'Austin' });
    await api.createShipFrom({ name: 'Ade Okafor', line1: '1 Unknown Rd', line2: '', city: 'Houston', state: 'TX', postalCode: '00000', phone: '', label: 'Nowhere' });
    await renderScreen(<ShippingView />);
    const cards = await screen.findAllByTestId('shipping-location');
    const austin = cards[2] as HTMLElement;
    await turnOnWithMonday(user, austin);
    await user.click(within(austin).getByRole('button', { name: 'Save pickup settings' }));
    expect(await within(austin).findByRole('alert')).toHaveTextContent('outside the AfroTransact delivery area');
    const nowhere = cards[3] as HTMLElement;
    await turnOnWithMonday(user, nowhere);
    await user.click(within(nowhere).getByRole('button', { name: 'Save pickup settings' }));
    expect(await within(nowhere).findByRole('alert')).toHaveTextContent('couldn’t locate this address at street level');
  });

  it('sets an offer to “AfroTransact picks up from my location” with a pickup location', async () => {
    const user = userEvent.setup();
    const listing = (await mockApi().listListings({ page: 0, size: 10 })).items.find((l) => l.fulfilment === 'SELLER' && l.atin);
    if (!listing) throw new Error('no listing');
    await renderScreen(<FulfilmentSheet listing={listing} onClose={() => undefined} />);
    const form = (await screen.findAllByTestId('offer-fulfilment'))[0] as HTMLElement;
    await user.click(within(form).getByLabelText('AfroTransact picks up from my location'));
    const location = within(form).getByLabelText('Pickup location');
    expect(within(location).getAllByRole('option')).toHaveLength(2); // only the address with pickup on
    await user.selectOptions(location, 'sfa-store');
    await user.click(within(form).getByRole('button', { name: 'Save fulfilment' }));
    await waitFor(async () => expect((await mockApi().listOffers(listing.productId)).items[0]).toMatchObject({ fulfilment: 'AFROTRANSACT_DELIVERY', shipFromLocationId: 'sfa-store' }));
  });

  it('shows the seller delivery fee on the shipment, in the payout breakdown and on the Fees page', async () => {
    const order = (await mockApi().listOrders({ limit: 100 })).items.find((r) => r.fulfilment === 'AFROTRANSACT_DELIVERY');
    if (!order) throw new Error('no AfroTransact Delivery order');
    const { unmount } = await renderScreen(<OrderSheet orderNumber={order.orderNumber} onClose={() => undefined} />);
    expect(await screen.findByTestId('local-delivery')).toHaveTextContent('−$2.00');
    expect(screen.getByTestId('order-earnings')).toHaveTextContent('AfroTransact Delivery seller fees');
    expect(screen.queryByRole('button', { name: /Buy label/ })).not.toBeInTheDocument();
    unmount();
    await renderScreen(<FeesView />);
    expect((await screen.findAllByText('AfroTransact Delivery seller fee')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('$2.00 per package').length).toBeGreaterThan(0);
  });
});
