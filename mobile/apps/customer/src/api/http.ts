/* Bearer-token fetch for native clients (the BFF verifies app tokens with identity's JWKS) and a small JSON requester
   for the routes that @afrotransact/api-client does not wrap yet. Errors are RFC 9457 problems → ApiError. */
import { ApiError, type FetchLike, ProblemSchema } from '@afrotransact/api-client';

export interface TokenSource {
  /** Current access token (refreshed ahead of expiry by the auth layer), or null when signed out. */
  getAccessToken(): Promise<string | null>;
  /** Forces a refresh after a 401; resolves null when the session is gone. */
  refreshAccessToken(): Promise<string | null>;
  /** Called when a refresh fails: the app returns to sign-in. */
  onSessionExpired(): void;
}

function withBearer(req: Request, token: string | null): Request {
  const headers = new Headers(req.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  headers.set('X-Client', 'customer-app');
  return new Request(req, { headers });
}

/** Adds `Authorization: Bearer …`; on 401 refreshes once and retries the request. */
export function createAuthFetch(tokens: TokenSource, base: FetchLike): FetchLike {
  return async (input: Request) => {
    const retry = input.clone();
    const res = await base(withBearer(input, await tokens.getAccessToken()));
    if (res.status !== 401) return res;
    const fresh = await tokens.refreshAccessToken();
    if (!fresh) {
      tokens.onSessionExpired();
      return res;
    }
    return base(withBearer(retry, fresh));
  };
}

export interface JsonOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Record<string, string | number | undefined | string[]>;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

export type JsonRequester = <T>(path: string, opts?: JsonOptions) => Promise<T>;

export function buildUrl(baseUrl: string, path: string, query?: JsonOptions['query']): string {
  const b = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(query ?? {})) {
    if (v === undefined || v === '') continue;
    if (Array.isArray(v)) v.forEach((x) => p.append(k, x));
    else p.set(k, String(v));
  }
  const qs = p.toString();
  return `${b}${path}${qs ? `?${qs}` : ''}`;
}

async function problemOf(res: Response): Promise<ApiError> {
  try {
    const parsed = ProblemSchema.safeParse(await res.json());
    if (parsed.success) return new ApiError(parsed.data);
  } catch {
    /* not JSON */
  }
  return new ApiError({ type: 'about:blank', title: res.statusText || 'Request failed', status: res.status });
}

/** Typed JSON requester against one service. Responses are trusted to match the generated contract types. */
export function createJsonRequester(baseUrl: string, doFetch: FetchLike): JsonRequester {
  return async <T>(path: string, opts: JsonOptions = {}): Promise<T> => {
    const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
    if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
    const res = await doFetch(
      new Request(buildUrl(baseUrl, path, opts.query), {
        method: opts.method ?? 'GET',
        headers,
        body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
        signal: opts.signal
      })
    );
    if (!res.ok) throw await problemOf(res);
    if (res.status === 204) return undefined as T;
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  };
}
