/* One place that wires the customer app's API clients:
   - gateway BFF via @afrotransact/api-client (Zod-validated) + the BFF routes it does not wrap yet,
   - identity /v1/me/* (push devices, notification preferences),
   - bookings (customer + public endpoints; not proxied by the BFF yet).
   In dev (env.mocks) every request goes to the in-app mock router instead of the network. */
import { createApiClient, type FetchLike } from '@afrotransact/api-client';

import { env } from '@/config/env';

import { createAuthFetch, createJsonRequester, type TokenSource } from './http';
import { mockFetch } from './mock/router';
import {
  type AvailableDay,
  type AvailableSlot,
  type Booking,
  type BookingActivity,
  type BookingSummary,
  type CancellationQuote,
  type CancelResponse,
  type CheckoutSummary,
  type CreateBookingRequest,
  type CreateBookingResponse,
  type Device,
  type NotificationPreferences,
  type OrderConfirmation,
  type OrderTracking,
  type PlaceOrderRequest,
  type PublicProvider,
  type PublicService,
  type PublicServiceSearch,
  type RegisterDeviceRequest,
  type SetupIntent,
  type StartReturnRequest
} from './types';

export function createCustomerApi(tokens: TokenSource, network: FetchLike = (r) => fetch(r)) {
  const transport: FetchLike = env.mocks ? mockFetch : network;
  const authed = createAuthFetch(tokens, transport);
  const bff = createApiClient({ baseUrl: env.bffUrl, fetch: authed, headers: { 'Accept-Language': 'en-US' } });
  const bffJson = createJsonRequester(env.bffUrl, authed);
  const identity = createJsonRequester(env.identityApiUrl, authed);
  const bookings = createJsonRequester(env.bookingsUrl, authed);

  return {
    bff,
    checkout: {
      summary: (addressId?: string) => bffJson<CheckoutSummary>('/v1/checkout/summary', { query: { addressId } }),
      placeOrder: (body: PlaceOrderRequest, idempotencyKey: string) =>
        bffJson<OrderConfirmation>('/v1/checkout/orders', { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } })
    },
    account: {
      createSetupIntent: () => bffJson<SetupIntent>('/v1/account/payment-methods/setup-intents', { method: 'POST' }),
      deletePaymentMethod: (id: string) => bffJson<void>(`/v1/account/payment-methods/${encodeURIComponent(id)}`, { method: 'DELETE' }),
      startReturn: (body: StartReturnRequest) => bffJson<{ rmaId: string; orderNumber: string; title: string; status: string; refundMinor: number }>('/v1/account/returns', { method: 'POST', body }),
      /** Proposed BFF route (see OrderTracking). */
      tracking: (orderNumber: string) => bffJson<OrderTracking>(`/v1/account/orders/${encodeURIComponent(orderNumber)}/tracking`)
    },
    identity: {
      registerDevice: (body: RegisterDeviceRequest) => identity<Device>('/v1/me/devices', { method: 'POST', body }),
      unregisterDevice: (token: string) => identity<void>('/v1/me/devices', { method: 'DELETE', body: { token } }),
      getPreferences: () => identity<NotificationPreferences>('/v1/me/notification-preferences'),
      putPreferences: (body: { email: boolean; sms: boolean; push: boolean; marketingOptIn: boolean }) =>
        identity<NotificationPreferences>('/v1/me/notification-preferences', { method: 'PUT', body })
    },
    bookings: {
      searchServices: (q?: string, category?: string) => bookings<PublicServiceSearch>('/v1/public/services/search', { query: { q, category } }),
      provider: (orgId: string) => bookings<PublicProvider>(`/v1/public/providers/${encodeURIComponent(orgId)}`),
      service: (serviceId: string) => bookings<PublicService>(`/v1/public/services/${encodeURIComponent(serviceId)}`),
      days: (serviceId: string, from?: string, days = 21) => bookings<AvailableDay[]>(`/v1/public/services/${encodeURIComponent(serviceId)}/days`, { query: { from, days } }),
      slots: (serviceId: string, date: string) => bookings<AvailableSlot[]>(`/v1/public/services/${encodeURIComponent(serviceId)}/slots`, { query: { date } }),
      summary: (serviceId: string) => bookings<BookingSummary>(`/v1/public/services/${encodeURIComponent(serviceId)}/summary`),
      create: (body: CreateBookingRequest, idempotencyKey: string) =>
        bookings<CreateBookingResponse>('/v1/bookings', { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }),
      mine: () => bookings<Booking[]>('/v1/bookings/mine'),
      get: (id: string) => bookings<Booking>(`/v1/bookings/${encodeURIComponent(id)}`),
      history: (id: string) => bookings<BookingActivity[]>(`/v1/bookings/${encodeURIComponent(id)}/history`),
      quote: (id: string) => bookings<CancellationQuote>(`/v1/bookings/${encodeURIComponent(id)}/cancellation-quote`),
      cancel: (id: string, idempotencyKey: string, reason?: string) =>
        bookings<CancelResponse>(`/v1/bookings/${encodeURIComponent(id)}/cancel`, { method: 'POST', body: { reason }, headers: { 'Idempotency-Key': idempotencyKey } }),
      reschedule: (id: string, startAt: string) => bookings<Booking>(`/v1/bookings/${encodeURIComponent(id)}/reschedule`, { method: 'POST', body: { startAt } }),
      acceptNewTime: (id: string) => bookings<Booking>(`/v1/bookings/${encodeURIComponent(id)}/new-time/accept`, { method: 'POST' }),
      declineNewTime: (id: string) => bookings<Booking>(`/v1/bookings/${encodeURIComponent(id)}/new-time/decline`, { method: 'POST', body: {} }),
      syncPayment: (id: string) => bookings<Booking>(`/v1/bookings/${encodeURIComponent(id)}/payment/sync`, { method: 'POST' })
    }
  };
}

export type CustomerApi = ReturnType<typeof createCustomerApi>;
