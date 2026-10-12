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
});
