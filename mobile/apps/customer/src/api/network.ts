/* The network transport of the app: every request has a deadline (an unreachable LAN host would otherwise leave a
   spinner up forever) and a failure to connect becomes a NetworkError the screens can explain, instead of React
   Native's "Network request failed" TypeError. */
import { ApiError, type FetchLike } from '@afrotransact/api-client';

export const REQUEST_TIMEOUT_MS = 20_000;

/** The server could not be reached (offline, wrong host, timed out). `host` names where we tried. */
export class NetworkError extends Error {
  readonly host: string;
  readonly timedOut: boolean;

  constructor(host: string, timedOut: boolean) {
    super(timedOut ? `${host} did not answer in time` : `Could not reach ${host}`);
    this.name = 'NetworkError';
    this.host = host;
    this.timedOut = timedOut;
  }
}

function hostOf(url: string): string {
  const m = /^[a-z][a-z0-9+.-]*:\/\/([^/?#]+)/i.exec(url);
  return m?.[1] ?? url;
}

/** Wraps a fetch with a deadline and NetworkError. A caller's own abort (a screen left) passes through untouched. */
export function withDeadline(base: FetchLike, timeoutMs = REQUEST_TIMEOUT_MS): FetchLike {
  return async (req: Request) => {
    const ctrl = new AbortController();
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      ctrl.abort();
    }, timeoutMs);
    const onCallerAbort = () => ctrl.abort();
    if (req.signal.aborted) ctrl.abort();
    else req.signal.addEventListener('abort', onCallerAbort, { once: true });
    try {
      return await base(new Request(req, { signal: ctrl.signal }));
    } catch (e) {
      if (req.signal.aborted) throw e;
      if (timedOut) throw new NetworkError(hostOf(req.url), true);
      if (e instanceof TypeError) throw new NetworkError(hostOf(req.url), false);
      throw e;
    } finally {
      clearTimeout(timer);
      req.signal.removeEventListener('abort', onCallerAbort);
    }
  };
}

/** What to tell the person; `detail` carries the technical part (host, status) for the dev ribbon and support. */
export interface ErrorSummary {
  title: string;
  body: string;
  detail: string | null;
  /** Worth a retry button (connectivity or a server hiccup), as opposed to a request that will fail again. */
  retryable: boolean;
}

export function describeError(e: unknown): ErrorSummary {
  if (e instanceof NetworkError) {
    return {
      title: e.timedOut ? 'AfroTransact is slow to answer' : 'You seem to be offline',
      body: e.timedOut ? 'The connection is slow or the server is busy. Try again in a moment.' : 'Check your connection and try again.',
      detail: e.message,
      retryable: true
    };
  }
  if (e instanceof ApiError) {
    if (e.status === 401 || e.status === 403) return { title: 'Please sign in again', body: 'Your session ended. Sign in to continue.', detail: `${e.status} ${e.problem.title}`, retryable: false };
    if (e.status >= 500) return { title: 'Something went wrong on our side', body: 'We’re on it. Try again in a moment.', detail: `${e.status} ${e.problem.title}`, retryable: true };
    return { title: 'We couldn’t load this', body: e.message || 'Try again.', detail: `${e.status} ${e.problem.title}`, retryable: e.status === 408 || e.status === 429 };
  }
  return { title: 'We couldn’t load this', body: 'Try again.', detail: e instanceof Error ? e.message : null, retryable: true };
}

/** React Query retry policy: connectivity and 5xx twice, anything the server refused on purpose never. */
export function shouldRetry(failureCount: number, e: unknown): boolean {
  if (failureCount >= 2) return false;
  if (e instanceof NetworkError) return true;
  if (e instanceof ApiError) return e.status >= 500 || e.status === 408 || e.status === 429;
  return false;
}

export const retryDelay = (attempt: number): number => Math.min(1000 * 2 ** attempt, 8000);
