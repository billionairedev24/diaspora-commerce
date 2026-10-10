import { z } from 'zod';

import { type ApiClientOptions, type Requester } from './http';
import { streamLola } from './lola';
import { type Address, AddressSchema, type OrderSummary, OrderSummarySchema, PageOfSchema, type PaymentMethod, PaymentMethodSchema, type ReturnSummary, ReturnSummarySchema } from './schemas/account';
import { type SearchClickRequest, type SearchClickResult, SearchClickResultSchema } from './schemas/search';
import {
  type AddressInput,
  type AvailableDay,
  AvailableDaySchema,
  type AvailableSlot,
  AvailableSlotSchema,
  type BookingCancelled,
  BookingCancelledSchema,
  type BookingCreated,
  BookingCreatedSchema,
  type BookingQuote,
  BookingQuoteSchema,
  type BookingRequest,
  type BookingReview,
  BookingReviewSchema,
  type CancellationQuote,
  CancellationQuoteSchema,
  type CheckoutSummary,
  CheckoutSummarySchema,
  type CustomerBooking,
  CustomerBookingSchema,
  type LinkedAccounts,
  LinkedAccountsSchema,
  type LolaChatRequest,
  type LolaEvent,
  type LolaTurn,
  LolaTurnSchema,
  type NotificationPreferences,
  NotificationPreferencesSchema,
  type OrderConfirmation,
  OrderConfirmationSchema,
  type OrderDetail,
  OrderDetailSchema,
  type PasswordChange,
  type PlaceOrderRequest,
  type ProviderPage,
  ProviderPageSchema,
  type RecoveryCodes,
  RecoveryCodesSchema,
  type ReturnDetail,
  ReturnDetailSchema,
  type ReturnEligibility,
  ReturnEligibilitySchema,
  type SecurityOverview,
  SecurityOverviewSchema,
  type ServiceDetail,
  ServiceDetailSchema,
  type ServiceSearch,
  ServiceSearchSchema,
  type SetupIntent,
  SetupIntentSchema,
  type StartReturnRequest,
  type TotpSetup,
  TotpSetupSchema
} from './schemas/shopper';

const Void = z.undefined();
const enc = encodeURIComponent;

export interface SlotQuery {
  from?: string;
  days?: number;
  date?: string;
  memberId?: string;
  addOnIds?: string[];
  mode?: string;
}

const slotQuery = (q: SlotQuery): Record<string, string | undefined> => ({
  from: q.from,
  days: q.days === undefined ? undefined : String(q.days),
  date: q.date,
  memberId: q.memberId,
  addOnIds: q.addOnIds?.length ? q.addOnIds.join(',') : undefined,
  mode: q.mode
});

const idem = (key?: string): Record<string, string> | undefined => (key ? { 'Idempotency-Key': key } : undefined);

/** Account, checkout, services & bookings and Lola routes of the gateway BFF (spread into createApiClient). */
export function shopperMethods(request: Requester, options: ApiClientOptions) {
  return {
    /** Reports a click on a search result (organic or sponsored, S240). Fire and forget: never block navigation on it. */
    recordSearchClick: (body: SearchClickRequest): Promise<SearchClickResult> =>
      request('/v1/search/clicks', SearchClickResultSchema, { method: 'POST', body }),

    /* Addresses (identity, validated by geo) */
    createAddress: (input: AddressInput): Promise<Address> => request('/v1/account/addresses', AddressSchema, { method: 'POST', body: input }),
    updateAddress: (id: string, input: AddressInput): Promise<Address> => request(`/v1/account/addresses/${enc(id)}`, AddressSchema, { method: 'PATCH', body: input }),
    deleteAddress: (id: string): Promise<void> => request(`/v1/account/addresses/${enc(id)}`, Void, { method: 'DELETE' }),
    setDefaultAddress: (id: string): Promise<Address> => request(`/v1/account/addresses/${enc(id)}/default`, AddressSchema, { method: 'POST' }),

    /* Saved payment methods (payments) */
    createSetupIntent: (types?: string[]): Promise<SetupIntent> =>
      request('/v1/account/payment-methods/setup-intents', SetupIntentSchema, { method: 'POST', body: types ? { types } : {} }),
    setDefaultPaymentMethod: (id: string): Promise<PaymentMethod> => request(`/v1/account/payment-methods/${enc(id)}/default`, PaymentMethodSchema, { method: 'POST' }),
    deletePaymentMethod: (id: string): Promise<void> => request(`/v1/account/payment-methods/${enc(id)}`, Void, { method: 'DELETE' }),

    /* Orders */
    getOrderPage: (cursor?: string, signal?: AbortSignal) => request('/v1/account/orders', PageOfSchema(OrderSummarySchema), { query: { cursor, limit: '20' }, signal }),
    getOrder: (orderNumber: string, signal?: AbortSignal): Promise<OrderDetail> => request(`/v1/account/orders/${enc(orderNumber)}`, OrderDetailSchema, { signal }),
    cancelShipment: (orderNumber: string, shipmentId: string, reason?: string): Promise<OrderSummary> =>
      request(`/v1/account/orders/${enc(orderNumber)}/shipments/${enc(shipmentId)}/cancel`, OrderSummarySchema, { method: 'POST', body: reason ? { reason } : {} }),

    /* Returns (tax-returns) */
    getReturnEligibility: (orderNumber: string, signal?: AbortSignal): Promise<ReturnEligibility> =>
      request(`/v1/account/orders/${enc(orderNumber)}/returnable`, ReturnEligibilitySchema, { signal }),
    startReturn: (body: StartReturnRequest, idempotencyKey?: string): Promise<ReturnSummary> =>
      request('/v1/account/returns', ReturnSummarySchema, { method: 'POST', body, headers: idem(idempotencyKey) }),
    getReturn: (rmaId: string, signal?: AbortSignal): Promise<ReturnDetail> => request(`/v1/account/returns/${enc(rmaId)}`, ReturnDetailSchema, { signal }),
    cancelReturn: (rmaId: string): Promise<ReturnSummary> => request(`/v1/account/returns/${enc(rmaId)}/cancel`, ReturnSummarySchema, { method: 'POST' }),

    /* Login & security (identity) */
    getSecurity: (signal?: AbortSignal): Promise<SecurityOverview> => request('/v1/account/security', SecurityOverviewSchema, { signal }),
    changePassword: (body: PasswordChange): Promise<void> => request('/v1/account/password', Void, { method: 'PUT', body }),
    startTotp: (): Promise<TotpSetup> => request('/v1/account/mfa/totp', TotpSetupSchema, { method: 'POST' }),
    confirmTotp: (code: string): Promise<RecoveryCodes> => request('/v1/account/mfa/totp/confirm', RecoveryCodesSchema, { method: 'POST', body: { code } }),
    disableTotp: (): Promise<void> => request('/v1/account/mfa/totp', Void, { method: 'DELETE' }),
    startSmsMfa: () => request('/v1/account/mfa/sms', z.object({ codeExpiresAt: z.string().nullable() }), { method: 'POST' }),
    confirmSmsMfa: (code: string): Promise<RecoveryCodes> => request('/v1/account/mfa/sms/confirm', RecoveryCodesSchema, { method: 'POST', body: { code } }),
    disableSmsMfa: (): Promise<void> => request('/v1/account/mfa/sms', Void, { method: 'DELETE' }),
    regenerateRecoveryCodes: (): Promise<RecoveryCodes> => request('/v1/account/mfa/recovery-codes', RecoveryCodesSchema, { method: 'POST' }),
    removePasskey: (id: string): Promise<void> => request(`/v1/account/passkeys/${enc(id)}`, Void, { method: 'DELETE' }),
    revokeSession: (id: string): Promise<void> => request(`/v1/account/sessions/${enc(id)}`, Void, { method: 'DELETE' }),
    revokeOtherSessions: () => request('/v1/account/sessions/revoke-others', z.object({ revoked: z.number().int() }), { method: 'POST' }),
    removeDevice: (id: string): Promise<void> => request(`/v1/account/devices/${enc(id)}`, Void, { method: 'DELETE' }),
    unlinkAccount: (provider: string): Promise<LinkedAccounts> => request(`/v1/account/linked-accounts/${enc(provider)}`, LinkedAccountsSchema, { method: 'DELETE' }),
    getNotificationPreferences: (signal?: AbortSignal): Promise<NotificationPreferences> =>
      request('/v1/account/notification-preferences', NotificationPreferencesSchema, { signal }),
    saveNotificationPreferences: (body: NotificationPreferences): Promise<NotificationPreferences> =>
      request('/v1/account/notification-preferences', NotificationPreferencesSchema, { method: 'PUT', body }),

    /* Checkout (orders saga + Stripe) */
    getCheckoutSummary: (addressId?: string, signal?: AbortSignal): Promise<CheckoutSummary> =>
      request('/v1/checkout/summary', CheckoutSummarySchema, { query: { addressId }, signal }),
    placeOrder: (body: PlaceOrderRequest, idempotencyKey: string): Promise<OrderConfirmation> =>
      request('/v1/checkout/orders', OrderConfirmationSchema, { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }),
    getCheckoutStatus: (orderNumber: string): Promise<OrderConfirmation> => request(`/v1/checkout/orders/${enc(orderNumber)}`, OrderConfirmationSchema),
    confirmCheckoutPayment: (orderNumber: string): Promise<OrderConfirmation> =>
      request(`/v1/checkout/orders/${enc(orderNumber)}/confirm`, OrderConfirmationSchema, { method: 'POST' }),

    /* Services & bookings (bookings) */
    searchServices: (q: { q?: string; category?: string; zip?: string }, signal?: AbortSignal): Promise<ServiceSearch> =>
      request('/v1/services/search', ServiceSearchSchema, { query: q, signal }),
    getService: (id: string, signal?: AbortSignal): Promise<ServiceDetail> => request(`/v1/services/${enc(id)}`, ServiceDetailSchema, { signal }),
    getServiceDays: async (id: string, q: SlotQuery = {}, signal?: AbortSignal): Promise<AvailableDay[]> =>
      (await request(`/v1/services/${enc(id)}/days`, z.object({ days: z.array(AvailableDaySchema) }), { query: slotQuery(q), signal })).days,
    getServiceSlots: async (id: string, q: SlotQuery, signal?: AbortSignal): Promise<AvailableSlot[]> =>
      (await request(`/v1/services/${enc(id)}/slots`, z.object({ slots: z.array(AvailableSlotSchema) }), { query: slotQuery(q), signal })).slots,
    getBookingQuote: (id: string, q: SlotQuery = {}, signal?: AbortSignal): Promise<BookingQuote> =>
      request(`/v1/services/${enc(id)}/summary`, BookingQuoteSchema, { query: slotQuery(q), signal }),
    getProviderPage: (orgId: string, slug?: string, signal?: AbortSignal): Promise<ProviderPage> =>
      request(`/v1/service-providers/${enc(orgId)}`, ProviderPageSchema, { query: { slug }, signal }),
    bookService: (body: BookingRequest, idempotencyKey?: string): Promise<BookingCreated> =>
      request('/v1/services/bookings', BookingCreatedSchema, { method: 'POST', body, headers: idem(idempotencyKey) }),
    getMyBookings: async (signal?: AbortSignal): Promise<CustomerBooking[]> =>
      (await request('/v1/account/bookings', z.object({ items: z.array(CustomerBookingSchema) }), { signal })).items,
    getMyBooking: (id: string, signal?: AbortSignal): Promise<CustomerBooking> => request(`/v1/account/bookings/${enc(id)}`, CustomerBookingSchema, { signal }),
    getBookingCancellationQuote: (id: string, signal?: AbortSignal): Promise<CancellationQuote> =>
      request(`/v1/account/bookings/${enc(id)}/cancellation-quote`, CancellationQuoteSchema, { signal }),
    cancelBooking: (id: string, reason?: string, idempotencyKey?: string): Promise<BookingCancelled> =>
      request(`/v1/account/bookings/${enc(id)}/cancel`, BookingCancelledSchema, { method: 'POST', body: reason ? { reason } : {}, headers: idem(idempotencyKey) }),
    rescheduleBooking: (id: string, startAt: string, idempotencyKey?: string): Promise<CustomerBooking> =>
      request(`/v1/account/bookings/${enc(id)}/reschedule`, CustomerBookingSchema, { method: 'POST', body: { startAt }, headers: idem(idempotencyKey) }),
    answerProposedTime: (id: string, accept: boolean): Promise<CustomerBooking> =>
      request(`/v1/account/bookings/${enc(id)}/new-time/${accept ? 'accept' : 'decline'}`, CustomerBookingSchema, { method: 'POST' }),
    reviewBooking: (id: string, rating: number, text?: string): Promise<BookingReview> =>
      request(`/v1/account/bookings/${enc(id)}/review`, BookingReviewSchema, { method: 'POST', body: { rating, text: text ?? '' } }),
    syncBookingPayment: (id: string): Promise<CustomerBooking> => request(`/v1/account/bookings/${enc(id)}/payment/sync`, CustomerBookingSchema, { method: 'POST' }),

    /* Lola (ai-gateway SSE through the BFF) */
    chatWithLola: (body: LolaChatRequest, onEvent: (e: LolaEvent) => void, signal?: AbortSignal): Promise<void> => streamLola(options, body, onEvent, signal),
    getLolaHistory: async (sessionId: string, signal?: AbortSignal): Promise<LolaTurn[]> =>
      (await request(`/v1/lola/sessions/${enc(sessionId)}/messages`, z.object({ items: z.array(LolaTurnSchema) }), { signal })).items
  };
}

