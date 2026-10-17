import { describe, expect, it } from 'vitest';

import { ACTING_AS_HEADER, createAuthFetch } from '../src/api/http';

describe('authed fetch', () => {
  it('shops as the signed-in person even when their token carries a seller organisation', async () => {
    const sent: Request[] = [];
    const doFetch = createAuthFetch(
      { getAccessToken: async () => 'tok', refreshAccessToken: async () => null, onSessionExpired: () => undefined },
      async (r) => {
        sent.push(r);
        return new Response('[]', { status: 200 });
      }
    );
    await doFetch(new Request('https://api.example.test/bookings/v1/bookings/mine'));
    expect(sent[0]?.headers.get('Authorization')).toBe('Bearer tok');
    expect(sent[0]?.headers.get(ACTING_AS_HEADER)).toBe('CUSTOMER');
  });

  it('sends no acting context when signed out', async () => {
    const sent: Request[] = [];
    const doFetch = createAuthFetch(
      { getAccessToken: async () => null, refreshAccessToken: async () => null, onSessionExpired: () => undefined },
      async (r) => {
        sent.push(r);
        return new Response('{}', { status: 200 });
      }
    );
    await doFetch(new Request('https://api.example.test/bff/v1/home'));
    expect(sent[0]?.headers.get(ACTING_AS_HEADER)).toBeNull();
  });

  it('refreshes once on a 401 and retries with the new token', async () => {
    const sent: string[] = [];
    let expired = 0;
    const doFetch = createAuthFetch(
      { getAccessToken: async () => 'old', refreshAccessToken: async () => 'new', onSessionExpired: () => void expired++ },
      async (r) => {
        sent.push(r.headers.get('Authorization') ?? '');
        return new Response('{}', { status: r.headers.get('Authorization') === 'Bearer new' ? 200 : 401 });
      }
    );
    expect((await doFetch(new Request('https://api.example.test/bff/v1/cart'))).status).toBe(200);
    expect(sent).toEqual(['Bearer old', 'Bearer new']);
    expect(expired).toBe(0);
  });

  it('ends the session only when the refresh token is really gone', async () => {
    let expired = 0;
    const gone = createAuthFetch(
      { getAccessToken: async () => 'old', refreshAccessToken: async () => null, onSessionExpired: () => void expired++ },
      async () => new Response('{}', { status: 401 })
    );
    expect((await gone(new Request('https://api.example.test/bff/v1/cart'))).status).toBe(401);
    expect(expired).toBe(1);
  });

  it('keeps the session when identity cannot be reached to refresh', async () => {
    let expired = 0;
    const offline = createAuthFetch(
      { getAccessToken: async () => 'old', refreshAccessToken: async () => { throw new TypeError('Network request failed'); }, onSessionExpired: () => void expired++ },
      async () => new Response('{}', { status: 401 })
    );
    expect((await offline(new Request('https://api.example.test/bff/v1/cart'))).status).toBe(401);
    expect(expired).toBe(0);
  });
});
