import { type z } from 'zod';

import { withCorrelationHeaders } from './correlation';
import { ApiError, problemFrom } from './errors';
import { reportSessionEnd, type SessionEndBus, sessionEndReasonOf } from './session-end';

export type FetchLike = (input: Request) => Promise<Response>;

export interface ApiClientOptions {
  /** Gateway BFF base URL, e.g. "/api/bff" in the browser or "https://bff.internal" on the server. */
  baseUrl: string;
  /** Custom fetch (server-side cookie relay, in-process mocks, tests). Defaults to global fetch. */
  fetch?: FetchLike;
  /** Extra headers on every request (e.g. forwarded Cookie, Accept-Language). */
  headers?: Record<string, string>;
  /** Hears 401 SESSION_IDLE_TIMEOUT / SESSION_ABSOLUTE_TIMEOUT (default: the page's bus, browser only). */
  sessionEnd?: SessionEndBus;
}

/** Request header the BFF reads: this request was not made by the person, so it must not count as activity. */
export const BACKGROUND_HEADER = 'X-Background';

/**
 * Pass as the `background` argument of an API method for requests nobody asked for (polling, refresh timers): the BFF
 * still checks the session but does not treat the request as the person being active, so a tab left open still times
 * out. The default is foreground (counts as activity).
 */
export const BACKGROUND = true;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  body?: unknown;
  query?: URLSearchParams | Record<string, string | undefined>;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** Sends `X-Background: 1`: not the person's doing (see {@link BACKGROUND}). Default false. */
  background?: boolean;
}

export function joinUrl(base: string, path: string, query?: RequestOptions['query']): string {
  const b = base.endsWith('/') ? base.slice(0, -1) : base;
  let qs = '';
  if (query instanceof URLSearchParams) qs = query.toString();
  else if (query) {
    const p = new URLSearchParams();
    Object.entries(query).forEach(([k, v]) => { if (v !== undefined && v !== '') p.set(k, v); });
    qs = p.toString();
  }
  return `${b}${path}${qs ? `?${qs}` : ''}`;
}

/** Absolute URL for Request construction (relative base URLs resolve against the page origin). */
export function absolute(url: string): string {
  if (/^https?:\/\//.test(url)) return url;
  const origin = typeof window !== 'undefined' && window.location ? window.location.origin : 'http://localhost';
  return origin + url;
}

export type Requester = <S extends z.ZodType>(path: string, schema: S, opts?: RequestOptions) => Promise<z.infer<S>>;

/** Sends a request and answers its Response when ok; problem documents become ApiError. */
export type Transport = (path: string, opts?: RequestOptions) => Promise<Response>;

/**
 * Turns a failed response into the ApiError to throw. A 401 with SESSION_IDLE_TIMEOUT or SESSION_ABSOLUTE_TIMEOUT also
 * tells the page (once, however many requests fail together) that the session is over. Every fetch of a BFF call that
 * is not made through {@link createTransport} (streams, downloads) fails through here too.
 */
export async function apiErrorOf(options: Pick<ApiClientOptions, 'sessionEnd'>, res: Response): Promise<ApiError> {
  const problem = await problemFrom(res);
  const reason = res.status === 401 ? sessionEndReasonOf(problem.code) : undefined;
  if (reason) reportSessionEnd(reason, options.sessionEnd);
  return new ApiError(problem);
}

/** Shared by every JSON and session call: correlation id, credentials, the X-Background flag and the session-end report. */
export function createTransport(options: ApiClientOptions): Transport {
  const doFetch: FetchLike = options.fetch ?? ((req) => fetch(req));
  return async function send(path: string, opts: RequestOptions = {}): Promise<Response> {
    const headers: Record<string, string> = withCorrelationHeaders({ Accept: 'application/json', ...options.headers, ...opts.headers });
    if (opts.background) headers[BACKGROUND_HEADER] = '1';
    // FormData (file uploads) sets its own multipart Content-Type with the boundary.
    const form = typeof FormData !== 'undefined' && opts.body instanceof FormData;
    if (opts.body !== undefined && !form) headers['Content-Type'] = 'application/json';
    const req = new Request(absolute(joinUrl(options.baseUrl, path, opts.query)), {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body === undefined ? undefined : form ? (opts.body as FormData) : JSON.stringify(opts.body),
      credentials: 'include',
      signal: opts.signal
    });
    const res = await doFetch(req);
    if (!res.ok) throw await apiErrorOf(options, res);
    return res;
  };
}

/** JSON request helper shared by the storefront and seller clients: problem documents become ApiError, bodies are Zod-validated. */
export function createRequester(options: ApiClientOptions): Requester {
  const send = createTransport(options);
  return async function request<S extends z.ZodType>(path: string, schema: S, opts: RequestOptions = {}): Promise<z.infer<S>> {
    const res = await send(path, opts);
    if (res.status === 204) return schema.parse(undefined);
    return schema.parse(await res.json());
  };
}

/** A downloaded file (templates, reports): its bytes and the name from Content-Disposition. */
export interface DownloadedFile {
  blob: Blob;
  fileName: string;
}

export type Downloader = (path: string, query?: RequestOptions['query'], fallbackName?: string) => Promise<DownloadedFile>;

/** File-download helper: same credentials and problem handling as JSON requests, the body read as a Blob. */
export function createDownloader(options: ApiClientOptions): Downloader {
  const doFetch: FetchLike = options.fetch ?? ((req) => fetch(req));
  return async (path, query, fallbackName = 'download') => {
    const req = new Request(absolute(joinUrl(options.baseUrl, path, query)), {
      headers: withCorrelationHeaders({ Accept: '*/*', ...options.headers }),
      credentials: 'include'
    });
    const res = await doFetch(req);
    if (!res.ok) throw await apiErrorOf(options, res);
    const disposition = res.headers.get('Content-Disposition') ?? '';
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
    return { blob: await res.blob(), fileName: match?.[1] ? decodeURIComponent(match[1]) : fallbackName };
  };
}
