import { type z } from 'zod';

import { ApiError, problemFrom } from './errors';

export type FetchLike = (input: Request) => Promise<Response>;

export interface ApiClientOptions {
  /** Gateway BFF base URL, e.g. "/api/bff" in the browser or "https://bff.internal" on the server. */
  baseUrl: string;
  /** Custom fetch (server-side cookie relay, in-process mocks, tests). Defaults to global fetch. */
  fetch?: FetchLike;
  /** Extra headers on every request (e.g. forwarded Cookie, Accept-Language). */
  headers?: Record<string, string>;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  body?: unknown;
  query?: URLSearchParams | Record<string, string | undefined>;
  headers?: Record<string, string>;
  signal?: AbortSignal;
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

/** JSON request helper shared by the storefront and seller clients: problem documents become ApiError, bodies are Zod-validated. */
export function createRequester(options: ApiClientOptions): Requester {
  const doFetch: FetchLike = options.fetch ?? ((req) => fetch(req));
  return async function request<S extends z.ZodType>(path: string, schema: S, opts: RequestOptions = {}): Promise<z.infer<S>> {
    const headers: Record<string, string> = { Accept: 'application/json', ...options.headers, ...opts.headers };
    if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
    const req = new Request(absolute(joinUrl(options.baseUrl, path, opts.query)), {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      credentials: 'include',
      signal: opts.signal
    });
    const res = await doFetch(req);
    if (!res.ok) throw new ApiError(await problemFrom(res));
    if (res.status === 204) return schema.parse(undefined);
    return schema.parse(await res.json());
  };
}
