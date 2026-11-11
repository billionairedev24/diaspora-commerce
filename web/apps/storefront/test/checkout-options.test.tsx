import { type CheckoutPackage, type DeliveryOption } from '@afrotransact/api-client';
import { resetShippingSettings, setShippingSettings } from '@afrotransact/api-client/mocks';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CheckoutView } from '@/components/checkout/CheckoutView';
import { initialChoice, PackageCard } from '@/components/checkout/PackageCard';
import { onRequestQuote } from '@/components/checkout/quote';

import { installMockBff, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

vi.mock('@/components/checkout/quote', () => ({ onRequestQuote: vi.fn() }));

installMockBff();

/** Carrier and carrier-service names no shopper-facing text may carry while we are not shipping by carrier. */
const CARRIER = /\b(usps|ups|fedex|dhl)\b|ground advantage|next day air|priority mail|express air/i;

const delivery: DeliveryOption = { key: 'afrotransact_delivery', label: 'Delivery by AfroTransact', eta: 'Tuesday, Nov 17', feeMinor: 599, free: false };
const pickup: DeliveryOption = { key: 'pickup', label: 'Pickup at an AfroTransact point', eta: 'Ready the same day', feeMinor: 0, free: true };
const quote: DeliveryOption = { key: 'quote_required', label: 'Too far for standard delivery. Ask us for a quote', eta: '', feeMinor: 0, free: false };

function pkg(options: DeliveryOption[]): CheckoutPackage {
  return {
    packageId: 'org_fba:AFROTRANSACT',
    seller: { id: 'org_fba', name: 'Lagos Foods', kind: 'SELLER', city: '' },
    fulfilment: 'AFROTRANSACT',
    heading: 'Shipped by AfroTransact',
    shipsFrom: 'AfroTransact',
    delivery: { days: 2, label: 'Tuesday, Nov 17', prefix: 'Delivery by AfroTransact', free: false, feeMinor: 599, cutoff: null },
    deliveryOptions: options,
    lines: [{ lineId: 'l1', atin: 'AT1', offerId: 'o1', title: 'Jollof Seasoning', unit: '', department: 'Groceries', priceMinor: 649, lineTotalMinor: 649, quantity: 1, maxQuantity: 5, availableQty: 5, stockStatus: 'IN_STOCK', selected: true }],
    itemCount: 1,
    subtotalMinor: 649,
    freeShippingNudge: null,
    membershipWaivedMinor: 0,
    pickupPoints: options.some((o) => o.key === 'pickup')
      ? [{ id: 'pp1', name: 'Houston FC — Will Call', address: '1 Dock Rd, Houston, TX 77002', hours: 'Mon–Sat 8 am–8 pm', ready: 'Ready the same day', setBy: 'platform', kind: 'PLATFORM_COUNTER', distanceMiles: 3 }]
      : []
  } as CheckoutPackage;
}

function Harness({ p }: { p: CheckoutPackage }) {
  const [choice, setChoice] = useState(initialChoice(p));
  return <PackageCard pkg={p} n={1} total={1} choice={choice} onChoice={setChoice} onRemove={() => undefined} onChangeAddress={() => undefined} removing={false} />;
}

function card(p: CheckoutPackage) {
  return renderWithProviders(<Harness p={p} />);
}

beforeEach(() => {
  resetShippingSettings();
  vi.mocked(onRequestQuote).mockClear();
});

describe('the delivery option list at launch (listing rules section 3)', () => {
  it('is a flat list: Delivery by AfroTransact with its price and date, no carrier, no express', () => {
    card(pkg([delivery, pickup]));
    const row = screen.getByTestId('delivery-option-afrotransact_delivery');
    expect(row).toHaveTextContent('Delivery by AfroTransact');
    expect(row).toHaveTextContent('Tuesday, Nov 17');
    expect(screen.getByTestId('option-fee-afrotransact_delivery')).toHaveTextContent('$5.99');
    expect(screen.queryByTestId('delivery-option-express')).toBeNull();
    expect(screen.getByRole('radio', { name: /Delivery by AfroTransact/ })).toBeChecked();
    expect(document.body.textContent ?? '').not.toMatch(CARRIER);
  });

  it('shows a free delivery as FREE and never shows an unknown fee as $0', () => {
    card(pkg([{ ...delivery, feeMinor: 0, free: true }, pickup]));
    expect(screen.getByTestId('option-fee-afrotransact_delivery')).toHaveTextContent('FREE');
  });

  it('shows a fee that could not be read as unknown, not FREE and not $0.00', () => {
    card(pkg([{ ...delivery, feeMinor: 0, free: false, feeUnknown: true, label: 'Delivery fee shown at checkout' }, pickup]));
    const fee = screen.getByTestId('option-fee-afrotransact_delivery');
    expect(fee).toHaveTextContent('Delivery fee shown at checkout');
    expect(fee).not.toHaveTextContent(/FREE|\$0/);
  });

  it('offers pickup with its own label and the free price', async () => {
    const user = userEvent.setup();
    card(pkg([delivery, pickup]));
    await user.click(screen.getByTestId('fulfil-pickup'));
    expect(screen.getByText('Houston FC — Will Call')).toBeInTheDocument();
    expect(document.body.textContent ?? '').not.toMatch(CARRIER);
  });
});

describe('an address outside every delivery zone', () => {
  it('shows the single quote_required option, no way to pay for it, and a button that calls onRequestQuote', async () => {
    const user = userEvent.setup();
    const p = pkg([quote, pickup]);
    card(p);
    const row = screen.getByTestId('quote-required');
    expect(row).toHaveTextContent('Too far for standard delivery. Ask us for a quote');
    expect(screen.queryByRole('radio', { name: /Too far/ })).toBeNull();
    await user.click(within(row).getByTestId('request-quote'));
    expect(onRequestQuote).toHaveBeenCalledTimes(1);
    expect(onRequestQuote).toHaveBeenCalledWith(expect.objectContaining({ packageId: 'org_fba:AFROTRANSACT' }));
  });

  it('keeps pickup as a way forward', async () => {
    const user = userEvent.setup();
    card(pkg([quote, pickup]));
    expect(screen.getByTestId('fulfil-pickup')).toBeInTheDocument();
    await user.click(screen.getByTestId('fulfil-pickup'));
    expect(screen.getByText('Houston FC — Will Call')).toBeInTheDocument();
  });
});

describe('checkout page (mock BFF)', () => {
  it('lists delivery and pickup for an in-zone address and places the order with the flat fee', async () => {
    const api = await signInDemo();
    await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity: 2 });
    renderWithProviders(<CheckoutView />);
    const pk = await screen.findByTestId('checkout-package');
    expect(within(pk).getByTestId('delivery-option-afrotransact_delivery')).toHaveTextContent('Delivery by AfroTransact');
    expect(within(pk).getByTestId('option-fee-afrotransact_delivery')).toHaveTextContent('$5.99');
    expect(screen.getByRole('button', { name: 'Place your order' })).toBeEnabled();
    expect(document.body.textContent ?? '').not.toMatch(CARRIER);
  });

  it('follows the fee staff set, and a free-above threshold makes it FREE', async () => {
    const api = await signInDemo();
    await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity: 2 });
    setShippingSettings({ flatFeeMinor: 799, freeAboveMinor: null });
    const { unmount } = renderWithProviders(<CheckoutView />);
    expect(await screen.findByTestId('option-fee-afrotransact_delivery')).toHaveTextContent('$7.99');
    unmount();
    setShippingSettings({ flatFeeMinor: 799, freeAboveMinor: 1000 });
    renderWithProviders(<CheckoutView />);
    await waitFor(() => expect(screen.getByTestId('option-fee-afrotransact_delivery')).toHaveTextContent('FREE'));
  });

  it('blocks the order for a far address: the quote option, the reason in plain words, Place your order disabled', async () => {
    const api = await signInDemo();
    await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity: 1 });
    const far = await api.createAddress({ name: 'Ada Obi', phone: '+13015550142', line1: '8605 Colesville Rd', city: 'Silver Spring', state: 'MD', zip: '20910', isDefault: true });
    expect(far.zip).toBe('20910');
    const user = userEvent.setup();
    renderWithProviders(<CheckoutView />);
    const pk = await screen.findByTestId('checkout-package');
    expect(within(pk).getByTestId('quote-required')).toHaveTextContent('Too far for standard delivery. Ask us for a quote');
    expect(screen.getByRole('button', { name: 'Place your order' })).toBeDisabled();
    expect(screen.getByTestId('delivery-blocked-note')).toHaveTextContent(/too far for standard delivery/i);
    await user.click(within(pk).getByTestId('request-quote'));
    expect(onRequestQuote).toHaveBeenCalledTimes(1);
  });
});
