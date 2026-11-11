import { z } from 'zod';

import { type AddressSearchOptions } from './address-lookup';
import { type ApiClientOptions, createDownloader, createRequester, type DownloadedFile } from './http';
import { createSellerFbaApi } from './inventory-hub';
import { type ExportDataset, type ExportFormat, type ExportJob, type ExportJobList, ExportJobListSchema, ExportJobSchema, type TableExportQuery } from './schemas/exports';
import { type AddressSuggestions, AddressSuggestionsSchema, type PostalAddress, PostalAddressSchema } from './schemas/location';
import { type ReviewReportInput, type ReviewReportResult, ReviewReportResultSchema, type SellerReview, type SellerReviewPage, SellerReviewPageSchema, SellerReviewSchema } from './schemas/reviews';
import {
  type AcceptedInvitation,
  AcceptedInvitationSchema,
  type AddBillingPaymentMethodInput,
  type AfrotransactPickupRequest,
  type Asset,
  AssetSchema,
  type AssistantChatRequest,
  type AssistantReply,
  AssistantReplySchema,
  type AssistantStatus,
  AssistantStatusSchema,
  type AttachLibraryImagesResult,
  AttachLibraryImagesResultSchema,
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
  type BrandList,
  BrandListSchema,
  type BrandScope,
  type BusinessPageSettings,
  type BusinessPageSettingsInput,
  BusinessPageSettingsSchema,
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
  type CreatedBrand,
  CreatedBrandSchema,
  type CreateSellerCoupon,
  type CustomerPickupRequest,
  type Dashboard,
  DashboardSchema,
  type Earnings,
  EarningsSchema,
  type GtinCheck,
  GtinCheckSchema,
  type ImageLibraryItem,
  ImageLibraryItemSchema,
  type ImageLibraryPage,
  ImageLibraryPageSchema,
  type ImageLibraryQuery,
  type ImportMode,
  type ImportRowOutcome,
  type ImportRowStatus,
  type InventoryView,
  InventoryViewSchema,
  type InvitationPreview,
  InvitationPreviewSchema,
  type Invoice,
  InvoiceSchema,
  type LabelPurchase,
  LabelPurchaseSchema,
  type ListingContext,
  ListingContextSchema,
  type ListingPage,
  ListingPageSchema,
  type ListingRow,
  ListingRowSchema,
  type ListingRules,
  ListingRulesSchema,
  type ListingStatus,
  type ListingWriterDraft,
  ListingWriterDraftSchema,
  type ListingWriterRequest,
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
  type PlanFeeCollectionMode,
  type PlanFeeCollectionView,
  PlanFeeCollectionViewSchema,
  type ProductDraft,
  type ProductDraftList,
  ProductDraftListSchema,
  type ProductImport,
  type ProductImportPage,
  ProductImportPageSchema,
  type ProductImportRowPage,
  ProductImportRowPageSchema,
  ProductImportSchema,
  type ProductTaxReadiness,
  ProductTaxReadinessSchema,
  type ProductTypeSchema,
  ProductTypeSchemaSchema,
  type ProductUpdate,
  type RateQuotes,
  RateQuotesSchema,
  type SavedProductDraft,
  SavedProductDraftSchema,
  type SaveProductDraftRequest,
  type SellerAddress,
  type SellerCatalogScope,
  SellerCatalogScopeSchema,
  type SellerCoupon,
  SellerCouponListSchema,
  SellerCouponSchema,
  type SellerDepartment,
  type SellerDepartmentRequest,
  type SellerDepartmentRequestInput,
  SellerDepartmentRequestSchema,
  SellerDepartmentSchema,
  type SellerDispute,
  SellerDisputeListSchema,
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
  type SellerProductDetail,
  SellerProductDetailSchema,
  type SellerReturn,
  SellerReturnListSchema,
  type SellerRewardsView,
  SellerRewardsViewSchema,
  type SellerRunPickup,
  type SellerRunPickupList,
  SellerRunPickupListSchema,
  SellerRunPickupSchema,
  type SellerSession,
  SellerSessionSchema,
  type SellerStatusAction,
  type SellerTaxStatement,
  SellerTaxStatementSchema,
  type ServiceAreaInput,
  type ServiceAreaPreview,
  ServiceAreaPreviewSchema,
  type ServiceAreaView,
  ServiceAreaViewSchema,
  type ServiceInput,
  type ServiceOffer,
  ServiceOfferSchema,
  type ShipFromAddress,
  ShipFromAddressSchema,
  type ShipFromInput,
  ShipFromListSchema,
  type ShipmentStatus,
  type SizeChart,
  type SizeChartImportReport,
  SizeChartImportReportSchema,
  type SizeChartInput,
  type SizeChartPage,
  SizeChartPageSchema,
  SizeChartSchema,
  type SizeChartSuggestions,
  SizeChartSuggestionsSchema,
  type SpreadsheetFormat,
  type StockAdjustment,
  type StockAdjustmentRequest,
  StockAdjustmentSchema,
  type TeamMember,
  TeamMemberSchema,
  type TeamView,
  TeamViewSchema,
  type UploadRequest,
  type UploadTicket,
  UploadTicketSchema,  type VariantTaxEvidence,
  VariantTaxEvidenceSchema} from './schemas/seller';
import { tableSearchParams } from './table-query';

/** Server-driven orders table query: one search over every column, the sort (`<column>,asc|desc`), cursor, limit. */
export interface SellerOrdersQuery {
  status?: ShipmentStatus;
  q?: string;
  sort?: string[];
  cursor?: string;
  limit?: number;
}

/** Server-driven drafts table query: search over title and category, sort (updatedAt | title | categoryPath), page. */
export interface ProductDraftsQuery {
  q?: string;
  /** new = only drafts of products not created yet (the Drafts table); all = also the unsaved edits of listings. */
  kind?: 'all' | 'new';
  sort?: string[];
  page: number;
  size: number;
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

/** Bulk upload preview query (server-driven rows table). */
export interface SizeChartsQuery {
  productTypeId?: string;
  productType?: string;
  q?: string;
  includeRetired?: boolean;
  page?: number;
  size?: number;
}

export interface ImportRowsQuery {
  status?: ImportRowStatus;
  outcome?: ImportRowOutcome;
  page: number;
  size: number;
}

/** Typed client for the Seller Center & Provider Hub routes (contracts/openapi/gateway-bff-seller.yaml). */
export function createSellerApiClient(options: ApiClientOptions) {
  const request = createRequester(options);
  const download = createDownloader(options);
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
    /** One keystroke of an address field; pass the entry's session token (Google Places bills the entry as one session). */
    searchAddresses: (q: string, signal?: AbortSignal, options: AddressSearchOptions = {}): Promise<AddressSuggestions> =>
      request('/v1/geo/address-suggestions', AddressSuggestionsSchema, { query: { q, sessionToken: options.sessionToken, country: options.country }, signal }),
    /** Structured address of a picked suggestion (Google Place Details, closing the session). */
    resolveAddressSuggestion: (id: string, sessionToken?: string, signal?: AbortSignal): Promise<PostalAddress> =>
      request(`/v1/geo/address-suggestions/${encodeURIComponent(id)}`, PostalAddressSchema, { query: { sessionToken }, signal }),
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
    /** One step forward; `handoverCode` is the buyer's 4-digit code, needed to hand a pickup order over (DELIVERED). */
    updateShipmentStatus: (orderNumber: string, shipmentId: string, status: SellerStatusAction, key?: string, handoverCode?: string): Promise<SellerOrderDetail> =>
      request(`${shipment(orderNumber, shipmentId)}/status`, SellerOrderDetailSchema, { method: 'POST', body: { status, ...(handoverCode ? { handoverCode } : {}) }, headers: idem(key) }),
    quoteRates: (orderNumber: string, shipmentId: string, parcel: Parcel, shipFrom: SellerAddress, carrier?: string): Promise<RateQuotes> =>
      request(`${shipment(orderNumber, shipmentId)}/rates`, RateQuotesSchema, { method: 'POST', body: { parcel, shipFrom, ...(carrier ? { carrier } : {}) } }),
    buyLabel: (orderNumber: string, shipmentId: string, body: { carrier: string; service: string; parcel: Parcel; shipFrom: SellerAddress }, key: string): Promise<LabelPurchase> =>
      request(`${shipment(orderNumber, shipmentId)}/labels`, LabelPurchaseSchema, { method: 'POST', body, headers: idem(key) }),

    /* Ship-from addresses (seller service) */
    getBusinessPage: (signal?: AbortSignal): Promise<BusinessPageSettings> => request('/v1/seller/business-page', BusinessPageSettingsSchema, { signal }),
    updateBusinessPage: (input: BusinessPageSettingsInput): Promise<BusinessPageSettings> =>
      request('/v1/seller/business-page', BusinessPageSettingsSchema, { method: 'PUT', body: input }),
    /* Delivery run pick lists (S264) */
    listRunPickups: async (signal?: AbortSignal, background?: boolean): Promise<SellerRunPickupList> =>
      request('/v1/seller/run-pickups', SellerRunPickupListSchema, { signal, background }),
    getRunPickup: (runId: string, signal?: AbortSignal, background?: boolean): Promise<SellerRunPickup> =>
      request(`/v1/seller/run-pickups/${enc(runId)}`, SellerRunPickupSchema, { signal, background }),
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
    listReturns: async (status?: string, signal?: AbortSignal): Promise<SellerReturn[]> =>
      (await request(`/v1/seller/returns${status ? `?status=${enc(status)}` : ''}`, SellerReturnListSchema, { signal })).items,
    listDisputes: async (status?: string, signal?: AbortSignal): Promise<SellerDispute[]> =>
      (await request(`/v1/seller/disputes${status ? `?status=${enc(status)}` : ''}`, SellerDisputeListSchema, { signal })).items,
    /* Reviews of the organisation's products (S324): read, reply once, report. Sellers cannot edit or hide a review. */
    listReviews: (q: { unanswered?: boolean; page?: number; size?: number } = {}, signal?: AbortSignal): Promise<SellerReviewPage> =>
      request('/v1/seller/reviews', SellerReviewPageSchema, {
        signal,
        query: { unanswered: q.unanswered ? 'true' : undefined, page: q.page === undefined ? undefined : String(q.page), size: q.size === undefined ? undefined : String(q.size) }
      }),
    replyToReview: (id: string, text: string): Promise<SellerReview> =>
      request(`/v1/seller/reviews/${enc(id)}/reply`, SellerReviewSchema, { method: 'POST', body: { text } }),
    reportReview: (id: string, body: ReviewReportInput): Promise<ReviewReportResult> =>
      request(`/v1/seller/reviews/${enc(id)}/reports`, ReviewReportResultSchema, { method: 'POST', body }),
    listCoupons: async (signal?: AbortSignal) => (await request('/v1/seller/coupons', SellerCouponListSchema, { signal })).items,
    createCoupon: (body: CreateSellerCoupon): Promise<SellerCoupon> =>
      request('/v1/seller/coupons', SellerCouponSchema, { method: 'POST', body }),
    setCouponStatus: (id: string, status: 'ACTIVE' | 'PAUSED' | 'EXPIRED'): Promise<SellerCoupon> =>
      request(`/v1/seller/coupons/${enc(id)}/status`, SellerCouponSchema, { method: 'POST', body: { status } }),
    deleteCoupon: async (id: string): Promise<void> => {
      await request(`/v1/seller/coupons/${enc(id)}`, z.unknown(), { method: 'DELETE' });
    },

    /* Listings & builder */
    listListings: (q: ListingsQuery, signal?: AbortSignal): Promise<ListingPage> =>
      request('/v1/seller/listings', ListingPageSchema, { query: tableSearchParams({ status: q.status, q: q.q, page: q.page, size: q.size }, q.sort), signal }),
    setListingActive: (productId: string, active: boolean): Promise<ListingRow> =>
      request(`/v1/seller/listings/${enc(productId)}/status`, ListingRowSchema, { method: 'POST', body: { active } }),
    /** The tax publish gate of a product per variant, in plain words: what is still needed before it can go live. */
    getTaxReadiness: (productId: string, signal?: AbortSignal): Promise<ProductTaxReadiness> =>
      request(`/v1/seller/products/${encodeURIComponent(productId)}/tax-readiness`, ProductTaxReadinessSchema, { signal }),
    /** Uploads the proof file of an exemption question (label photo, Drug Facts) for a saved variant. */
    uploadTaxEvidence: (atin: string, factId: string, file: File): Promise<VariantTaxEvidence> => {
      const body = new FormData();
      body.set('factId', factId);
      body.set('file', file);
      return request(`/v1/seller/variants/${encodeURIComponent(atin)}/tax-evidence`, VariantTaxEvidenceSchema, { method: 'POST', body });
    },
    listTaxEvidence: (atin: string, signal?: AbortSignal): Promise<VariantTaxEvidence[]> =>
      request(`/v1/seller/variants/${encodeURIComponent(atin)}/tax-evidence`, z.array(VariantTaxEvidenceSchema), { signal }),
    submitProduct: (draft: ProductDraft): Promise<ListingRow> => request('/v1/seller/products', ListingRowSchema, { method: 'POST', body: draft }),
    /** One listing with everything the one-step form edits (S307). */
    getProduct: (productId: string, signal?: AbortSignal): Promise<SellerProductDetail> =>
      request(`/v1/seller/listings/${enc(productId)}`, SellerProductDetailSchema, { signal }),
    updateProduct: (productId: string, body: ProductUpdate): Promise<ListingRow> =>
      request(`/v1/seller/products/${enc(productId)}`, ListingRowSchema, { method: 'PUT', body }),
    /** One page of drafts and the total. Without a query: the first 100 of every kind (the form looks its own edit up here). */
    listProductDrafts: (q: ProductDraftsQuery = { page: 0, size: 100 }, signal?: AbortSignal): Promise<ProductDraftList> =>
      request('/v1/seller/product-drafts', ProductDraftListSchema, { query: tableSearchParams({ q: q.q, kind: q.kind, page: q.page, size: q.size }, q.sort), signal }),
    getProductDraft: (id: string, signal?: AbortSignal): Promise<SavedProductDraft> =>
      request(`/v1/seller/product-drafts/${enc(id)}`, SavedProductDraftSchema, { signal }),
    createProductDraft: (body: SaveProductDraftRequest): Promise<SavedProductDraft> =>
      request('/v1/seller/product-drafts', SavedProductDraftSchema, { method: 'POST', body }),
    saveProductDraft: (id: string, body: SaveProductDraftRequest): Promise<SavedProductDraft> =>
      request(`/v1/seller/product-drafts/${enc(id)}`, SavedProductDraftSchema, { method: 'PUT', body }),
    deleteProductDraft: (id: string): Promise<void> =>
      request(`/v1/seller/product-drafts/${enc(id)}`, z.undefined(), { method: 'DELETE' }),
    getListingContext: (signal?: AbortSignal): Promise<ListingContext> => request('/v1/seller/catalog/listing-context', ListingContextSchema, { signal }),
    writeListing: (body: ListingWriterRequest): Promise<ListingWriterDraft> =>
      request('/v1/seller/catalog/listing-writer', ListingWriterDraftSchema, { method: 'POST', body }),

    /* Bulk upload (S308) */
    downloadImportTemplate: (opts: { productType?: string; categoryId?: string; format: SpreadsheetFormat }): Promise<DownloadedFile> =>
      download('/v1/seller/imports/template', { productType: opts.productType, categoryId: opts.categoryId, format: opts.format },
        `afrotransact-products.${opts.format}`),
    uploadImport: (file: File, mode: ImportMode, productType?: string): Promise<ProductImport> => {
      const body = new FormData();
      body.set('file', file, file.name);
      body.set('mode', mode);
      if (productType) body.set('productType', productType);
      return request('/v1/seller/imports', ProductImportSchema, { method: 'POST', body });
    },
    listImports: (page: number, size: number, signal?: AbortSignal): Promise<ProductImportPage> =>
      request('/v1/seller/imports', ProductImportPageSchema, { query: { page: String(page), size: String(size) }, signal }),
    getImport: (id: string, signal?: AbortSignal, background?: boolean): Promise<ProductImport> =>
      request(`/v1/seller/imports/${enc(id)}`, ProductImportSchema, { signal, background }),
    listImportRows: (id: string, q: ImportRowsQuery, signal?: AbortSignal): Promise<ProductImportRowPage> =>
      request(`/v1/seller/imports/${enc(id)}/rows`, ProductImportRowPageSchema, {
        query: { status: q.status, outcome: q.outcome, page: String(q.page), size: String(q.size) },
        signal
      }),
    downloadShippingLabel: (orderNumber: string, shipmentId: string): Promise<DownloadedFile> =>
      download(`/v1/seller/orders/${enc(orderNumber)}/shipments/${enc(shipmentId)}/label`, {}, 'shipping-label.pdf'),
    downloadImportReport: (id: string, format: SpreadsheetFormat): Promise<DownloadedFile> =>
      download(`/v1/seller/imports/${enc(id)}/report`, { format }, `upload-report.${format}`),
    startImport: (id: string, validOnly: boolean): Promise<ProductImport> =>
      request(`/v1/seller/imports/${enc(id)}/start`, ProductImportSchema, { method: 'POST', body: { validOnly } }),
    listImageLibrary: (q: ImageLibraryQuery, signal?: AbortSignal): Promise<ImageLibraryPage> =>
      request('/v1/seller/image-library', ImageLibraryPageSchema, {
        query: {
          q: q.q || undefined,
          status: q.status,
          matched: q.matched === undefined ? undefined : String(q.matched),
          page: String(q.page),
          size: String(q.size)
        },
        signal
      }),
    removeLibraryImage: (assetId: string): Promise<undefined> =>
      request(`/v1/seller/image-library/${enc(assetId)}`, z.undefined(), { method: 'DELETE' }),
    renameLibraryImage: (assetId: string, fileName: string): Promise<ImageLibraryItem> =>
      request(`/v1/seller/image-library/${enc(assetId)}`, ImageLibraryItemSchema, { method: 'PATCH', body: { fileName } }),
    attachLibraryImages: (skus?: string[]): Promise<AttachLibraryImagesResult> =>
      request('/v1/seller/image-library/attach', AttachLibraryImagesResultSchema, { method: 'POST', body: { skus: skus ?? [] } }),
    /* Size charts: the seller writes them (sizes and measurements are only suggested by the category) */
    /** The organisation's charts, newest change first; `productTypeId`/`productType` also keep the charts made for any type. */
    listSizeCharts: (q: SizeChartsQuery = {}, signal?: AbortSignal): Promise<SizeChartPage> =>
      request('/v1/seller/size-charts', SizeChartPageSchema, {
        query: {
          productTypeId: q.productTypeId || undefined,
          productType: q.productType || undefined,
          q: q.q || undefined,
          includeRetired: q.includeRetired ? 'true' : undefined,
          page: String(q.page ?? 0),
          size: String(q.size ?? 25)
        },
        signal
      }),
    getSizeChart: (id: string, signal?: AbortSignal): Promise<SizeChart> => request(`/v1/seller/size-charts/${enc(id)}`, SizeChartSchema, { signal }),
    /** 422 with `errors` keyed by field: name, columns[1].label, rows[2].size, rows[2].values.bust. */
    createSizeChart: (input: SizeChartInput): Promise<SizeChart> => request('/v1/seller/size-charts', SizeChartSchema, { method: 'POST', body: input }),
    /** Send the `version` you loaded in `input.version`: 409 when the chart changed since. A size that products still sell must keep its row. */
    updateSizeChart: (id: string, input: SizeChartInput): Promise<SizeChart> =>
      request(`/v1/seller/size-charts/${enc(id)}`, SizeChartSchema, { method: 'PUT', body: input }),
    /** Retire (never deleted): products keep showing it, it can no longer be linked to products. Answers the retired chart. */
    retireSizeChart: (id: string): Promise<SizeChart> => request(`/v1/seller/size-charts/${enc(id)}`, SizeChartSchema, { method: 'DELETE' }),
    duplicateSizeChart: (id: string, name?: string): Promise<SizeChart> =>
      request(`/v1/seller/size-charts/${enc(id)}/duplicate`, SizeChartSchema, { method: 'POST', body: name ? { name } : {} }),
    /** The sizes and measurement columns a category suggests (`productTypeId` = the product-type category node, or `productType` = its code). */
    getSizeChartSuggestions: (scope: { productTypeId?: string; productType?: string }, signal?: AbortSignal): Promise<SizeChartSuggestions> =>
      request('/v1/seller/size-charts/suggestions', SizeChartSuggestionsSchema, {
        query: { productTypeId: scope.productTypeId || undefined, productType: scope.productType || undefined },
        signal
      }),
    downloadSizeChartTemplate: (opts: { productType?: string; productTypeId?: string; format: SpreadsheetFormat }): Promise<DownloadedFile> =>
      download('/v1/seller/size-charts/import-template', { productType: opts.productType, productTypeId: opts.productTypeId, format: opts.format },
        `afrotransact-size-charts.${opts.format}`),
    /** Saves the charts of a .xlsx/.csv file by name (same name = update; the same file again changes nothing). `dryRun` only reports. */
    importSizeCharts: (file: File, opts: { productType?: string; productTypeId?: string; dryRun?: boolean } = {}): Promise<SizeChartImportReport> => {
      const body = new FormData();
      body.set('file', file, file.name);
      if (opts.productType) body.set('productType', opts.productType);
      if (opts.productTypeId) body.set('productTypeId', opts.productTypeId);
      body.set('dryRun', String(opts.dryRun ?? false));
      return request('/v1/seller/size-charts/import', SizeChartImportReportSchema, { method: 'POST', body });
    },
    /** The brands of a category (422 without a category or product type). */
    listBrands: (scope: BrandScope, q: string, signal?: AbortSignal): Promise<BrandList> =>
      request('/v1/seller/catalog/brands', BrandListSchema, { query: { category: scope.category || undefined, productType: scope.productType || undefined, q: q || undefined }, signal }),
    /** Adds a brand the seller typed: the existing match, or a pending brand reviewed by AfroTransact. */
    createBrand: (name: string, scope: BrandScope): Promise<CreatedBrand> =>
      request('/v1/seller/catalog/brands', CreatedBrandSchema, { method: 'POST', body: { name, category: scope.category, productType: scope.productType } }),
    getCategoryTree: (signal?: AbortSignal): Promise<CategoryNode[]> => request('/v1/seller/catalog/categories', z.array(CategoryNodeSchema), { signal }),
    getProductType: (code: string, signal?: AbortSignal): Promise<ProductTypeSchema> =>
      request(`/v1/seller/catalog/product-types/${enc(code)}`, ProductTypeSchemaSchema, { signal }),
    /** What a category asks of a listing: the kind, the approval level and the status of every field. The one source of the form's rules. */
    getListingRules: (categoryCode: string, signal?: AbortSignal): Promise<ListingRules> =>
      request('/v1/seller/catalog/listing-rules', ListingRulesSchema, { query: { categoryCode }, signal }),
    checkGtin: (code: string, isbn: boolean, signal?: AbortSignal): Promise<GtinCheck> =>
      request(`/v1/seller/catalog/gtin/${enc(code)}`, GtinCheckSchema, { query: { isbn: String(isbn) }, signal }),
    listCategoryRequests: (signal?: AbortSignal): Promise<CategoryRequest[]> => request('/v1/seller/category-requests', z.array(CategoryRequestSchema), { signal }),
    requestCategory: (input: CategoryRequestInput): Promise<CategoryRequest> =>
      request('/v1/seller/category-requests', CategoryRequestSchema, { method: 'POST', body: input }),
    /** The departments the organisation may sell in, whether any sells sized goods, and its department requests. */
    getCatalogScope: (signal?: AbortSignal): Promise<SellerCatalogScope> => request('/v1/seller/catalog-scope', SellerCatalogScopeSchema, { signal }),
    /** The catalog's departments with the evidence each asks for (what onboarding offers). */
    listDepartments: (signal?: AbortSignal): Promise<SellerDepartment[]> => request('/v1/seller/departments', z.array(SellerDepartmentSchema), { signal }),
    /** Ask staff to let the organisation sell in another department. */
    requestDepartment: (input: SellerDepartmentRequestInput): Promise<SellerDepartmentRequest> =>
      request('/v1/seller/department-requests', SellerDepartmentRequestSchema, { method: 'POST', body: input }),
    requestUpload: (input: UploadRequest): Promise<UploadTicket> => request('/v1/seller/uploads', UploadTicketSchema, { method: 'POST', body: input }),
    completeUpload: (assetId: string): Promise<Asset> => request(`/v1/seller/uploads/${enc(assetId)}/complete`, AssetSchema, { method: 'POST' }),
    getInventory: (lowStock: boolean, signal?: AbortSignal): Promise<InventoryView> =>
      request('/v1/seller/inventory', InventoryViewSchema, { query: { lowStock: lowStock ? 'true' : undefined }, signal }),
    adjustStock: (input: StockAdjustmentRequest, key?: string): Promise<StockAdjustment> =>
      request('/v1/seller/inventory/adjustments', StockAdjustmentSchema, { method: 'POST', body: input, headers: idem(key) }),

    /* Lola for sellers */
    getAssistantStatus: (signal?: AbortSignal): Promise<AssistantStatus> => request('/v1/seller/assistant', AssistantStatusSchema, { signal }),
    askAssistant: (body: AssistantChatRequest): Promise<AssistantReply> => request('/v1/seller/assistant/chat', AssistantReplySchema, { method: 'POST', body }),

    /* Money */
    getPayouts: (signal?: AbortSignal): Promise<PayoutsView> => request('/v1/seller/payouts', PayoutsViewSchema, { signal }),
    getTaxStatement: (month: string, signal?: AbortSignal): Promise<SellerTaxStatement> =>
      request(`/v1/seller/tax/statement?month=${encodeURIComponent(month)}`, SellerTaxStatementSchema, { signal }),
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
    /** Collect an unpaid invoice again after fixing the payment method (owner / finance). */
    retryInvoice: (invoiceId: string): Promise<Invoice> =>
      request(`/v1/seller/billing/invoices/${enc(invoiceId)}/retry`, InvoiceSchema, { method: 'POST' }),
    /** A printable invoice (HTML; Print › Save as PDF makes the PDF). */
    downloadInvoice: (invoiceId: string): Promise<DownloadedFile> =>
      download(`/v1/seller/billing/invoices/${enc(invoiceId)}/document`, {}, 'invoice.html'),
    /** S298: pay the plan from sales (after credit) when the seller chose that, else always by card or always by card (owner / finance; applies from the next invoice). */
    setPlanFeeCollection: (mode: PlanFeeCollectionMode): Promise<PlanFeeCollectionView> =>
      request('/v1/seller/billing/plan-fee-collection', PlanFeeCollectionViewSchema, { method: 'PUT', body: { mode } }),

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
    /** Where the provider works: base, radius, extra areas, with the staff-set radius bounds, zones and categories. */
    getServiceArea: (signal?: AbortSignal): Promise<ServiceAreaView> => request('/v1/seller/provider/service-area', ServiceAreaViewSchema, { signal }),
    saveServiceArea: (input: ServiceAreaInput): Promise<ServiceAreaView> => request('/v1/seller/provider/service-area', ServiceAreaViewSchema, { method: 'PUT', body: input }),
    /** What a base ZIP and radius would cover (cities and metro zone), nothing saved. */
    previewServiceArea: (zip: string, radiusMiles: number, signal?: AbortSignal): Promise<ServiceAreaPreview> =>
      request('/v1/seller/provider/service-area/preview', ServiceAreaPreviewSchema, { query: { zip, radiusMiles: String(radiusMiles) }, signal }),
    getCancellationPolicy: (signal?: AbortSignal): Promise<CancellationPolicy> =>
      request('/v1/seller/provider/cancellation-policy', CancellationPolicySchema, { signal }),
    saveCancellationPolicy: (input: CancellationPolicyInput): Promise<CancellationPolicy> =>
      request('/v1/seller/provider/cancellation-policy', CancellationPolicySchema, { method: 'PUT', body: input }),
    getEarnings: (from: string | undefined, to: string | undefined, signal?: AbortSignal): Promise<Earnings> =>
      request('/v1/seller/provider/earnings', EarningsSchema, { query: { from, to }, signal }),

    /* Send to AfroTransact (S281): inbound plans, labels, lots and expiry for the active organisation */
    fba: createSellerFbaApi(request, options)
  };
}

export type SellerApiClient = ReturnType<typeof createSellerApiClient>;
