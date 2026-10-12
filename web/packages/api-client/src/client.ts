import { type z } from 'zod';

import { type ApiClientOptions, createRequester, joinUrl } from './http';
import { type PreorderFulfilmentRequest, preorderMethods } from './preorders';
import { type Address, AddressSchema, type OrderSummary, OrderSummarySchema, PageOfSchema, type PaymentMethod, PaymentMethodSchema, type ReturnSummary, ReturnSummarySchema } from './schemas/account';
import { type Me, MeSchema, type PhoneCodeSent, PhoneCodeSentSchema, type PhoneVerification, PhoneVerificationSchema, type SignOutResult, SignOutResultSchema } from './schemas/auth';
import {
  type ApplicationReceipt,
  ApplicationReceiptSchema,
  type Job,
  type JobApplicationForm,
  JobsSchema,
  type ResumeAsset,
  ResumeAssetSchema,
  type ResumeUpload,
  ResumeUploadSchema
} from './schemas/careers';
import { type AddToCartRequest, type Cart, CartSchema, type UpdateCartLineRequest } from './schemas/cart';
import { type ProductDetail, ProductDetailSchema, type StockAlert, StockAlertSchema } from './schemas/catalog';
import { type HelpArticle, type HelpArticlePage, HelpArticlePageSchema, HelpArticleSchema, type HelpQuery } from './schemas/help';
import { type Home, HomeSchema } from './schemas/home';
import {
  type ConsentRecord,
  ConsentRecordSchema,
  type ConsentRequest,
  type CookiePolicy,
  CookiePolicySchema,
  type LegalDocumentKey,
  type LegalPage,
  LegalPageSchema,
  type PrivacyRequestInput,
  type PrivacyRequestReceipt,
  PrivacyRequestReceiptSchema
} from './schemas/legal';
import { type AddressSuggestions, AddressSuggestionsSchema, type DeliveryLocation, DeliveryLocationSchema } from './schemas/location';
import { type SearchPage, SearchPageSchema, type SearchQuery, type Suggestions, SuggestionsSchema } from './schemas/search';
import { NewsletterResponseSchema, type SiteConfig, SiteConfigSchema } from './schemas/site';
import { toApiSearchParams } from './search-params';
import { shopperMethods } from './shopper-client';


export type { ApiClientOptions, FetchLike } from './http';

/** Creates a typed client for the gateway BFF (contracts/openapi/gateway-bff.yaml). Every response is validated with Zod. */
export function createApiClient(options: ApiClientOptions) {
  const request = createRequester(options);

  const pageOf = <T extends z.ZodType>(item: T) => PageOfSchema(item);

  return {
    /* Site & content */
    getSiteConfig: (signal?: AbortSignal): Promise<SiteConfig> => request('/v1/site-config', SiteConfigSchema, { signal }),
    getHome: (signal?: AbortSignal): Promise<Home> => request('/v1/home', HomeSchema, { signal }),
    subscribeNewsletter: (email: string) => request('/v1/newsletter/subscriptions', NewsletterResponseSchema, { method: 'POST', body: { email } }),

    /* Legal & privacy center */
    /** A legal document: the current version, or an archived one with `version`. English is binding. */
    getLegalDocument: (key: LegalDocumentKey, opts: { version?: string; locale?: string } = {}, signal?: AbortSignal): Promise<LegalPage> =>
      request(`/v1/legal/${encodeURIComponent(key)}`, LegalPageSchema, { query: { version: opts.version, locale: opts.locale }, signal }),
    /* Help center */
    /** A page of published help articles (the hub excluded); pass the previous page's nextCursor for the next one. */
    getHelpArticles: (query: HelpQuery & { cursor?: string } = {}, signal?: AbortSignal): Promise<HelpArticlePage> =>
      request('/v1/help/articles', HelpArticlePageSchema, { query: { topic: query.topic, q: query.q || undefined, cursor: query.cursor, limit: query.limit === undefined ? undefined : String(query.limit) }, signal }),
    /** A published help page; HELP_HUB is the help center hub. */
    getHelpArticle: (slug: string, signal?: AbortSignal): Promise<HelpArticle> =>
      request(`/v1/help/articles/${encodeURIComponent(slug)}`, HelpArticleSchema, { signal }),
    getCookiePolicy: (signal?: AbortSignal): Promise<CookiePolicy> => request('/v1/consent/cookie-policy', CookiePolicySchema, { signal }),
    /** Store a cookie choice (auditable). `gpc` also sends `Sec-GPC: 1`. */
    recordConsent: (input: ConsentRequest): Promise<ConsentRecord> =>
      request('/v1/consent/records', ConsentRecordSchema, { method: 'POST', body: input, headers: input.gpc ? { 'Sec-GPC': '1' } : undefined }),
    submitPrivacyRequest: (input: PrivacyRequestInput): Promise<PrivacyRequestReceipt> =>
      request('/v1/privacy/requests', PrivacyRequestReceiptSchema, { method: 'POST', body: input }),

    /* Location */
    getDeliveryLocation: (signal?: AbortSignal): Promise<DeliveryLocation> => request('/v1/delivery-location', DeliveryLocationSchema, { signal }),
    /** Set the deliver-to location by ZIP or by device coordinates ("Use my current location"). */
    setDeliveryLocation: (input: { zip: string } | { lat: number; lon: number }): Promise<DeliveryLocation> =>
      request('/v1/delivery-location', DeliveryLocationSchema, { method: 'PUT', body: input }),
    searchAddresses: (q: string, signal?: AbortSignal): Promise<AddressSuggestions> =>
      request('/v1/geo/address-suggestions', AddressSuggestionsSchema, { query: { q }, signal }),

    /* Search */
    search: (query: SearchQuery, signal?: AbortSignal): Promise<SearchPage> => request('/v1/search', SearchPageSchema, { query: toApiSearchParams(query), signal }),
    suggest: (q: string, signal?: AbortSignal): Promise<Suggestions> => request('/v1/search/suggestions', SuggestionsSchema, { query: { q }, signal }),

    /* Catalogue */
    getProduct: (atin: string, signal?: AbortSignal): Promise<ProductDetail> => request(`/v1/products/${encodeURIComponent(atin)}`, ProductDetailSchema, { signal }),
    createStockAlert: (atin: string, email?: string): Promise<StockAlert> =>
      request(`/v1/products/${encodeURIComponent(atin)}/stock-alerts`, StockAlertSchema, { method: 'POST', body: email ? { email } : {} }),

    /* Cart */
    getCart: (signal?: AbortSignal): Promise<Cart> => request('/v1/cart', CartSchema, { signal }),
    addToCart: (body: AddToCartRequest): Promise<Cart> => request('/v1/cart/items', CartSchema, { method: 'POST', body }),
    updateCartLine: (lineId: string, body: UpdateCartLineRequest): Promise<Cart> =>
      request(`/v1/cart/items/${encodeURIComponent(lineId)}`, CartSchema, { method: 'PATCH', body }),
    removeCartLine: (lineId: string): Promise<Cart> => request(`/v1/cart/items/${encodeURIComponent(lineId)}`, CartSchema, { method: 'DELETE' }),
    selectAllCartLines: (selected: boolean): Promise<Cart> => request('/v1/cart/selection', CartSchema, { method: 'PUT', body: { selected } }),
    saveForLater: (lineId: string): Promise<Cart> => request(`/v1/cart/items/${encodeURIComponent(lineId)}/save-for-later`, CartSchema, { method: 'POST' }),
    moveToCart: (savedId: string): Promise<Cart> => request(`/v1/cart/saved/${encodeURIComponent(savedId)}/move-to-cart`, CartSchema, { method: 'POST' }),
    removeSaved: (savedId: string): Promise<Cart> => request(`/v1/cart/saved/${encodeURIComponent(savedId)}`, CartSchema, { method: 'DELETE' }),
    /** Door delivery or pickup for the cart's pre-order package (fees from the window's payment setup). */
    setPreorderFulfilment: (body: PreorderFulfilmentRequest): Promise<Cart> => request('/v1/cart/preorder-fulfilment', CartSchema, { method: 'PUT', body }),

    /* Pre-orders (S273) */
    ...preorderMethods(request),

    /* Auth: sign-in and sign-up run on identity's hosted pages (single sign-on), started with a full-page navigation to
       loginUrl(); the BFF keeps the tokens (OIDC authorization code + PKCE). */
    getMe: (signal?: AbortSignal): Promise<Me> => request('/v1/me', MeSchema, { signal }),
    startPhoneVerification: (): Promise<PhoneCodeSent> => request('/v1/account/phone/verification', PhoneCodeSentSchema, { method: 'POST' }),
    confirmPhoneVerification: (code: string): Promise<PhoneVerification> =>
      request('/v1/account/phone/verification/confirm', PhoneVerificationSchema, { method: 'POST', body: { code } }),
    /**
     * Ends the BFF session; navigate to redirectTo (when set) to end the identity session too (single sign-out), after
     * which identity returns to this app's signed-out landing. returnTo is the page to come back to (storefront: only
     * pages a guest may see).
     */
    signOut: (input: { returnTo?: string } = {}): Promise<SignOutResult> =>
      request('/v1/auth/sign-out', SignOutResultSchema, { method: 'POST', body: input.returnTo ? { returnTo: input.returnTo } : {} }),
    /**
     * URL of the BFF's hosted sign-in start (identity's sign-in page, or its sign-up page with prompt 'create').
     * returnTo is a storefront path or an absolute URL on another AfroTransact web app.
     */
    loginUrl: (returnTo = '/', opts: { prompt?: 'login' | 'create'; loginHint?: string; invitation?: string } = {}): string =>
      joinUrl(options.baseUrl, '/v1/auth/login', {
        returnTo,
        ...(opts.prompt ? { prompt: opts.prompt } : {}),
        ...(opts.loginHint ? { login_hint: opts.loginHint } : {}),
        ...(opts.invitation ? { invitation: opts.invitation } : {})
      }),
    /** URL that starts the OIDC authorization-code + PKCE flow with an external identity provider. */
    oidcAuthorizeUrl: (provider: string, returnTo = '/'): string =>
      joinUrl(options.baseUrl, `/v1/auth/oidc/${encodeURIComponent(provider)}/authorize`, { returnTo }),

    /* Careers */
    getJobs: async (signal?: AbortSignal): Promise<Job[]> => (await request('/v1/careers/jobs', JobsSchema, { signal })).jobs,
    /** Signed direct upload for a résumé (media service; PDF ≤ 5 MB). PUT the file to upload.url, then complete. */
    requestResumeUpload: (input: { fileName: string; contentType: string; size: number }): Promise<ResumeUpload> =>
      request('/v1/careers/uploads', ResumeUploadSchema, { method: 'POST', body: input }),
    completeResumeUpload: (assetId: string): Promise<ResumeAsset> =>
      request(`/v1/careers/uploads/${encodeURIComponent(assetId)}/complete`, ResumeAssetSchema, { method: 'POST' }),
    applyToJob: (jobId: string, form: JobApplicationForm): Promise<ApplicationReceipt> =>
      request(`/v1/careers/jobs/${encodeURIComponent(jobId)}/applications`, ApplicationReceiptSchema, { method: 'POST', body: form }),

    /* Account lists */
    getAddresses: async (): Promise<Address[]> => (await request('/v1/account/addresses', pageOf(AddressSchema))).items,
    getPaymentMethods: async (): Promise<PaymentMethod[]> => (await request('/v1/account/payment-methods', pageOf(PaymentMethodSchema))).items,
    getOrders: async (): Promise<OrderSummary[]> => (await request('/v1/account/orders', pageOf(OrderSummarySchema))).items,
    getReturns: async (orderNumber?: string): Promise<ReturnSummary[]> =>
      (await request('/v1/account/returns', pageOf(ReturnSummarySchema), { query: orderNumber ? { orderNumber } : undefined })).items,

    /* Account details, checkout, services & bookings, Lola */
    ...shopperMethods(request, options)
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;

/**
 * Uploads a résumé through the media service's signed upload: ask the BFF for a URL, PUT the file straight to storage
 * (no BFF credentials), then let media check it. Returns the asset id to send as resumeAssetId.
 */
export async function uploadResume(
  api: Pick<ApiClient, 'requestResumeUpload' | 'completeResumeUpload'>,
  file: { name: string; type: string; size: number } & Blob,
  put: (url: string, init: RequestInit) => Promise<Response> = (url, init) => fetch(url, init)
): Promise<ResumeAsset> {
  const ticket = await api.requestResumeUpload({ fileName: file.name, contentType: file.type || 'application/pdf', size: file.size });
  const res = await put(ticket.upload.url, { method: ticket.upload.method, headers: ticket.upload.headers, body: file });
  if (!res.ok) throw new Error(`Upload failed (${res.status})`);
  return api.completeResumeUpload(ticket.assetId);
}
