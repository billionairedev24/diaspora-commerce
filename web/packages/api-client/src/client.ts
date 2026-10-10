import { z } from 'zod';

import { type ApiClientOptions, createRequester, joinUrl } from './http';
import { type Address, AddressSchema, type OrderSummary, OrderSummarySchema, PageOfSchema, type PaymentMethod, PaymentMethodSchema, type ReturnSummary, ReturnSummarySchema } from './schemas/account';
import {
  type AuthResult,
  AuthResultSchema,
  type Me,
  MeSchema,
  type PasskeyAssertion,
  type PasskeyOptions,
  PasskeyOptionsSchema,
  type PhoneCodeSent,
  PhoneCodeSentSchema,
  type PhoneVerification,
  PhoneVerificationSchema,
  ResendResponseSchema,
  type SignInRequest,
  type SignUpRequest,
  type VerifyCodeRequest
} from './schemas/auth';
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
import { type Home, HomeSchema } from './schemas/home';
import { type AddressSuggestions, AddressSuggestionsSchema, type DeliveryLocation, DeliveryLocationSchema } from './schemas/location';
import { type SearchPage, SearchPageSchema, type SearchQuery, type Suggestions, SuggestionsSchema } from './schemas/search';
import { NewsletterResponseSchema, type SiteConfig, SiteConfigSchema } from './schemas/site';
import { toApiSearchParams } from './search-params';

const AuthVoid = z.undefined();

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

    /* Auth (OIDC PKCE against identity is driven by the BFF; these are the first-party form steps) */
    getMe: (signal?: AbortSignal): Promise<Me> => request('/v1/me', MeSchema, { signal }),
    signIn: (body: SignInRequest): Promise<AuthResult> => request('/v1/auth/sign-in', AuthResultSchema, { method: 'POST', body }),
    verifyMfa: (body: VerifyCodeRequest): Promise<AuthResult> => request('/v1/auth/mfa/verify', AuthResultSchema, { method: 'POST', body }),
    signUp: (body: SignUpRequest): Promise<AuthResult> => request('/v1/auth/sign-up', AuthResultSchema, { method: 'POST', body }),
    startPhoneVerification: (): Promise<PhoneCodeSent> => request('/v1/account/phone/verification', PhoneCodeSentSchema, { method: 'POST' }),
    confirmPhoneVerification: (code: string): Promise<PhoneVerification> =>
      request('/v1/account/phone/verification/confirm', PhoneVerificationSchema, { method: 'POST', body: { code } }),
    verifyEmail: (body: VerifyCodeRequest): Promise<AuthResult> => request('/v1/auth/email-verification/verify', AuthResultSchema, { method: 'POST', body }),
    resendEmailCode: (challengeId: string) => request('/v1/auth/email-verification/resend', ResendResponseSchema, { method: 'POST', body: { challengeId } }),
    passkeyOptions: (): Promise<PasskeyOptions> => request('/v1/auth/passkey/options', PasskeyOptionsSchema, { method: 'POST', body: {} }),
    passkeyVerify: (body: PasskeyAssertion): Promise<AuthResult> => request('/v1/auth/passkey/verify', AuthResultSchema, { method: 'POST', body }),
    signOut: (): Promise<void> => request('/v1/auth/sign-out', AuthVoid, { method: 'POST' }),
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

    /* Account (pages arrive in the next phase) */
    getAddresses: async (): Promise<Address[]> => (await request('/v1/account/addresses', pageOf(AddressSchema))).items,
    getPaymentMethods: async (): Promise<PaymentMethod[]> => (await request('/v1/account/payment-methods', pageOf(PaymentMethodSchema))).items,
    getOrders: async (): Promise<OrderSummary[]> => (await request('/v1/account/orders', pageOf(OrderSummarySchema))).items,
    getReturns: async (): Promise<ReturnSummary[]> => (await request('/v1/account/returns', pageOf(ReturnSummarySchema))).items
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
