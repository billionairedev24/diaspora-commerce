import { randomUuid } from './uuid';

/** Same header as gateway-bff, Java {@code CorrelationIdFilter}, and go/pkg/httpserver. */
export const CORRELATION_HEADER = 'X-Correlation-Id';

export function newCorrelationId(): string {
  return randomUuid();
}

/** Ensures every outbound BFF request carries a correlation id (reuse when already set). */
export function withCorrelationHeaders(headers: Record<string, string> = {}): Record<string, string> {
  const has = Object.keys(headers).some((k) => k.toLowerCase() === CORRELATION_HEADER.toLowerCase());
  if (has) return headers;
  return { ...headers, [CORRELATION_HEADER]: newCorrelationId() };
}

/** Header names the Next.js `/api/bff` proxy forwards to gateway-bff (lowercase). */
export const BFF_PROXY_FORWARD_REQUEST = [
  'accept',
  'accept-language',
  'content-type',
  'cookie',
  'idempotency-key',
  'if-match',
  'if-none-match',
  'origin',
  'user-agent',
  'x-correlation-id',
  'x-request-id'
] as const;

export const BFF_PROXY_FORWARD_RESPONSE = [
  'cache-control',
  'content-disposition',
  'content-type',
  'etag',
  'location',
  'retry-after',
  'x-correlation-id',
  'x-request-id'
] as const;

/** Copies allowed inbound headers and mints a correlation id when the client did not send one. */
export function forwardBffProxyRequestHeaders(incoming: Headers, outgoing: Headers): void {
  BFF_PROXY_FORWARD_REQUEST.forEach((h) => {
    const v = incoming.get(h);
    if (v) outgoing.set(h, v);
  });
  if (!outgoing.has('x-correlation-id')) {
    outgoing.set('x-correlation-id', newCorrelationId());
  }
}

export function forwardBffProxyResponseHeaders(upstream: Headers, outgoing: Headers): void {
  BFF_PROXY_FORWARD_RESPONSE.forEach((h) => {
    const v = upstream.get(h);
    if (v) outgoing.set(h, v);
  });
}
