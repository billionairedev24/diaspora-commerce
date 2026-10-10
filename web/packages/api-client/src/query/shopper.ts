import { keepPreviousData, queryOptions } from '@tanstack/react-query';

import { type ApiClient } from '../client';
import { type SlotQuery } from '../shopper-client';
import { accountKeys } from './keys';

/* Key factories and queryOptions for the account detail screens, checkout, services & bookings and Lola.
   Account families hang under accountKeys.all so signing out can drop every personal query at once. */

const MINUTE = 60_000;

export const orderKeys = {
  all: [...accountKeys.all, 'orders'] as const,
  lists: () => [...orderKeys.all, 'list'] as const,
  detail: (orderNumber: string) => [...orderKeys.all, 'detail', orderNumber] as const,
  returnable: (orderNumber: string) => [...orderKeys.all, 'returnable', orderNumber] as const
};

export const returnKeys = {
  all: [...accountKeys.all, 'returns'] as const,
  list: () => [...returnKeys.all, 'list'] as const,
  detail: (rmaId: string) => [...returnKeys.all, 'detail', rmaId] as const
};

export const securityKeys = {
  all: [...accountKeys.all, 'security'] as const,
  overview: () => [...securityKeys.all, 'overview'] as const,
  preferences: () => [...securityKeys.all, 'notification-preferences'] as const
};

export const checkoutKeys = {
  all: [...accountKeys.all, 'checkout'] as const,
  summary: (addressId: string | null) => [...checkoutKeys.all, 'summary', addressId ?? 'default'] as const
};

export const serviceKeys = {
  all: ['services'] as const,
  search: (q: { q?: string; category?: string; zip?: string }) => [...serviceKeys.all, 'search', q] as const,
  detail: (id: string) => [...serviceKeys.all, 'detail', id] as const,
  days: (id: string, q: SlotQuery) => [...serviceKeys.all, 'days', id, q] as const,
  slots: (id: string, q: SlotQuery) => [...serviceKeys.all, 'slots', id, q] as const,
  quote: (id: string, q: SlotQuery) => [...serviceKeys.all, 'quote', id, q] as const,
  provider: (orgId: string) => [...serviceKeys.all, 'provider', orgId] as const
};

export const bookingKeys = {
  all: [...accountKeys.all, 'bookings'] as const,
  list: () => [...bookingKeys.all, 'list'] as const,
  detail: (id: string) => [...bookingKeys.all, 'detail', id] as const,
  cancellationQuote: (id: string) => [...bookingKeys.all, 'cancellation-quote', id] as const
};

export const lolaKeys = {
  all: ['lola'] as const,
  history: (sessionId: string) => [...lolaKeys.all, 'history', sessionId] as const
};

export const shopperQueries = {
  addresses: (api: ApiClient) => queryOptions({ queryKey: accountKeys.addresses(), queryFn: () => api.getAddresses(), staleTime: MINUTE }),
  paymentMethods: (api: ApiClient) => queryOptions({ queryKey: accountKeys.paymentMethods(), queryFn: () => api.getPaymentMethods(), staleTime: MINUTE }),
  orders: (api: ApiClient) => queryOptions({ queryKey: orderKeys.lists(), queryFn: ({ signal }) => api.getOrderPage(undefined, signal), staleTime: 30_000 }),
  order: (api: ApiClient, orderNumber: string) =>
    queryOptions({ queryKey: orderKeys.detail(orderNumber), queryFn: ({ signal }) => api.getOrder(orderNumber, signal), staleTime: 30_000 }),
  returnable: (api: ApiClient, orderNumber: string) =>
    queryOptions({ queryKey: orderKeys.returnable(orderNumber), queryFn: ({ signal }) => api.getReturnEligibility(orderNumber, signal), staleTime: 0 }),
  returns: (api: ApiClient) => queryOptions({ queryKey: returnKeys.list(), queryFn: () => api.getReturns(), staleTime: 30_000 }),
  returnDetail: (api: ApiClient, rmaId: string) => queryOptions({ queryKey: returnKeys.detail(rmaId), queryFn: ({ signal }) => api.getReturn(rmaId, signal) }),
  security: (api: ApiClient) => queryOptions({ queryKey: securityKeys.overview(), queryFn: ({ signal }) => api.getSecurity(signal), staleTime: 0 }),
  notificationPreferences: (api: ApiClient) =>
    queryOptions({ queryKey: securityKeys.preferences(), queryFn: ({ signal }) => api.getNotificationPreferences(signal), staleTime: MINUTE }),
  checkoutSummary: (api: ApiClient, addressId: string | null) =>
    queryOptions({ queryKey: checkoutKeys.summary(addressId), queryFn: ({ signal }) => api.getCheckoutSummary(addressId ?? undefined, signal), staleTime: 0, placeholderData: keepPreviousData }),
  serviceSearch: (api: ApiClient, q: { q?: string; category?: string; zip?: string }) =>
    queryOptions({ queryKey: serviceKeys.search(q), queryFn: ({ signal }) => api.searchServices(q, signal), staleTime: MINUTE }),
  service: (api: ApiClient, id: string) => queryOptions({ queryKey: serviceKeys.detail(id), queryFn: ({ signal }) => api.getService(id, signal), staleTime: 5 * MINUTE }),
  // Availability is live (bookings answers Cache-Control: no-store): always refetch.
  serviceDays: (api: ApiClient, id: string, q: SlotQuery) =>
    queryOptions({ queryKey: serviceKeys.days(id, q), queryFn: ({ signal }) => api.getServiceDays(id, q, signal), staleTime: 0 }),
  serviceSlots: (api: ApiClient, id: string, q: SlotQuery) =>
    queryOptions({ queryKey: serviceKeys.slots(id, q), queryFn: ({ signal }) => api.getServiceSlots(id, q, signal), staleTime: 0, enabled: !!q.date }),
  bookingQuote: (api: ApiClient, id: string, q: SlotQuery) =>
    queryOptions({ queryKey: serviceKeys.quote(id, q), queryFn: ({ signal }) => api.getBookingQuote(id, q, signal), staleTime: MINUTE }),
  provider: (api: ApiClient, orgId: string) =>
    queryOptions({ queryKey: serviceKeys.provider(orgId), queryFn: ({ signal }) => api.getProviderPage(orgId, undefined, signal), staleTime: 5 * MINUTE }),
  bookings: (api: ApiClient) => queryOptions({ queryKey: bookingKeys.list(), queryFn: ({ signal }) => api.getMyBookings(signal), staleTime: 30_000 }),
  cancellationQuote: (api: ApiClient, id: string) =>
    queryOptions({ queryKey: bookingKeys.cancellationQuote(id), queryFn: ({ signal }) => api.getBookingCancellationQuote(id, signal), staleTime: 0 }),
  lolaHistory: (api: ApiClient, sessionId: string) =>
    queryOptions({ queryKey: lolaKeys.history(sessionId), queryFn: ({ signal }) => api.getLolaHistory(sessionId, signal), staleTime: Infinity })
};
