import { type SellerShipment } from '@afrotransact/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ProofOfDelivery } from '@/components/orders/ProofOfDelivery';
import { ShipmentProgress } from '@/components/orders/ShipmentProgress';

import { renderWithProviders } from './render';

type Progress = Pick<SellerShipment, 'status' | 'nextActions' | 'trackingNumber' | 'canBuyLabel' | 'fulfilment' | 'fulfilmentMode'>;

/** An old shipment the seller shipped themselves, before self-shipping was retired: history, no steps. */
const legacy: Progress = { status: 'READY', nextActions: [], trackingNumber: null, canBuyLabel: false, fulfilment: 'SELLER', fulfilmentMode: 'legacy_seller_shipped' };

describe('self-shipping is retired', () => {
  it('offers the seller no shipped or tracking entry on an old seller-shipped order, and no delivered step either', () => {
    for (const status of ['READY', 'PICKED_UP', 'IN_TRANSIT'] as const) {
      const { unmount } = renderWithProviders(<ShipmentProgress shipment={{ ...legacy, status }} editable loading={false} onMove={vi.fn()} />);
      expect(screen.queryByRole('button')).toBeNull();
      expect(screen.queryByTestId('ship-details')).not.toBeInTheDocument();
      expect(screen.queryByLabelText(/Tracking number/)).not.toBeInTheDocument();
      expect(screen.queryByLabelText(/How you sent it/)).not.toBeInTheDocument();
      unmount();
    }
  });

  it('only offers confirm, pack and ready on a Fulfilled by AfroTransact shipment from the seller’s store, never shipped or delivered', async () => {
    const user = userEvent.setup();
    const onMove = vi.fn();
    renderWithProviders(
      <ShipmentProgress shipment={{ ...legacy, status: 'PACKED', nextActions: ['READY'], fulfilment: 'AFROTRANSACT_DELIVERY', fulfilmentMode: 'afrotransact_delivery' }} editable loading={false} onMove={onMove} />
    );
    expect(screen.queryByTestId('ship-details')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /shipped|delivered/i })).toBeNull();
    await user.click(screen.getByRole('button'));
    expect(onMove).toHaveBeenCalledWith('READY', undefined, undefined);
  });
});

describe('proof of delivery on the seller’s order (read-only)', () => {
  it('shows staff proof with every photo and who received it', () => {
    renderWithProviders(
      <ProofOfDelivery
        proof={{ method: 'PHOTO', recipientName: 'Ada Obi', relationship: null, safePlace: null, photoUrl: 'https://m/1', photoUrls: ['https://m/1', 'https://m/2'], signatureUrl: null, at: '2026-11-14T16:00:00Z' }}
      />
    );
    const box = screen.getByTestId('proof-of-delivery');
    expect(within(box).getByText(/Received by Ada Obi/)).toBeInTheDocument();
    expect(within(box).getAllByRole('img')).toHaveLength(2);
    expect(within(box).queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('a seller whose shipment is fulfilled by AfroTransact', () => {
  const afro: Progress = { status: 'READY', nextActions: [], trackingNumber: null, canBuyLabel: false, fulfilment: 'AFROTRANSACT_DELIVERY', fulfilmentMode: 'afrotransact_delivery' };

  it('sees it read-only: AfroTransact picks it up, with where to find the pickup hours, and no button', () => {
    renderWithProviders(<ShipmentProgress shipment={afro} editable loading={false} onMove={vi.fn()} />);
    const note = screen.getByTestId('afrotransact-note');
    expect(note).toHaveTextContent('AfroTransact picks this up.');
    expect(note).toHaveTextContent('pickup hours');
    expect(screen.queryByRole('button')).toBeNull();
    expect(document.body.textContent).not.toMatch(/driver/i);
  });

  it('is told AfroTransact has it, and can do nothing, while it is picked up, on the way or failed', () => {
    for (const [status, text] of [['PICKED_UP', 'AfroTransact has this order.'], ['IN_TRANSIT', 'AfroTransact has this order.'], ['OUT_FOR_DELIVERY', 'AfroTransact has this order.'], ['DELIVERY_FAILED', 'AfroTransact could not deliver this yet.']] as const) {
      const { unmount } = renderWithProviders(<ShipmentProgress shipment={{ ...afro, status }} editable loading={false} onMove={vi.fn()} />);
      expect(screen.getByTestId('afrotransact-note')).toHaveTextContent(text);
      expect(screen.queryByRole('button')).toBeNull();
      unmount();
    }
  });

  it('says nothing about AfroTransact for a pickup order, and the seller hands it over with the customer’s code', async () => {
    const user = userEvent.setup();
    const onMove = vi.fn();
    renderWithProviders(<ShipmentProgress shipment={{ ...afro, fulfilment: 'SELLER', fulfilmentMode: 'pickup', status: 'READY', nextActions: ['DELIVERED'] }} editable loading={false} onMove={onMove} />);
    expect(screen.queryByTestId('afrotransact-note')).toBeNull();
    await user.type(screen.getByLabelText(/code/i), '4821');
    await user.click(screen.getByRole('button', { name: /Hand over/ }));
    expect(onMove).toHaveBeenCalledWith('DELIVERED', '4821', undefined);
  });
});
