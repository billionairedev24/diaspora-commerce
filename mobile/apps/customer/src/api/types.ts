/* Contract types used by the screens. BFF types come from @afrotransact/api-client (generated + Zod); bookings and
   identity types are generated into src/api/generated from contracts/openapi. */
import { type components as Bff } from '@afrotransact/api-client/generated/gateway-bff';

import { type components as Bookings } from './generated/bookings';
import { type components as Identity } from './generated/identity';

/** GET /v1/session/policy (bearer callers get lastActivityAt and expiresAt = null). */
export type SessionPolicyBody = Bff['schemas']['SessionPolicy'];
export type CheckoutSummary = Bff['schemas']['CheckoutSummary'];
export type PlaceOrderRequest = Bff['schemas']['PlaceOrderRequest'];
export type OrderConfirmation = Bff['schemas']['OrderConfirmation'];
export type SetupIntent = { clientSecret: string; publishableKey: string };

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

export type FulfilmentMode = 'seller_ships' | 'afrotransact_delivery' | 'fulfilled_by_afrotransact' | 'pickup' | 'preorder_run';

/** GET /v1/account/orders/{orderNumber}/tracking → shipments[].proof (contracts/openapi/gateway-bff.yaml, DeliveryProof). */
export interface DeliveryProof {
  method: 'CUSTOMER' | 'SOMEONE_ELSE' | 'SAFE_PLACE' | 'PHOTO';
  recipientName: string | null;
  photoUrls: string[];
  at: string;
}

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
    method: 'afrotransact_delivery' | 'carrier' | 'express' | 'seller_ships' | 'pickup';
    /** Who moves it. The app names AfroTransact as the deliverer, and shows proof, only for the AfroTransact modes. */
    fulfilmentMode: FulfilmentMode;
    /** How AfroTransact handed it over (photos with short-lived links, who received it, when); null for every other mode. */
    proof: DeliveryProof | null;
    handover: 'hand' | 'door' | 'desk' | null;
    perishable: boolean;
    deliveryCode: string | null;
    driver: { name: string; initials: string } | null;
    items: Array<{ atin: string; title: string; quantity: number; priceMinor: number; returnable: boolean }>;
  }>;
}
