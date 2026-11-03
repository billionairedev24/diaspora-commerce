import { describe, expect, it } from 'vitest';

import { BACKGROUND_ON, createAuthFetch, sessionEndReasonOf, type TokenSource } from '../src/api/http';
import { flashCodeFor } from '../src/domain/sessionFlash';
import { CATALOG_FILES, EN_MESSAGES } from '../src/i18n/catalogs';

const problem = (code: string, status = 401) =>
  new Response(JSON.stringify({ type: 'about:blank', title: 'Signed out', status, code }), { status, headers: { 'Content-Type': 'application/problem+json' } });

function source(over: Partial<TokenSource> = {}): TokenSource & { calls: string[] } {
  const calls: string[] = [];
  return {
    calls,
    getAccessToken: async () => 'tok',
    refreshAccessToken: async () => {
      calls.push('refresh');
      return 'new';
    },
    onSessionExpired: () => void calls.push('expired'),
    onSessionEnded: (r) => void calls.push(`ended:${r}`),
    onUserRequest: () => void calls.push('activity'),
    ...over
  };
}

describe('sign-out messages', () => {
  it('map each reason to its own flash code and message in every locale', () => {
    expect(flashCodeFor('idle')).toBe('session-idle');
    expect(flashCodeFor('absolute')).toBe('session-absolute');
    for (const key of ['cu.session.idle', 'cu.session.absolute', 'cu.session.warnTitle', 'cu.session.warnBody', 'cu.session.stay', 'cu.session.signOut'] as const) {
      expect(EN_MESSAGES[key]).toBeTruthy();
      for (const loc of ['fr', 'es', 'ar', 'yo'] as const) expect(CATALOG_FILES[loc][key], `${loc} ${key}`).toBeTruthy();
    }
    expect(EN_MESSAGES['cu.session.idle']).toMatch(/inactivity/);
    expect(EN_MESSAGES['cu.session.absolute']).not.toMatch(/inactivity/);
  });
});

describe('401 session codes in the API layer', () => {
  it('SESSION_IDLE_TIMEOUT and SESSION_ABSOLUTE_TIMEOUT sign out with their reason, without trying a refresh', async () => {
    for (const [code, reason] of [['SESSION_IDLE_TIMEOUT', 'idle'], ['SESSION_ABSOLUTE_TIMEOUT', 'absolute']] as const) {
      const tokens = source();
      const doFetch = createAuthFetch(tokens, async () => problem(code));
      const res = await doFetch(new Request('https://api.example.test/bff/v1/cart'));
      expect(res.status).toBe(401);
      expect(tokens.calls).toEqual([`ended:${reason}`]);
      expect((await res.json()).code).toBe(code); // the caller still gets the problem body
    }
  });

  it('any other 401 still refreshes and retries (unchanged)', async () => {
    const tokens = source();
    let n = 0;
    const doFetch = createAuthFetch(tokens, async () => (n++ === 0 ? problem('TOKEN_EXPIRED') : new Response('{}', { status: 200 })));
    expect((await doFetch(new Request('https://api.example.test/bff/v1/cart'))).status).toBe(200);
    expect(tokens.calls).toEqual(['refresh']);
  });

  it('reads the code from a clone, and tolerates bodies that are not problems', async () => {
    expect(await sessionEndReasonOf(problem('SESSION_IDLE_TIMEOUT'))).toBe('idle');
    expect(await sessionEndReasonOf(new Response('nope', { status: 401 }))).toBeNull();
    expect(await sessionEndReasonOf(problem('SESSION_IDLE_TIMEOUT', 403))).toBeNull();
  });
});

describe('what counts as activity in the API layer', () => {
  const run = async (req: Request) => {
    const tokens = source();
    await createAuthFetch(tokens, async () => new Response('{}', { status: 200 }))(req);
    return tokens.calls;
  };

  it('counts a write the person started', async () => {
    expect(await run(new Request('https://api.example.test/bff/v1/cart/lines', { method: 'POST', body: '{}' }))).toEqual(['activity']);
  });

  it('does not count reads (screens refetch on resume by themselves)', async () => {
    expect(await run(new Request('https://api.example.test/bff/v1/home'))).toEqual([]);
  });

  it('does not count background work, e.g. the policy read and push-token registration', async () => {
    expect(await run(new Request('https://api.example.test/identity/v1/me/devices', { method: 'POST', body: '{}', headers: BACKGROUND_ON }))).toEqual([]);
    expect(await run(new Request('https://api.example.test/bff/v1/session/policy', { headers: BACKGROUND_ON }))).toEqual([]);
  });
});
