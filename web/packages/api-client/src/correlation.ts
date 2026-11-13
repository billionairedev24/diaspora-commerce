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

/**
 * Cloudflare's visitor-location headers ("Add visitor location headers" transform): the BFF reads them to detect an
 * approximate deliver-to city on the first visit (gateway-bff `edgeHints`; ignored unless TRUST_EDGE_GEO).
 */
export const EDGE_GEO_HEADERS = [
  'cf-ipcountry',
  'cf-ipcity',
  'cf-ippostalcode',
  'cf-postal-code',
  'cf-region',
  'cf-region-code',
  'cf-iplatitude',
  'cf-iplongitude'
] as const;

/** Header names the Next.js `/api/bff` proxy forwards to gateway-bff (lowercase). */
export const BFF_PROXY_FORWARD_REQUEST = [
  ...EDGE_GEO_HEADERS,
  'accept',
  'accept-language',
  'content-type',
  'cookie',
  'idempotency-key',
  'if-match',
  'if-none-match',
  'origin',
  'user-agent',
  // Marks a request the person did not make (polling): the BFF must not count it as activity. Dropping it here would
  // let every poll keep a session alive.
  'x-background',
  'x-correlation-id',
  'x-request-id'
] as const;

export const BFF_PROXY_FORWARD_RESPONSE = [
  'cache-control',
  'content-disposition',
  'content-type',
  // The BFF's clock: the session timer works out the browser's clock offset from it.
  'date',
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

/**
 * Tells the BFF who the visitor is, from `cf-connecting-ip` ONLY (Cloudflare sets it; a browser cannot override it at the
 * edge). An incoming `x-forwarded-for` is never read: Cloudflare appends the real IP to whatever the client sent, so its
 * first entry is attacker-controlled. Without Cloudflare (local dev) nothing is sent and the BFF uses the socket peer.
 * `proxyToken` (BFF_PROXY_TOKEN) additionally lets the BFF accept the address as `x-at-client-ip`.
 */
export function forwardBffClientIp(incoming: Headers, outgoing: Headers, proxyToken?: string): void {
  outgoing.delete('x-forwarded-for');
  outgoing.delete('x-at-client-ip');
  outgoing.delete('x-at-proxy-token');
  const ip = incoming.get('cf-connecting-ip')?.trim();
  if (!ip) return;
  outgoing.set('x-forwarded-for', ip);
  if (proxyToken) {
    outgoing.set('x-at-client-ip', ip);
    outgoing.set('x-at-proxy-token', proxyToken);
  }
}

export function forwardBffProxyResponseHeaders(upstream: Headers, outgoing: Headers): void {
  BFF_PROXY_FORWARD_RESPONSE.forEach((h) => {
    const v = upstream.get(h);
    if (v) outgoing.set(h, v);
  });
}
