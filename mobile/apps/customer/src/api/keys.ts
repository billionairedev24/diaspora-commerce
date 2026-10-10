/* TanStack Query key factories for the mobile-only resources. BFF resources reuse the factories of
   @afrotransact/api-client (cartKeys, productKeys, searchKeys, accountKeys) so keys stay identical to the web. */
export { accountKeys, cartKeys, productKeys, searchKeys } from '@afrotransact/api-client';

export const checkoutKeys = {
  all: ['checkout'] as const,
  summary: (addressId: string | null) => [...checkoutKeys.all, 'summary', addressId] as const
};

export const orderKeys = {
  all: ['orders'] as const,
  tracking: (orderNumber: string) => [...orderKeys.all, 'tracking', orderNumber] as const
};

export const bookingKeys = {
  all: ['bookings'] as const,
  services: (q: string) => [...bookingKeys.all, 'services', q] as const,
  provider: (orgId: string) => [...bookingKeys.all, 'provider', orgId] as const,
  service: (serviceId: string) => [...bookingKeys.all, 'service', serviceId] as const,
  days: (serviceId: string) => [...bookingKeys.all, 'days', serviceId] as const,
  slots: (serviceId: string, date: string) => [...bookingKeys.all, 'slots', serviceId, date] as const,
  summary: (serviceId: string) => [...bookingKeys.all, 'summary', serviceId] as const,
  mine: () => [...bookingKeys.all, 'mine'] as const,
  detail: (id: string) => [...bookingKeys.all, 'detail', id] as const,
  history: (id: string) => [...bookingKeys.all, 'history', id] as const,
  quote: (id: string) => [...bookingKeys.all, 'quote', id] as const
};

export const identityKeys = {
  all: ['identity'] as const,
  preferences: () => [...identityKeys.all, 'notification-preferences'] as const
};
