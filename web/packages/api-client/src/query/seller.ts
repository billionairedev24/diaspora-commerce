import { keepPreviousData, queryOptions } from '@tanstack/react-query';

import { type ListingsQuery, type SellerApiClient, type SellerOrdersQuery } from '../seller-client';

/* TanStack Query key factories for the Seller Center & Provider Hub. Everything hangs off ['seller', orgId] so
   switching organisation (org switcher) or signing out invalidates a whole family at once. */

export const sellerKeys = {
  all: ['seller'] as const,
  session: () => [...sellerKeys.all, 'session'] as const,
  org: (orgId: string) => [...sellerKeys.all, 'org', orgId] as const,
  onboarding: (orgId: string) => [...sellerKeys.org(orgId), 'onboarding'] as const,
  compliance: (orgId: string) => [...sellerKeys.org(orgId), 'compliance'] as const,
  dashboard: (orgId: string, rangeDays: number) => [...sellerKeys.org(orgId), 'dashboard', rangeDays] as const,
  orders: (orgId: string) => [...sellerKeys.org(orgId), 'orders'] as const,
  orderPage: (orgId: string, q: SellerOrdersQuery) => [...sellerKeys.orders(orgId), 'page', q] as const,
  order: (orgId: string, orderNumber: string) => [...sellerKeys.orders(orgId), 'detail', orderNumber] as const,
  shipFrom: (orgId: string) => [...sellerKeys.org(orgId), 'ship-from'] as const,
  listings: (orgId: string) => [...sellerKeys.org(orgId), 'listings'] as const,
  listingPage: (orgId: string, q: ListingsQuery) => [...sellerKeys.listings(orgId), 'page', q] as const,
  categories: () => [...sellerKeys.all, 'catalog', 'categories'] as const,
  productType: (code: string) => [...sellerKeys.all, 'catalog', 'product-type', code] as const,
  categoryRequests: (orgId: string) => [...sellerKeys.org(orgId), 'category-requests'] as const,
  inventory: (orgId: string, lowStock: boolean) => [...sellerKeys.org(orgId), 'inventory', lowStock] as const,
  payouts: (orgId: string) => [...sellerKeys.org(orgId), 'payouts'] as const,
  billing: (orgId: string) => [...sellerKeys.org(orgId), 'billing'] as const,
  team: (orgId: string) => [...sellerKeys.org(orgId), 'team'] as const,
  calendar: (orgId: string, from: string, to: string) => [...sellerKeys.org(orgId), 'calendar', from, to] as const,
  calendars: (orgId: string) => [...sellerKeys.org(orgId), 'calendar'] as const,
  requests: (orgId: string) => [...sellerKeys.org(orgId), 'requests'] as const,
  hours: (orgId: string) => [...sellerKeys.org(orgId), 'hours'] as const,
  services: (orgId: string) => [...sellerKeys.org(orgId), 'services'] as const,
  policy: (orgId: string) => [...sellerKeys.org(orgId), 'policy'] as const,
  earnings: (orgId: string, from?: string, to?: string) => [...sellerKeys.org(orgId), 'earnings', from ?? '', to ?? ''] as const,
  publicPage: (orgId: string) => [...sellerKeys.org(orgId), 'public-page'] as const
};

const MINUTE = 60_000;

/** queryOptions for every seller screen (same keys, fetchers and stale times on server and client). */
export const sellerQueries = {
  session: (api: SellerApiClient) => queryOptions({ queryKey: sellerKeys.session(), queryFn: ({ signal }) => api.getSession(signal), staleTime: MINUTE }),
  onboarding: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.onboarding(orgId), queryFn: ({ signal }) => api.getOnboarding(signal), staleTime: MINUTE }),
  compliance: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.compliance(orgId), queryFn: ({ signal }) => api.getCompliance(signal), staleTime: 5 * MINUTE }),
  dashboard: (api: SellerApiClient, orgId: string, rangeDays: number) =>
    queryOptions({ queryKey: sellerKeys.dashboard(orgId, rangeDays), queryFn: ({ signal }) => api.getDashboard(rangeDays, signal), staleTime: MINUTE }),
  orders: (api: SellerApiClient, orgId: string, q: SellerOrdersQuery) =>
    queryOptions({ queryKey: sellerKeys.orderPage(orgId, q), queryFn: ({ signal }) => api.listOrders(q, signal), placeholderData: keepPreviousData, staleTime: 15_000 }),
  order: (api: SellerApiClient, orgId: string, orderNumber: string) =>
    queryOptions({ queryKey: sellerKeys.order(orgId, orderNumber), queryFn: ({ signal }) => api.getOrder(orderNumber, signal), staleTime: 15_000 }),
  shipFrom: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.shipFrom(orgId), queryFn: ({ signal }) => api.listShipFrom(signal), staleTime: 5 * MINUTE }),
  listings: (api: SellerApiClient, orgId: string, q: ListingsQuery) =>
    queryOptions({ queryKey: sellerKeys.listingPage(orgId, q), queryFn: ({ signal }) => api.listListings(q, signal), placeholderData: keepPreviousData, staleTime: 30_000 }),
  categories: (api: SellerApiClient) =>
    queryOptions({ queryKey: sellerKeys.categories(), queryFn: ({ signal }) => api.getCategoryTree(signal), staleTime: 30 * MINUTE }),
  productType: (api: SellerApiClient, code: string) =>
    queryOptions({ queryKey: sellerKeys.productType(code), queryFn: ({ signal }) => api.getProductType(code, signal), staleTime: 30 * MINUTE, enabled: code !== '' }),
  categoryRequests: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.categoryRequests(orgId), queryFn: ({ signal }) => api.listCategoryRequests(signal), staleTime: MINUTE }),
  inventory: (api: SellerApiClient, orgId: string, lowStock: boolean) =>
    queryOptions({ queryKey: sellerKeys.inventory(orgId, lowStock), queryFn: ({ signal }) => api.getInventory(lowStock, signal), staleTime: 30_000 }),
  payouts: (api: SellerApiClient, orgId: string) => queryOptions({ queryKey: sellerKeys.payouts(orgId), queryFn: ({ signal }) => api.getPayouts(signal), staleTime: MINUTE }),
  billing: (api: SellerApiClient, orgId: string) => queryOptions({ queryKey: sellerKeys.billing(orgId), queryFn: ({ signal }) => api.getBilling(signal), staleTime: MINUTE }),
  team: (api: SellerApiClient, orgId: string) => queryOptions({ queryKey: sellerKeys.team(orgId), queryFn: ({ signal }) => api.getTeam(signal), staleTime: MINUTE }),
  calendar: (api: SellerApiClient, orgId: string, from: string, to: string) =>
    queryOptions({ queryKey: sellerKeys.calendar(orgId, from, to), queryFn: ({ signal }) => api.getCalendar(from, to, signal), placeholderData: keepPreviousData, staleTime: 15_000 }),
  requests: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.requests(orgId), queryFn: ({ signal }) => api.listBookingRequests(signal), staleTime: 15_000 }),
  hours: (api: SellerApiClient, orgId: string) => queryOptions({ queryKey: sellerKeys.hours(orgId), queryFn: ({ signal }) => api.getWorkingHours(signal), staleTime: MINUTE }),
  services: (api: SellerApiClient, orgId: string) => queryOptions({ queryKey: sellerKeys.services(orgId), queryFn: ({ signal }) => api.listServices(signal), staleTime: MINUTE }),
  policy: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.policy(orgId), queryFn: ({ signal }) => api.getCancellationPolicy(signal), staleTime: MINUTE }),
  earnings: (api: SellerApiClient, orgId: string, from?: string, to?: string) =>
    queryOptions({ queryKey: sellerKeys.earnings(orgId, from, to), queryFn: ({ signal }) => api.getEarnings(from, to, signal), staleTime: MINUTE }),
  publicPage: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.publicPage(orgId), queryFn: ({ signal }) => api.getPublicPage(signal), staleTime: MINUTE })
};
