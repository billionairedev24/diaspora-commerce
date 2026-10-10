/* Mock BFF for component tests: the api-client MSW handlers in Node, with a cookie jar so the demo account stays
   signed in across requests like in a browser. */
import { createApiClient } from '@afrotransact/api-client';
import { resetDb } from '@afrotransact/api-client/mocks';
import { mockServer } from '@afrotransact/api-client/mocks/node';
import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest';

let cookie = '';
const realFetch = globalThis.fetch;

export function installMockBff() {
  beforeAll(() => {
    mockServer.listen({ onUnhandledRequest: 'error' });
    const msw = globalThis.fetch;
    globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = new Request(input instanceof URL ? input.toString() : input, init);
      if (cookie) req.headers.set('cookie', cookie);
      const res = await msw(req);
      const set = res.headers.get('set-cookie');
      if (set) cookie = set.split(';')[0] ?? '';
      return res;
    };
  });
  beforeEach(() => {
    resetDb();
    cookie = '';
  });
  afterEach(() => mockServer.resetHandlers());
  afterAll(() => {
    globalThis.fetch = realFetch;
    mockServer.close();
  });
}

/** Signs the demo shopper in (password + MFA code) on the shared cookie jar. */
export async function signInDemo() {
  const api = createApiClient({ baseUrl: `${window.location.origin}/api/bff` });
  const r = await api.signIn({ email: 'adaeze@example.com', password: 'shop2026' });
  if (r.status !== 'mfa_required') throw new Error('expected MFA');
  await api.verifyMfa({ challengeId: r.challengeId, code: '246810' });
  return api;
}
