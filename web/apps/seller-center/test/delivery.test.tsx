import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { FulfilmentSheet } from '@/components/listings/FulfilmentSheet';
import { FeesView } from '@/components/money/FeesView';
import { OrderSheet } from '@/components/orders/OrderSheet';
import { ShipFromView } from '@/components/orders/ShipFromView';

import { asRole, mockApi } from './mock-api';
import { renderScreen } from './render';

/* AfroTransact Delivery from the seller's store (S255) in the Seller Center, against the seller mocks. */
describe('AfroTransact pickup and delivery fee', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('shows where geo placed each ship-from address and turns pickup on with hours', async () => {
    const user = userEvent.setup();
    await renderScreen(<ShipFromView />);
    const cards = await screen.findAllByTestId('ship-from-address');
    expect(cards).toHaveLength(2);
    expect(within(cards[0] as HTMLElement).getByTestId('ship-from-position')).toHaveTextContent('29.6713, -95.5559');
    // The AfroTransact (driver) pickup form of the Katy address; customer pickup has its own form next to it.
    const katy = within(cards[1] as HTMLElement).getByRole('form', { name: 'AfroTransact pickup' });
    const toggle = within(katy).getByLabelText(/AfroTransact picks up here/);
    expect(toggle).not.toBeChecked();
    await user.click(toggle);
    expect(within(katy).getAllByTestId('pickup-window')).toHaveLength(1);
    await user.click(within(katy).getByRole('button', { name: 'Add a pickup window' }));
    const closes = within(katy).getByLabelText('Closes, window 2');
    await user.clear(closes);
    await user.type(closes, '09:00');
    await user.click(within(katy).getByRole('button', { name: 'Save pickup settings' }));
    expect(await within(katy).findByText('Closes after it opens')).toBeInTheDocument();
    await user.click(within(katy).getByRole('button', { name: 'Remove window 2' }));
    await user.click(within(katy).getByRole('button', { name: 'Save pickup settings' }));
    await waitFor(async () => expect((await mockApi().listShipFrom())[1]?.afrotransactPickup.enabled).toBe(true));
  });

  it('lets customers pick up at a store address: hours and ready time, offered at checkout', async () => {
    const user = userEvent.setup();
    await renderScreen(<ShipFromView />);
    const cards = await screen.findAllByTestId('ship-from-address');
    expect(within(cards[0] as HTMLElement).getByTestId('customer-pickup')).toHaveTextContent('Customers can pick up');
    const katy = within(cards[1] as HTMLElement).getByTestId('customer-pickup');
    await user.click(within(katy).getByLabelText(/Customers can pick up their orders here/));
    expect(within(katy).getAllByTestId('customer-pickup-window')).toHaveLength(1);
    const ready = within(katy).getByLabelText('Ready after (hours)');
    await user.clear(ready);
    await user.type(ready, '999');
    await user.click(within(katy).getByRole('button', { name: 'Save customer pickup' }));
    expect(await within(katy).findByText('Enter 0 to 168 hours')).toBeInTheDocument();
    await user.clear(ready);
    await user.type(ready, '4');
    await user.click(within(katy).getByRole('button', { name: 'Save customer pickup' }));
    await waitFor(async () => expect((await mockApi().listShipFrom())[1]?.customerPickup).toMatchObject({ enabled: true, readyHours: 4 }));
  });

  it('explains why pickup can’t be turned on: outside the area, not geocoded', async () => {
    const user = userEvent.setup();
    const api = mockApi();
    await api.createShipFrom({ name: 'Ade Okafor', line1: '500 Congress Ave', line2: '', city: 'Austin', state: 'TX', postalCode: '78701', phone: '', label: 'Austin' });
    await api.createShipFrom({ name: 'Ade Okafor', line1: '1 Unknown Rd', line2: '', city: 'Houston', state: 'TX', postalCode: '00000', phone: '', label: 'Nowhere' });
    await renderScreen(<ShipFromView />);
    const cards = await screen.findAllByTestId('ship-from-address');
    const austin = cards[2] as HTMLElement;
    await user.click(within(austin).getByLabelText(/AfroTransact picks up here/));
    await user.click(within(austin).getByRole('button', { name: 'Save pickup settings' }));
    expect(await within(austin).findByRole('alert')).toHaveTextContent('outside the AfroTransact delivery area');
    const nowhere = cards[3] as HTMLElement;
    expect(within(nowhere).getByText('We couldn’t locate this address yet.')).toBeInTheDocument();
    await user.click(within(nowhere).getByLabelText(/AfroTransact picks up here/));
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
