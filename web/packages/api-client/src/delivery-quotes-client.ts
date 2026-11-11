import { type Requester } from './http';
import {
  type NewQuoteRequestBody,
  type QuoteRequest,
  type QuoteRequestPage,
  QuoteRequestPageSchema,
  QuoteRequestSchema
} from './schemas/delivery-quotes';

const enc = encodeURIComponent;

/**
 * "Ask for a delivery quote" of the gateway BFF (listing rules D20), spread into createApiClient. Signed-in shoppers only:
 * a signed-out shopper gets 401 and signs in first. Asking again for the same cart and address returns the same request.
 */
export function deliveryQuoteMethods(request: Requester) {
  return {
    /** Asks for a quote for the cart (or part of it) and a saved address. 429 over the hourly limit. */
    requestDeliveryQuote: (body: NewQuoteRequestBody): Promise<QuoteRequest> =>
      request('/v1/me/delivery-quotes', QuoteRequestSchema, { method: 'POST', body }),

    /** My quote requests, newest first. */
    listMyDeliveryQuotes: (signal?: AbortSignal): Promise<QuoteRequestPage> => request('/v1/me/delivery-quotes', QuoteRequestPageSchema, { signal }),

    /** One of mine (404 for anyone else's). */
    getMyDeliveryQuote: (id: string, signal?: AbortSignal): Promise<QuoteRequest> =>
      request(`/v1/me/delivery-quotes/${enc(id)}`, QuoteRequestSchema, { signal })
  };
}
