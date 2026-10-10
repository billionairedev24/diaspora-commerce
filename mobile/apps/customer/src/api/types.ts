/* Contract types used by the screens. BFF types come from @afrotransact/api-client (generated + Zod); bookings and
   identity types are generated into src/api/generated from contracts/openapi. */
import { type components as Bff } from '@afrotransact/api-client/generated/gateway-bff';

import { type components as Bookings } from './generated/bookings';
import { type components as Identity } from './generated/identity';

export type CheckoutSummary = Bff['schemas']['CheckoutSummary'];
export type PlaceOrderRequest = Bff['schemas']['PlaceOrderRequest'];
export type OrderConfirmation = Bff['schemas']['OrderConfirmation'];
export type SetupIntent = { clientSecret: string; publishableKey: string };
export type StartReturnRequest = { orderNumber: string; atin: string; reason: 'DAMAGED' | 'WRONG_ITEM' | 'NOT_AS_DESCRIBED' | 'NO_LONGER_NEEDED' };

export type Booking = Bookings['schemas']['Booking'];
export type BookingActivity = Bookings['schemas']['BookingActivity'];
export type CancellationQuote = Bookings['schemas']['CancellationQuote'];
export type CancelResponse = Bookings['schemas']['CancelResponse'];
export type CreateBookingRequest = Bookings['schemas']['CreateBookingRequest'];
export type CreateBookingResponse = Bookings['schemas']['CreateBookingResponse'];
export type PublicProvider = Bookings['schemas']['PublicProvider'];
export type PublicService = Bookings['schemas']['PublicService'];
export type PublicServiceListing = Bookings['schemas']['PublicServiceListing'];
export type PublicServiceSearch = Bookings['schemas']['PublicServiceSearch'];
export type AvailableDay = Bookings['schemas']['AvailableDay'];
export type AvailableSlot = Bookings['schemas']['AvailableSlot'];
export type BookingSummary = Bookings['schemas']['BookingSummary'];

export type Device = Identity['schemas']['Device'];
export type RegisterDeviceRequest = Identity['schemas']['RegisterDeviceRequest'];
export type NotificationPreferences = Identity['schemas']['NotificationPreferences'];

/** Shipment tracking (proposed BFF route GET /v1/account/orders/{orderNumber}/tracking — not in the contract yet;
    served by the in-app mock in dev). */
export interface OrderTracking {
  orderNumber: string;
  placedAt: string;
  totalMinor: number;
  currency: string;
  shipments: Array<{
    shipmentId: string;
    label: string;
    status: string;
    eta: string | null;
    method: 'afrotransact_delivery' | 'carrier' | 'express' | 'pickup';
    handover: 'hand' | 'door' | 'desk' | null;
    perishable: boolean;
    deliveryCode: string | null;
    driver: { name: string; initials: string } | null;
    items: Array<{ atin: string; title: string; quantity: number; priceMinor: number; returnable: boolean }>;
  }>;
}
