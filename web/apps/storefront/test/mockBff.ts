/* Mock BFF for component tests: the api-client MSW handlers in Node, with a cookie jar so the demo account stays
   signed in across requests like in a browser. */
import { createApiClient } from '@afrotransact/api-client';
import { resetDb } from '@afrotransact/api-client/mocks';
import { mockServer } from '@afrotransact/api-client/mocks/node';
import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest';

let cookie = '';
let extra: string[] = [];
/** Every request the components sent (before any injected failure), in order. */
export const sent: Request[] = [];
let failures: { match: (req: Request) => boolean; mode: 'network' | 'lost' }[] = [];

/**
 * Makes the next matching request fail like a flaky network: 'network' never reaches the mock BFF; 'lost' reaches it
 * (the order is placed) but the answer never comes back (a timeout).
 */
export function failNext(match: (req: Request) => boolean, mode: 'network' | 'lost') {
  failures.push({ match, mode });
}
const realFetch = globalThis.fetch;

/** Adds a cookie to every request (e.g. at_mock_stripe_pk, the mock BFF's switch to a fake Stripe key). */
export function addCookie(nameValue: string) {
  extra.push(nameValue);
}

export function installMockBff() {
  beforeAll(() => {
    mockServer.listen({ onUnhandledRequest: 'error' });
    const msw = globalThis.fetch;
    globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = new Request(input instanceof URL ? input.toString() : input, init);
      const jar = [cookie, ...extra].filter(Boolean).join('; ');
      if (jar) req.headers.set('cookie', jar);
      sent.push(req.clone());
      const i = failures.findIndex((f) => f.match(req));
      const failure = i >= 0 ? failures.splice(i, 1)[0] : undefined;
      if (failure?.mode === 'network') throw new TypeError('Failed to fetch');
      const res = await msw(req);
      if (failure?.mode === 'lost') throw new TypeError('Failed to fetch');
      const set = res.headers.get('set-cookie');
      if (set) cookie = set.split(';')[0] ?? '';
      return res;
    };
  });
  beforeEach(() => {
    resetDb();
    cookie = '';
    extra = [];
    failures = [];
    sent.length = 0;
  });
  afterEach(() => mockServer.resetHandlers());
  afterAll(() => {
    globalThis.fetch = realFetch;
    mockServer.close();
  });
}

/** Signs the demo shopper in on the shared cookie jar (the mock BFF's hosted sign-in start, as identity's page would). */
export async function signInDemo() {
  const api = createApiClient({ baseUrl: `${window.location.origin}/api/bff` });
  const res = await fetch(api.loginUrl('/'), { redirect: 'manual' });
  if (res.status !== 302 && res.type !== 'opaqueredirect') throw new Error(`expected the sign-in redirect, got ${res.status}`);
  return api;
}
