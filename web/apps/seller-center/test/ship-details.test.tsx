import { type SellerShipment } from '@afrotransact/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ProofOfDelivery } from '@/components/orders/ProofOfDelivery';
import { ShipmentProgress } from '@/components/orders/ShipmentProgress';

import { renderWithProviders } from './render';

type Progress = Pick<SellerShipment, 'status' | 'nextActions' | 'trackingNumber' | 'canBuyLabel' | 'fulfilment' | 'fulfilmentMode' | 'needsShipDetails'>;

const own: Progress = { status: 'READY', nextActions: ['PICKED_UP'], trackingNumber: null, canBuyLabel: false, fulfilment: 'SELLER', fulfilmentMode: 'seller_ships', needsShipDetails: true };

describe('a seller who ships it themselves', () => {
  it('cannot mark it shipped without a tracking number or handover details, and sends what they gave', async () => {
    const user = userEvent.setup();
    const onMove = vi.fn();
    renderWithProviders(<ShipmentProgress shipment={own} editable loading={false} onMove={onMove} />);
    const button = screen.getByRole('button', { name: 'Mark as shipped' });
    expect(button).toBeDisabled();
    expect(screen.getByText('Add a tracking number or say how you sent it.')).toBeInTheDocument();
    // No label to buy, and AfroTransact is not mentioned as moving it.
    expect(screen.queryByText(/Buy a shipping label/)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/driver/i);

    await user.type(screen.getByLabelText(/How you sent it/), 'Handed to a courier at 3pm');
    expect(button).toBeEnabled();
    await user.click(button);
    expect(onMove).toHaveBeenCalledWith('PICKED_UP', undefined, { trackingNumber: undefined, trackingCarrier: undefined, shipNote: 'Handed to a courier at 3pm' });
  });

  it('can send a tracking number (spaces removed) with an optional courier name for the seller’s own records', async () => {
    const user = userEvent.setup();
    const onMove = vi.fn();
    renderWithProviders(<ShipmentProgress shipment={own} editable loading={false} onMove={onMove} />);
    expect(screen.queryByLabelText('Courier name (optional)')).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Tracking number (optional)'), '9400 1118');
    await user.type(await screen.findByLabelText('Courier name (optional)'), 'Local Express');
    expect(screen.getByText('For your records and ours. Buyers are never shown it.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Mark as shipped' }));
    expect(onMove).toHaveBeenCalledWith('PICKED_UP', undefined, { trackingNumber: '94001118', trackingCarrier: 'Local Express', shipNote: undefined });
  });

  it('confirms delivery without any AfroTransact step in between', async () => {
    const user = userEvent.setup();
    const onMove = vi.fn();
    renderWithProviders(<ShipmentProgress shipment={{ ...own, status: 'PICKED_UP', nextActions: ['DELIVERED'], needsShipDetails: false }} editable loading={false} onMove={onMove} />);
    await user.click(screen.getByRole('button', { name: 'Mark delivered' }));
    expect(onMove).toHaveBeenCalledWith('DELIVERED', undefined, undefined);
  });

  it('is told AfroTransact collects it when the seller opted into delivery by AfroTransact (nothing to ship)', () => {
    renderWithProviders(
      <ShipmentProgress shipment={{ ...own, status: 'PACKED', nextActions: ['READY'], fulfilment: 'AFROTRANSACT_DELIVERY', fulfilmentMode: 'afrotransact_delivery', needsShipDetails: false }} editable loading={false} onMove={vi.fn()} />
    );
    expect(screen.queryByTestId('ship-details')).not.toBeInTheDocument();
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
  const afro: Progress = { status: 'READY', nextActions: [], trackingNumber: null, canBuyLabel: false, fulfilment: 'AFROTRANSACT_DELIVERY', fulfilmentMode: 'afrotransact_delivery', needsShipDetails: false };

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

  it('says nothing about AfroTransact for a seller-shipped or pickup order', () => {
    renderWithProviders(<ShipmentProgress shipment={{ ...afro, fulfilmentMode: 'seller_ships', status: 'PICKED_UP', nextActions: ['DELIVERED'] }} editable loading={false} onMove={vi.fn()} />);
    expect(screen.queryByTestId('afrotransact-note')).toBeNull();
    expect(screen.getByRole('button', { name: 'Mark delivered' })).toBeEnabled();
  });
});
