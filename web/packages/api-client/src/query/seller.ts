import { keepPreviousData, queryOptions } from '@tanstack/react-query';

import { type BrandScope, type Cadence, type ImageLibraryQuery } from '../schemas/seller';
import { type ImportRowsQuery, type ListingsQuery, type SellerApiClient, type SellerOrdersQuery } from '../seller-client';

/* TanStack Query key factories for the Seller Center & Provider Hub. Everything hangs off ['seller', orgId] so
   switching organisation (org switcher) or signing out invalidates a whole family at once. */

export const sellerKeys = {
  all: ['seller'] as const,
  session: () => [...sellerKeys.all, 'session'] as const,
  org: (orgId: string) => [...sellerKeys.all, 'org', orgId] as const,
  onboarding: (orgId: string) => [...sellerKeys.org(orgId), 'onboarding'] as const,
  onboardingPlans: (orgId: string) => [...sellerKeys.onboarding(orgId), 'plans'] as const,
  compliance: (orgId: string) => [...sellerKeys.org(orgId), 'compliance'] as const,
  dashboard: (orgId: string, rangeDays: number) => [...sellerKeys.org(orgId), 'dashboard', rangeDays] as const,
  assistant: (orgId: string) => [...sellerKeys.org(orgId), 'assistant'] as const,
  orders: (orgId: string) => [...sellerKeys.org(orgId), 'orders'] as const,
  exports: (orgId: string) => [...sellerKeys.org(orgId), 'exports'] as const,
  exportJob: (orgId: string, id: string) => [...sellerKeys.exports(orgId), id] as const,
  orderPage: (orgId: string, q: SellerOrdersQuery) => [...sellerKeys.orders(orgId), 'page', q] as const,
  order: (orgId: string, orderNumber: string) => [...sellerKeys.orders(orgId), 'detail', orderNumber] as const,
  shipFrom: (orgId: string) => [...sellerKeys.org(orgId), 'ship-from'] as const,
  runPickups: (orgId: string) => [...sellerKeys.org(orgId), 'run-pickups'] as const,
  runPickup: (orgId: string, runId: string) => [...sellerKeys.runPickups(orgId), runId] as const,
  businessPage: (orgId: string) => [...sellerKeys.org(orgId), 'business-page'] as const,
  listings: (orgId: string) => [...sellerKeys.org(orgId), 'listings'] as const,
  offers: (orgId: string, productId: string) => [...sellerKeys.listings(orgId), 'offers', productId] as const,
  listingPage: (orgId: string, q: ListingsQuery) => [...sellerKeys.listings(orgId), 'page', q] as const,
  categories: () => [...sellerKeys.all, 'catalog', 'categories'] as const,
  productType: (code: string) => [...sellerKeys.all, 'catalog', 'product-type', code] as const,
  product: (orgId: string, productId: string) => [...sellerKeys.listings(orgId), 'product', productId] as const,
  productDrafts: (orgId: string) => [...sellerKeys.org(orgId), 'product-drafts'] as const,
  productDraft: (orgId: string, id: string) => [...sellerKeys.productDrafts(orgId), id] as const,
  listingContext: (orgId: string) => [...sellerKeys.org(orgId), 'listing-context'] as const,
  imports: (orgId: string) => [...sellerKeys.org(orgId), 'imports'] as const,
  importPage: (orgId: string, page: number, size: number) => [...sellerKeys.imports(orgId), 'page', page, size] as const,
  importJob: (orgId: string, id: string) => [...sellerKeys.imports(orgId), 'detail', id] as const,
  importRows: (orgId: string, id: string, q: ImportRowsQuery) => [...sellerKeys.importJob(orgId, id), 'rows', q] as const,
  imageLibrary: (orgId: string) => [...sellerKeys.org(orgId), 'image-library'] as const,
  imageLibraryPage: (orgId: string, q: ImageLibraryQuery) => [...sellerKeys.imageLibrary(orgId), q] as const,
  brands: (orgId: string, scope: BrandScope, q: string) => [...sellerKeys.org(orgId), 'brands', scope.category ?? '', scope.productType ?? '', q] as const,
  categoryRequests: (orgId: string) => [...sellerKeys.org(orgId), 'category-requests'] as const,
  inventory: (orgId: string, lowStock: boolean) => [...sellerKeys.org(orgId), 'inventory', lowStock] as const,
  payouts: (orgId: string) => [...sellerKeys.org(orgId), 'payouts'] as const,
  taxStatement: (orgId: string, month: string) => [...sellerKeys.org(orgId), 'tax-statement', month] as const,
  fees: (orgId: string) => [...sellerKeys.org(orgId), 'fees'] as const,
  billing: (orgId: string) => [...sellerKeys.org(orgId), 'billing'] as const,
  billingRewards: (orgId: string) => [...sellerKeys.billing(orgId), 'rewards'] as const,
  planChangePreview: (orgId: string, planCode: string, cadence: string) => [...sellerKeys.billing(orgId), 'preview', planCode, cadence] as const,
  team: (orgId: string) => [...sellerKeys.org(orgId), 'team'] as const,
  calendar: (orgId: string, from: string, to: string) => [...sellerKeys.org(orgId), 'calendar', from, to] as const,
  calendars: (orgId: string) => [...sellerKeys.org(orgId), 'calendar'] as const,
  requests: (orgId: string) => [...sellerKeys.org(orgId), 'requests'] as const,
  hours: (orgId: string) => [...sellerKeys.org(orgId), 'hours'] as const,
  services: (orgId: string) => [...sellerKeys.org(orgId), 'services'] as const,
  policy: (orgId: string) => [...sellerKeys.org(orgId), 'policy'] as const,
  earnings: (orgId: string, from?: string, to?: string) => [...sellerKeys.org(orgId), 'earnings', from ?? '', to ?? ''] as const,
  coupons: (orgId: string) => [...sellerKeys.org(orgId), 'coupons'] as const,
  reviews: (orgId: string) => [...sellerKeys.org(orgId), 'reviews'] as const,
  reviewList: (orgId: string, unanswered: boolean, page: number) => [...sellerKeys.reviews(orgId), { unanswered, page }] as const,
  returns: (orgId: string, status?: string) => [...sellerKeys.org(orgId), 'returns', status ?? ''] as const,
  disputes: (orgId: string, status?: string) => [...sellerKeys.org(orgId), 'disputes', status ?? ''] as const
};

const MINUTE = 60_000;

/** Combines TanStack's abort signal with a wall-clock timeout (Seller Center session). */
function sessionSignal(signal: AbortSignal, timeoutMs?: number): AbortSignal {
  if (!timeoutMs || (typeof AbortSignal.any !== 'function' && typeof AbortSignal.timeout !== 'function')) {
    return signal;
  }
  return AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]);
}

/** queryOptions for every seller screen (same keys, fetchers and stale times on server and client). */
export const sellerQueries = {
  session: (api: SellerApiClient, opts?: { timeoutMs?: number }) =>
    queryOptions({
      queryKey: sellerKeys.session(),
      queryFn: ({ signal }) => api.getSession(sessionSignal(signal, opts?.timeoutMs)),
      staleTime: MINUTE,
      refetchOnWindowFocus: false
    }),
  onboarding: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.onboarding(orgId), queryFn: ({ signal }) => api.getOnboarding(signal), staleTime: MINUTE }),
  onboardingPlans: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.onboardingPlans(orgId), queryFn: ({ signal }) => api.getOnboardingPlans(signal), staleTime: 10 * MINUTE }),
  compliance: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.compliance(orgId), queryFn: ({ signal }) => api.getCompliance(signal), staleTime: 5 * MINUTE }),
  dashboard: (api: SellerApiClient, orgId: string, rangeDays: number) =>
    queryOptions({ queryKey: sellerKeys.dashboard(orgId, rangeDays), queryFn: ({ signal }) => api.getDashboard(rangeDays, signal), staleTime: MINUTE }),
  orders: (api: SellerApiClient, orgId: string, q: SellerOrdersQuery) =>
    queryOptions({ queryKey: sellerKeys.orderPage(orgId, q), queryFn: ({ signal }) => api.listOrders(q, signal), placeholderData: keepPreviousData, staleTime: 15_000 }),
  exportJob: (api: SellerApiClient, orgId: string, id: string) =>
    queryOptions({ queryKey: sellerKeys.exportJob(orgId, id), queryFn: ({ signal }) => api.getExport(id, signal), staleTime: 0 }),
  order: (api: SellerApiClient, orgId: string, orderNumber: string) =>
    queryOptions({ queryKey: sellerKeys.order(orgId, orderNumber), queryFn: ({ signal }) => api.getOrder(orderNumber, signal), staleTime: 15_000 }),
  offers: (api: SellerApiClient, orgId: string, productId: string) =>
    queryOptions({ queryKey: sellerKeys.offers(orgId, productId), queryFn: ({ signal }) => api.listOffers(productId, signal), staleTime: 30_000 }),
  businessPage: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.businessPage(orgId), queryFn: ({ signal }) => api.getBusinessPage(signal), staleTime: MINUTE }),
  runPickups: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.runPickups(orgId), queryFn: ({ signal }) => api.listRunPickups(signal), staleTime: 30_000, refetchInterval: 60_000 }),
  runPickup: (api: SellerApiClient, orgId: string, runId: string) =>
    queryOptions({ queryKey: sellerKeys.runPickup(orgId, runId), queryFn: ({ signal }) => api.getRunPickup(runId, signal), staleTime: 30_000, refetchInterval: 60_000 }),
  shipFrom: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.shipFrom(orgId), queryFn: ({ signal }) => api.listShipFrom(signal), staleTime: 5 * MINUTE }),
  listings: (api: SellerApiClient, orgId: string, q: ListingsQuery) =>
    queryOptions({ queryKey: sellerKeys.listingPage(orgId, q), queryFn: ({ signal }) => api.listListings(q, signal), placeholderData: keepPreviousData, staleTime: 30_000 }),
  categories: (api: SellerApiClient) =>
    queryOptions({ queryKey: sellerKeys.categories(), queryFn: ({ signal }) => api.getCategoryTree(signal), staleTime: 30 * MINUTE }),
  productType: (api: SellerApiClient, code: string) =>
    queryOptions({ queryKey: sellerKeys.productType(code), queryFn: ({ signal }) => api.getProductType(code, signal), staleTime: 30 * MINUTE, enabled: code !== '' }),
  product: (api: SellerApiClient, orgId: string, productId: string) =>
    queryOptions({ queryKey: sellerKeys.product(orgId, productId), queryFn: ({ signal }) => api.getProduct(productId, signal), staleTime: 0, enabled: productId !== '' }),
  productDrafts: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.productDrafts(orgId), queryFn: ({ signal }) => api.listProductDrafts(signal), staleTime: 15_000 }),
  productDraft: (api: SellerApiClient, orgId: string, id: string) =>
    queryOptions({ queryKey: sellerKeys.productDraft(orgId, id), queryFn: ({ signal }) => api.getProductDraft(id, signal), staleTime: 0, enabled: id !== '' }),
  listingContext: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.listingContext(orgId), queryFn: ({ signal }) => api.getListingContext(signal), staleTime: 5 * MINUTE }),
  imports: (api: SellerApiClient, orgId: string, page: number, size: number) =>
    queryOptions({ queryKey: sellerKeys.importPage(orgId, page, size), queryFn: ({ signal }) => api.listImports(page, size, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  importJob: (api: SellerApiClient, orgId: string, id: string) =>
    queryOptions({ queryKey: sellerKeys.importJob(orgId, id), queryFn: ({ signal }) => api.getImport(id, signal), staleTime: 0 }),
  importRows: (api: SellerApiClient, orgId: string, id: string, q: ImportRowsQuery) =>
    queryOptions({ queryKey: sellerKeys.importRows(orgId, id, q), queryFn: ({ signal }) => api.listImportRows(id, q, signal), placeholderData: keepPreviousData, staleTime: 10_000 }),
  imageLibrary: (api: SellerApiClient, orgId: string, q: ImageLibraryQuery) =>
    queryOptions({ queryKey: sellerKeys.imageLibraryPage(orgId, q), queryFn: ({ signal }) => api.listImageLibrary(q, signal), placeholderData: keepPreviousData, staleTime: 5_000 }),
  /** The brands of a category; `enabled` only once a category is chosen. */
  brands: (api: SellerApiClient, orgId: string, scope: BrandScope, q: string) =>
    queryOptions({
      queryKey: sellerKeys.brands(orgId, scope, q),
      queryFn: ({ signal }) => api.listBrands(scope, q, signal),
      enabled: Boolean(scope.category || scope.productType),
      placeholderData: keepPreviousData,
      staleTime: 60_000
    }),
  categoryRequests: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.categoryRequests(orgId), queryFn: ({ signal }) => api.listCategoryRequests(signal), staleTime: MINUTE }),
  inventory: (api: SellerApiClient, orgId: string, lowStock: boolean) =>
    queryOptions({ queryKey: sellerKeys.inventory(orgId, lowStock), queryFn: ({ signal }) => api.getInventory(lowStock, signal), staleTime: 30_000 }),
  payouts: (api: SellerApiClient, orgId: string) => queryOptions({ queryKey: sellerKeys.payouts(orgId), queryFn: ({ signal }) => api.getPayouts(signal), staleTime: MINUTE }),
  taxStatement: (api: SellerApiClient, orgId: string, month: string) =>
    queryOptions({ queryKey: sellerKeys.taxStatement(orgId, month), queryFn: ({ signal }) => api.getTaxStatement(month, signal), staleTime: MINUTE }),
  fees: (api: SellerApiClient, orgId: string) => queryOptions({ queryKey: sellerKeys.fees(orgId), queryFn: ({ signal }) => api.getFees(signal), staleTime: 5 * MINUTE }),
  billing: (api: SellerApiClient, orgId: string) => queryOptions({ queryKey: sellerKeys.billing(orgId), queryFn: ({ signal }) => api.getBilling(signal), staleTime: MINUTE }),
  billingRewards: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.billingRewards(orgId), queryFn: ({ signal }) => api.getBillingRewards(signal), staleTime: MINUTE }),
  planChangePreview: (api: SellerApiClient, orgId: string, planCode: string, cadence: Cadence) =>
    queryOptions({
      queryKey: sellerKeys.planChangePreview(orgId, planCode, cadence),
      queryFn: ({ signal }) => api.previewPlanChange(planCode, cadence, signal),
      enabled: Boolean(planCode),
      staleTime: 30_000
    }),
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
  reviews: (api: SellerApiClient, orgId: string, unanswered: boolean, page: number) =>
    queryOptions({ queryKey: sellerKeys.reviewList(orgId, unanswered, page), queryFn: ({ signal }) => api.listReviews({ unanswered, page, size: 20 }, signal), staleTime: MINUTE }),
  coupons: (api: SellerApiClient, orgId: string) =>
    queryOptions({ queryKey: sellerKeys.coupons(orgId), queryFn: ({ signal }) => api.listCoupons(signal), staleTime: MINUTE }),
  returns: (api: SellerApiClient, orgId: string, status?: string) =>
    queryOptions({ queryKey: sellerKeys.returns(orgId, status), queryFn: ({ signal }) => api.listReturns(status, signal), staleTime: MINUTE }),
  disputes: (api: SellerApiClient, orgId: string, status?: string) =>
    queryOptions({ queryKey: sellerKeys.disputes(orgId, status), queryFn: ({ signal }) => api.listDisputes(status, signal), staleTime: MINUTE })
};
