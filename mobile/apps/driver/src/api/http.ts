/* Bearer JSON requester for fulfilment, media and identity (problems → ApiProblemError). */
export interface TokenSource {
  getAccessToken(): Promise<string | null>;
  refreshAccessToken(): Promise<string | null>;
}

export type Fetch = (req: Request) => Promise<Response>;

export class ApiProblemError extends Error {
  constructor(readonly status: number, readonly body: Record<string, unknown>) {
    super(typeof body.detail === 'string' ? body.detail : typeof body.title === 'string' ? body.title : `HTTP ${status}`);
    this.name = 'ApiProblemError';
  }

  get code(): string | undefined {
    return typeof this.body.code === 'string' ? this.body.code : undefined;
  }
}

export class NetworkError extends Error {
  constructor(cause: unknown) {
    super(cause instanceof Error ? cause.message : 'Network request failed');
    this.name = 'NetworkError';
  }
}

export interface JsonOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

export function createJson(baseUrl: string, tokens: TokenSource, doFetch: Fetch) {
  const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
  const send = async (path: string, opts: JsonOptions, token: string | null) => {
    const headers: Record<string, string> = { Accept: 'application/json', 'X-Client': 'driver-app', ...opts.headers };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
    try {
      return await doFetch(new Request(`${base}${path}`, { method: opts.method ?? 'GET', headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body), signal: opts.signal }));
    } catch (e) {
      throw new NetworkError(e);
    }
  };
  return async function json<T>(path: string, opts: JsonOptions = {}): Promise<T> {
    let res = await send(path, opts, await tokens.getAccessToken());
    if (res.status === 401) {
      const fresh = await tokens.refreshAccessToken();
      if (fresh) res = await send(path, opts, fresh);
    }
    const text = await res.text();
    const body: unknown = text ? JSON.parse(text) : undefined;
    if (!res.ok) throw new ApiProblemError(res.status, body && typeof body === 'object' ? (body as Record<string, unknown>) : {});
    return body as T;
  };
}
