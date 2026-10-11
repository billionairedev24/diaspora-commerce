import { z } from 'zod';

import { type ApiClientOptions, createRequester } from './http';
import { createSellerFbaApi } from './inventory-hub';
import { type ExportDataset, type ExportFormat, type ExportJob, type ExportJobList, ExportJobListSchema, ExportJobSchema, type TableExportQuery } from './schemas/exports';
import { type AddressSuggestions, AddressSuggestionsSchema } from './schemas/location';
import {
  type AcceptedInvitation,
  AcceptedInvitationSchema,
  type AddBillingPaymentMethodInput,
  type AfrotransactPickupRequest,
  type Asset,
  AssetSchema,
  type Availability,
  AvailabilitySchema,
  type BillingPaymentMethod,
  BillingPaymentMethodSchema,
  type BillingPlanChangePreview,
  BillingPlanChangePreviewSchema,
  type BillingSetupIntent,
  BillingSetupIntentSchema,
  type BillingView,
  BillingViewSchema,
  type BookingAction,
  type BookingCard,
  BookingCardSchema,
  type Cadence,
  type CalendarView,
  CalendarViewSchema,
  type CancellationPolicy,
  type CancellationPolicyInput,
  CancellationPolicySchema,
  type CategoryNode,
  CategoryNodeSchema,
  type CategoryRequest,
  type CategoryRequestInput,
  CategoryRequestSchema,
  type ChangePlanResult,
  ChangePlanResultSchema,
  type ComplianceSummary,
  ComplianceSummarySchema,
  type CustomerPickupRequest,
  type Dashboard,
  DashboardSchema,
  type Earnings,
  EarningsSchema,
  type GtinCheck,
  GtinCheckSchema,
  type InventoryView,
  InventoryViewSchema,
  type InvitationPreview,
  InvitationPreviewSchema,
  type LabelPurchase,
  LabelPurchaseSchema,
  type ListingPage,
  ListingPageSchema,
  type ListingRow,
  ListingRowSchema,
  type ListingStatus,
  type OfferFulfilmentRequest,
  type OnboardingBusinessInput,
  type OnboardingComplianceInput,
  type OnboardingDetailsInput,
  type OnboardingOwnersInput,
  type OnboardingPlanInput,
  type OnboardingPlans,
  OnboardingPlansSchema,
  type OnboardingStatus,
  OnboardingStatusSchema,
  type OnboardingStripeSession,
  OnboardingStripeSessionSchema,
  type OnboardingUploadRequest,
  type OrgKind,
  type Parcel,
  type Payout,
  PayoutSchema,
  type PayoutsView,
  PayoutsViewSchema,
  type ProductDraft,
  type ProductTypeSchema,
  ProductTypeSchemaSchema,
  type PublicPage,
  type PublicPageInput,
  PublicPageSchema,
  type RateQuotes,
  RateQuotesSchema,
  type SellerAddress,
  type SellerFeesView,
  SellerFeesViewSchema,
  type SellerOffer,
  type SellerOfferList,
  SellerOfferListSchema,
  SellerOfferSchema,
  type SellerOrderDetail,
  SellerOrderDetailSchema,
  type SellerOrderPage,
  SellerOrderPageSchema,
  type SellerRewardsView,
  SellerRewardsViewSchema,
  type SellerSession,
  SellerSessionSchema,
  type SellerStatusAction,
  type ServiceInput,
  type ServiceOffer,
  ServiceOfferSchema,
  type ShipFromAddress,
  ShipFromAddressSchema,
  type ShipFromInput,
  ShipFromListSchema,
  type ShipmentStatus,
  type StockAdjustment,
  type StockAdjustmentRequest,
  StockAdjustmentSchema,
  type TeamMember,
  TeamMemberSchema,
  type TeamView,
  TeamViewSchema,
  type UploadRequest,
  type UploadTicket,
  UploadTicketSchema
} from './schemas/seller';
import { tableSearchParams } from './table-query';

/** Server-driven orders table query: one search over every column, the sort (`<column>,asc|desc`), cursor, limit. */
export interface SellerOrdersQuery {
  status?: ShipmentStatus;
  q?: string;
  sort?: string[];
  cursor?: string;
  limit?: number;
}

/** Server-driven listings table query. */
export interface ListingsQuery {
  status?: ListingStatus;
  q?: string;
  sort?: string[];
  page: number;
  size: number;
}

/** POST /v1/seller/exports: orders or listings with the table's search, sort, columns and selection, or a client-side dataset. */
export interface SellerExportRequest extends Partial<TableExportQuery> {
  format: ExportFormat;
  table?: 'orders' | 'listings';
  dataset?: ExportDataset;
}

const enc = encodeURIComponent;
const num = (n: number | undefined): string | undefined => (n === undefined ? undefined : String(n));

/** Typed client for the Seller Center & Provider Hub routes (contracts/openapi/gateway-bff-seller.yaml). */
export function createSellerApiClient(options: ApiClientOptions) {
  const request = createRequester(options);
  const idem = (key?: string): Record<string, string> | undefined => (key ? { 'Idempotency-Key': key } : undefined);
  const shipment = (orderNumber: string, shipmentId: string) => `/v1/seller/orders/${enc(orderNumber)}/shipments/${enc(shipmentId)}`;

  return {
    /* Shell */
    getSession: (signal?: AbortSignal): Promise<SellerSession> => request('/v1/seller/session', SellerSessionSchema, { signal }),
    setActiveOrg: (orgId: string): Promise<SellerSession> => request('/v1/seller/session/active-org', SellerSessionSchema, { method: 'PUT', body: { orgId } }),
    getOnboarding: (signal?: AbortSignal): Promise<OnboardingStatus> => request('/v1/seller/onboarding', OnboardingStatusSchema, { signal }),
    /** Start selling / offer services: starts or resumes the application (idempotent), prefilled from the account. */
    startOnboarding: (kind: OrgKind): Promise<OnboardingStatus> => request('/v1/seller/onboarding', OnboardingStatusSchema, { method: 'POST', body: { kind } }),
    saveOnboardingBusiness: (body: OnboardingBusinessInput): Promise<OnboardingStatus> =>
      request('/v1/seller/onboarding/business', OnboardingStatusSchema, { method: 'PUT', body }),
    saveOnboardingDetails: (body: OnboardingDetailsInput): Promise<OnboardingStatus> =>
      request('/v1/seller/onboarding/business-details', OnboardingStatusSchema, { method: 'PUT', body }),
    saveOnboardingOwners: (body: OnboardingOwnersInput): Promise<OnboardingStatus> =>
      request('/v1/seller/onboarding/owners', OnboardingStatusSchema, { method: 'PUT', body }),
    saveOnboardingPlan: (body: OnboardingPlanInput): Promise<OnboardingStatus> =>
      request('/v1/seller/onboarding/plan', OnboardingStatusSchema, { method: 'PUT', body }),
    saveOnboardingCompliance: (body: OnboardingComplianceInput): Promise<OnboardingStatus> =>
      request('/v1/seller/onboarding/catalog-compliance', OnboardingStatusSchema, { method: 'PUT', body }),
    getOnboardingPlans: (signal?: AbortSignal): Promise<OnboardingPlans> => request('/v1/seller/onboarding/plans', OnboardingPlansSchema, { signal }),
    /**
     * Account Session for Stripe's embedded Connect components (localTest: the local stand-in): account onboarding
     * (identity, tax ID and the payout bank) and account management ("Change bank account").
     */
    createOnboardingStripeSession: (): Promise<OnboardingStripeSession> =>
      request('/v1/seller/onboarding/stripe/session', OnboardingStripeSessionSchema, { method: 'POST' }),
    refreshOnboardingStripe: (): Promise<OnboardingStatus> => request('/v1/seller/onboarding/stripe/refresh', OnboardingStatusSchema, { method: 'POST' }),
    submitOnboarding: (): Promise<OnboardingStatus> => request('/v1/seller/onboarding/submit', OnboardingStatusSchema, { method: 'POST' }),
    /** Private upload ticket for an application document (permit, licence, insurance, formation document). */
    requestOnboardingUpload: (body: OnboardingUploadRequest): Promise<UploadTicket> =>
      request('/v1/seller/onboarding/uploads', UploadTicketSchema, { method: 'POST', body }),
    completeOnboardingUpload: (assetId: string): Promise<Asset> =>
      request(`/v1/seller/onboarding/uploads/${enc(assetId)}/complete`, AssetSchema, { method: 'POST' }),
    /** Address suggestions (geo service: Google Places / OSM), the same search the storefront uses. */
    searchAddresses: (q: string, signal?: AbortSignal): Promise<AddressSuggestions> =>
      request('/v1/geo/address-suggestions', AddressSuggestionsSchema, { query: { q }, signal }),
    getCompliance: (signal?: AbortSignal): Promise<ComplianceSummary> => request('/v1/seller/compliance', ComplianceSummarySchema, { signal }),
    /** Account Session for Account health (owner / finance): Stripe's components answer what Stripe needs. */
    createSellerStripeSession: (): Promise<OnboardingStripeSession> =>
      request('/v1/seller/stripe/session', OnboardingStripeSessionSchema, { method: 'POST' }),
    getDashboard: (rangeDays: number, signal?: AbortSignal): Promise<Dashboard> =>
      request('/v1/seller/dashboard', DashboardSchema, { query: { rangeDays: String(rangeDays) }, signal }),

    /* Exports (every table: CSV, Excel with a Summary sheet of native charts, PDF with a summary page) */
    createExport: (body: SellerExportRequest): Promise<ExportJob> => request('/v1/seller/exports', ExportJobSchema, { method: 'POST', body }),
    getExport: (id: string, signal?: AbortSignal): Promise<ExportJob> => request(`/v1/seller/exports/${enc(id)}`, ExportJobSchema, { signal }),
    listExports: (signal?: AbortSignal): Promise<ExportJobList> => request('/v1/seller/exports', ExportJobListSchema, { signal }),

    /* Orders */
    listOrders: (q: SellerOrdersQuery, signal?: AbortSignal): Promise<SellerOrderPage> =>
      request('/v1/seller/orders', SellerOrderPageSchema, { query: tableSearchParams({ status: q.status, q: q.q, cursor: q.cursor, limit: num(q.limit) }, q.sort), signal }),
    getOrder: (orderNumber: string, signal?: AbortSignal): Promise<SellerOrderDetail> => request(`/v1/seller/orders/${enc(orderNumber)}`, SellerOrderDetailSchema, { signal }),
    updateShipmentStatus: (orderNumber: string, shipmentId: string, status: SellerStatusAction, key?: string): Promise<SellerOrderDetail> =>
      request(`${shipment(orderNumber, shipmentId)}/status`, SellerOrderDetailSchema, { method: 'POST', body: { status }, headers: idem(key) }),
    quoteRates: (orderNumber: string, shipmentId: string, parcel: Parcel, shipFrom: SellerAddress): Promise<RateQuotes> =>
      request(`${shipment(orderNumber, shipmentId)}/rates`, RateQuotesSchema, { method: 'POST', body: { parcel, shipFrom } }),
    buyLabel: (orderNumber: string, shipmentId: string, body: { carrier: string; service: string; parcel: Parcel; shipFrom: SellerAddress }, key: string): Promise<LabelPurchase> =>
      request(`${shipment(orderNumber, shipmentId)}/labels`, LabelPurchaseSchema, { method: 'POST', body, headers: idem(key) }),

    /* Ship-from addresses (seller service) */
    listShipFrom: async (signal?: AbortSignal): Promise<ShipFromAddress[]> => (await request('/v1/seller/ship-from-addresses', ShipFromListSchema, { signal })).items,
    createShipFrom: (input: ShipFromInput): Promise<ShipFromAddress> =>
      request('/v1/seller/ship-from-addresses', ShipFromAddressSchema, { method: 'POST', body: input }),
    updateShipFrom: (id: string, input: ShipFromInput): Promise<ShipFromAddress> =>
      request(`/v1/seller/ship-from-addresses/${enc(id)}`, ShipFromAddressSchema, { method: 'PUT', body: input }),
    defaultShipFrom: async (id: string): Promise<ShipFromAddress[]> =>
      (await request(`/v1/seller/ship-from-addresses/${enc(id)}/default`, ShipFromListSchema, { method: 'POST' })).items,
    deleteShipFrom: async (id: string): Promise<ShipFromAddress[]> =>
      (await request(`/v1/seller/ship-from-addresses/${enc(id)}`, ShipFromListSchema, { method: 'DELETE' })).items,
    /** "AfroTransact picks up from my store" on or off, with the pickup hours (S255). */
    setAfrotransactPickup: (id: string, body: AfrotransactPickupRequest): Promise<ShipFromAddress> =>
      request(`/v1/seller/ship-from-addresses/${enc(id)}/afrotransact-pickup`, ShipFromAddressSchema, { method: 'PUT', body }),
    /** "Customers can pick up their orders here" on or off, with the hours and how long orders take to be ready. */
    setCustomerPickup: (id: string, body: CustomerPickupRequest): Promise<ShipFromAddress> =>
      request(`/v1/seller/ship-from-addresses/${enc(id)}/customer-pickup`, ShipFromAddressSchema, { method: 'PUT', body }),
    listOffers: (productId: string, signal?: AbortSignal): Promise<SellerOfferList> =>
      request('/v1/seller/offers', SellerOfferListSchema, { query: { productId }, signal }),
    setOfferFulfilment: (offerId: string, body: OfferFulfilmentRequest): Promise<SellerOffer> =>
      request(`/v1/seller/offers/${enc(offerId)}/fulfilment`, SellerOfferSchema, { method: 'PUT', body }),

    /* Listings & builder */
    listListings: (q: ListingsQuery, signal?: AbortSignal): Promise<ListingPage> =>
      request('/v1/seller/listings', ListingPageSchema, { query: tableSearchParams({ status: q.status, q: q.q, page: q.page, size: q.size }, q.sort), signal }),
    setListingActive: (productId: string, active: boolean): Promise<ListingRow> =>
      request(`/v1/seller/listings/${enc(productId)}/status`, ListingRowSchema, { method: 'POST', body: { active } }),
    submitProduct: (draft: ProductDraft): Promise<ListingRow> => request('/v1/seller/products', ListingRowSchema, { method: 'POST', body: draft }),
    getCategoryTree: (signal?: AbortSignal): Promise<CategoryNode[]> => request('/v1/seller/catalog/categories', z.array(CategoryNodeSchema), { signal }),
    getProductType: (code: string, signal?: AbortSignal): Promise<ProductTypeSchema> =>
      request(`/v1/seller/catalog/product-types/${enc(code)}`, ProductTypeSchemaSchema, { signal }),
    checkGtin: (code: string, isbn: boolean, signal?: AbortSignal): Promise<GtinCheck> =>
      request(`/v1/seller/catalog/gtin/${enc(code)}`, GtinCheckSchema, { query: { isbn: String(isbn) }, signal }),
    listCategoryRequests: (signal?: AbortSignal): Promise<CategoryRequest[]> => request('/v1/seller/category-requests', z.array(CategoryRequestSchema), { signal }),
    requestCategory: (input: CategoryRequestInput): Promise<CategoryRequest> =>
      request('/v1/seller/category-requests', CategoryRequestSchema, { method: 'POST', body: input }),
    requestUpload: (input: UploadRequest): Promise<UploadTicket> => request('/v1/seller/uploads', UploadTicketSchema, { method: 'POST', body: input }),
    completeUpload: (assetId: string): Promise<Asset> => request(`/v1/seller/uploads/${enc(assetId)}/complete`, AssetSchema, { method: 'POST' }),
    getInventory: (lowStock: boolean, signal?: AbortSignal): Promise<InventoryView> =>
      request('/v1/seller/inventory', InventoryViewSchema, { query: { lowStock: lowStock ? 'true' : undefined }, signal }),
    adjustStock: (input: StockAdjustmentRequest, key?: string): Promise<StockAdjustment> =>
      request('/v1/seller/inventory/adjustments', StockAdjustmentSchema, { method: 'POST', body: input, headers: idem(key) }),

    /* Money */
    getPayouts: (signal?: AbortSignal): Promise<PayoutsView> => request('/v1/seller/payouts', PayoutsViewSchema, { signal }),
    getFees: (signal?: AbortSignal): Promise<SellerFeesView> => request('/v1/seller/fees', SellerFeesViewSchema, { signal }),
    requestPayout: (body: { amountMinor?: number; all?: boolean }, key?: string): Promise<Payout> =>
      request('/v1/seller/payouts', PayoutSchema, { method: 'POST', body, headers: idem(key) }),
    getBilling: (signal?: AbortSignal): Promise<BillingView> => request('/v1/seller/billing', BillingViewSchema, { signal }),
    changePlan: (planCode: string, cadence: Cadence): Promise<ChangePlanResult> =>
      request('/v1/seller/billing/plan', ChangePlanResultSchema, { method: 'POST', body: { planCode, cadence } }),
    makeDefaultPaymentMethod: (id: string): Promise<BillingPaymentMethod[]> =>
      request(`/v1/seller/billing/payment-methods/${enc(id)}/default`, z.array(BillingPaymentMethodSchema), { method: 'POST' }),
    removePaymentMethod: (id: string): Promise<BillingPaymentMethod[]> =>
      request(`/v1/seller/billing/payment-methods/${enc(id)}`, z.array(BillingPaymentMethodSchema), { method: 'DELETE' }),
    /** Rewards the organisation can earn (with progress) and the ones it received (S296). */
    getBillingRewards: (signal?: AbortSignal): Promise<SellerRewardsView> => request('/v1/seller/billing/rewards', SellerRewardsViewSchema, { signal }),
    /** What a plan or cadence change would do (an upgrade shows Stripe's proration now); nothing changes. */
    previewPlanChange: (planCode: string, cadence: Cadence, signal?: AbortSignal): Promise<BillingPlanChangePreview> =>
      request('/v1/seller/billing/plan/preview', BillingPlanChangePreviewSchema, { query: { planCode, cadence }, signal }),
    /** Owner only: a SetupIntent to confirm with the Payment Element. */
    createBillingSetupIntent: (): Promise<BillingSetupIntent> => request('/v1/seller/billing/setup-intents', BillingSetupIntentSchema, { method: 'POST' }),
    /** Owner only: register the payment method the SetupIntent confirmed. */
    addBillingPaymentMethod: (body: AddBillingPaymentMethodInput): Promise<BillingPaymentMethod> =>
      request('/v1/seller/billing/payment-methods', BillingPaymentMethodSchema, { method: 'POST', body }),

    /* Team */
    getTeam: (signal?: AbortSignal): Promise<TeamView> => request('/v1/seller/team', TeamViewSchema, { signal }),
    inviteMember: (email: string, roleKey: string): Promise<TeamMember> =>
      request('/v1/seller/team/invitations', TeamMemberSchema, { method: 'POST', body: { email, roleKey } }),
    assignRole: (userId: string, roleKey: string): Promise<TeamView> =>
      request(`/v1/seller/team/members/${enc(userId)}`, TeamViewSchema, { method: 'PUT', body: { roleKey } }),
    removeMember: (userId: string): Promise<TeamView> => request(`/v1/seller/team/members/${enc(userId)}`, TeamViewSchema, { method: 'DELETE' }),
    revokeInvitation: (invitationId: string): Promise<TeamView> =>
      request(`/v1/seller/team/invitations/${enc(invitationId)}`, TeamViewSchema, { method: 'DELETE' }),
    lookupInvitation: (token: string): Promise<InvitationPreview> =>
      request('/v1/seller/invitations/lookup', InvitationPreviewSchema, { method: 'POST', body: { token } }),
    acceptInvitation: (token: string): Promise<AcceptedInvitation> =>
      request('/v1/seller/invitations/accept', AcceptedInvitationSchema, { method: 'POST', body: { token } }),

    /* Provider Hub */
    getCalendar: (from: string, to: string, signal?: AbortSignal): Promise<CalendarView> =>
      request('/v1/seller/bookings/calendar', CalendarViewSchema, { query: { from, to }, signal }),
    listBookingRequests: (signal?: AbortSignal): Promise<BookingCard[]> => request('/v1/seller/bookings/requests', z.array(BookingCardSchema), { signal }),
    actOnBooking: (bookingId: string, body: { action: BookingAction; reason?: string; proposedStartAt?: string }, key?: string): Promise<BookingCard> =>
      request(`/v1/seller/bookings/${enc(bookingId)}/actions`, BookingCardSchema, { method: 'POST', body, headers: idem(key) }),
    getWorkingHours: (signal?: AbortSignal): Promise<Availability> => request('/v1/seller/provider/hours', AvailabilitySchema, { signal }),
    saveWorkingHours: (a: Availability): Promise<Availability> => request('/v1/seller/provider/hours', AvailabilitySchema, { method: 'PUT', body: a }),
    listServices: (signal?: AbortSignal): Promise<ServiceOffer[]> => request('/v1/seller/provider/services', z.array(ServiceOfferSchema), { signal }),
    createService: (input: ServiceInput): Promise<ServiceOffer> => request('/v1/seller/provider/services', ServiceOfferSchema, { method: 'POST', body: input }),
    updateService: (id: string, input: ServiceInput): Promise<ServiceOffer> =>
      request(`/v1/seller/provider/services/${enc(id)}`, ServiceOfferSchema, { method: 'PATCH', body: input }),
    getCancellationPolicy: (signal?: AbortSignal): Promise<CancellationPolicy> =>
      request('/v1/seller/provider/cancellation-policy', CancellationPolicySchema, { signal }),
    saveCancellationPolicy: (input: CancellationPolicyInput): Promise<CancellationPolicy> =>
      request('/v1/seller/provider/cancellation-policy', CancellationPolicySchema, { method: 'PUT', body: input }),
    getEarnings: (from: string | undefined, to: string | undefined, signal?: AbortSignal): Promise<Earnings> =>
      request('/v1/seller/provider/earnings', EarningsSchema, { query: { from, to }, signal }),
    getPublicPage: (signal?: AbortSignal): Promise<PublicPage> => request('/v1/seller/storefront', PublicPageSchema, { signal }),
    savePublicPage: (input: PublicPageInput): Promise<PublicPage> => request('/v1/seller/storefront', PublicPageSchema, { method: 'PUT', body: input }),
    publishPublicPage: (): Promise<PublicPage> => request('/v1/seller/storefront/publish', PublicPageSchema, { method: 'POST' }),

    /* Send to AfroTransact (S281): inbound plans, labels, lots and expiry for the active organisation */
    fba: createSellerFbaApi(request, options)
  };
}

export type SellerApiClient = ReturnType<typeof createSellerApiClient>;
