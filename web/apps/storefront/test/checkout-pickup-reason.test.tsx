import { type CheckoutPackage, type DeliveryOption } from '@afrotransact/api-client';
import { screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';

import { initialChoice, PackageCard } from '@/components/checkout/PackageCard';
import { pickupReasonKey } from '@/components/checkout/pickup-reason';

import { renderWithProviders } from './render';

const delivery: DeliveryOption = { key: 'afrotransact_delivery', label: 'Delivery', eta: 'Tuesday, Nov 17', feeMinor: 599, free: false };

function pkg(reason?: CheckoutPackage['pickupUnavailableReason']): CheckoutPackage {
  return {
    packageId: 'org_s1:SELLER',
    seller: { id: 'org_s1', name: 'Mama Put', kind: 'SELLER', city: '' },
    fulfilment: 'SELLER',
    heading: 'Shipped by Mama Put',
    shipsFrom: 'Houston, TX',
    delivery: { days: 2, label: 'Tuesday, Nov 17', prefix: 'Delivery', free: false, feeMinor: 599, cutoff: null },
    deliveryOptions: [delivery],
    lines: [{ lineId: 'l1', atin: 'AT1', offerId: 'o1', title: 'Jollof Seasoning', unit: '', department: 'Groceries', imageUrl: null, priceMinor: 649, lineTotalMinor: 649, quantity: 1, maxQuantity: 5, availableQty: 5, stockStatus: 'IN_STOCK', selected: true }],
    itemCount: 1,
    subtotalMinor: 649,
    freeShippingNudge: null,
    membershipWaivedMinor: 0,
    pickupPoints: [],
    pickupUnavailableReason: reason
  } as CheckoutPackage;
}

function Harness({ p }: { p: CheckoutPackage }) {
  const [choice, setChoice] = useState(initialChoice(p));
  return <PackageCard pkg={p} n={1} total={1} choice={choice} onChoice={setChoice} onRemove={() => undefined} onChangeAddress={() => undefined} removing={false} />;
}

describe('why a package has no pickup, in the shopper’s words', () => {
  it('maps only the reasons that help the shopper', () => {
    expect(pickupReasonKey('SELLER_NOT_ENABLED')).toBe('co.pickupNone.seller');
    expect(pickupReasonKey('NEEDS_ADDRESS')).toBe('co.pickupNone.address');
    expect(pickupReasonKey('TOO_FAR')).toBe('co.pickupNone.area');
    expect(pickupReasonKey('OTHER_AREA')).toBe('co.pickupNone.area');
    expect(pickupReasonKey('NO_PLATFORM_POINT')).toBeNull();
    expect(pickupReasonKey('LOOKUP_FAILED')).toBeNull();
    expect(pickupReasonKey(undefined)).toBeNull();
  });

  it('says the seller does not offer pickup', () => {
    renderWithProviders(<Harness p={pkg('SELLER_NOT_ENABLED')} />);
    expect(screen.getByTestId('pickup-unavailable')).toHaveTextContent('This seller doesn’t offer pickup.');
  });

  it('says nothing for internal reasons or when pickup is simply not in question', () => {
    renderWithProviders(<Harness p={pkg('LOOKUP_FAILED')} />);
    expect(screen.queryByTestId('pickup-unavailable')).toBeNull();
    expect(document.body.textContent ?? '').not.toMatch(/LOOKUP|SELLER_NOT_ENABLED|carrier/i);
  });
});
