import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { BACKGROUND_HEADERS, createJson, sessionEndReasonOf, type TokenSource } from '../src/api/http';
import { demoRoute } from '../src/api/mock/fixtures';
import { mockFetch } from '../src/api/mock/server';
import { SessionGuard, type SessionStore } from '../src/domain/sessionGuard';
import { checkSession, DEFAULT_POLICY, parsePolicy, startClock } from '../src/domain/sessionPolicy';
import { type DriverEventBody, type QueuedEvent } from '../src/domain/types';
import { CATALOG_FILES, EN_MESSAGES } from '../src/i18n/catalogs';
import { endSession, type EndSessionParts } from '../src/session/endSession';
import { createMemoryStore } from '../src/storage/memoryStore';
import { type EngineApi, SyncEngine } from '../src/sync/engine';

const problem = (code: string, status = 401) =>
  new Response(JSON.stringify({ type: 'about:blank', title: 'Signed out', status, code }), { status, headers: { 'Content-Type': 'application/problem+json' } });

function tokens(over: Partial<TokenSource> = {}): TokenSource & { calls: string[] } {
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
    ...over
  };
}

describe('driver defaults', () => {
  it('are 14 h idle and 14 h absolute, and the mock BFF answers a policy the app accepts', async () => {
    expect(DEFAULT_POLICY.driver).toMatchObject({ idleTimeoutSeconds: 14 * 3600, absoluteTimeoutSeconds: 14 * 3600 });
    const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url');
    const res = await mockFetch(new Request('http://localhost:8200/v1/session/policy', { headers: { Authorization: `Bearer ${b64({ alg: 'none' })}.${b64({ sub: 'usr_tunde', exp: 9999999999 })}.x`, 'X-App': 'driver', ...BACKGROUND_HEADERS } }));
    expect(parsePolicy(await res.json(), 'driver')).toEqual(DEFAULT_POLICY.driver);
  });

  it('end a 14 h shift session at 14 h without activity, and treat a long-parked app as idle', () => {
    const T0 = Date.UTC(2026, 0, 1, 6);
    const clock = startClock(T0);
    expect(checkSession(DEFAULT_POLICY.driver, clock, T0 + 14 * 3600_000 - 1).status).toBe('active');
    expect(checkSession(DEFAULT_POLICY.driver, clock, T0 + 14 * 3600_000).status).toBe('expired-absolute');
  });

  it('a policy for the customer app is not accepted by the driver app', () => {
    expect(parsePolicy({ ...DEFAULT_POLICY['customer-app'], lastActivityAt: null, expiresAt: null }, 'driver')).toBeNull();
  });
});

describe('401 session codes in the API layer', () => {
  const json = (t: TokenSource, status: () => Response) => createJson('https://api.example.test', t, async () => status());

  it('SESSION_IDLE_TIMEOUT and SESSION_ABSOLUTE_TIMEOUT sign out with their reason, without a refresh', async () => {
    for (const [code, reason] of [['SESSION_IDLE_TIMEOUT', 'idle'], ['SESSION_ABSOLUTE_TIMEOUT', 'absolute']] as const) {
      const t = tokens();
      await expect(json(t, () => problem(code))('/v1/driver/me')).rejects.toMatchObject({ status: 401, code });
      expect(t.calls).toEqual([`ended:${reason}`]);
    }
  });

  it('any other 401 refreshes and retries (unchanged)', async () => {
    const t = tokens();
    let n = 0;
    const out = await json(t, () => (n++ === 0 ? problem('TOKEN_EXPIRED') : new Response('{"ok":true}', { status: 200 })))('/v1/driver/me');
    expect(out).toEqual({ ok: true });
    expect(t.calls).toEqual(['refresh']);
  });

  it('a refused refresh still ends the session the old way', async () => {
    const t = tokens({ refreshAccessToken: async () => null });
    await expect(json(t, () => problem('TOKEN_EXPIRED'))('/v1/driver/me')).rejects.toMatchObject({ status: 401 });
    expect(t.calls).toEqual(['expired']);
  });

  it('reads the code from a clone and tolerates other bodies', async () => {
    expect(await sessionEndReasonOf(problem('SESSION_ABSOLUTE_TIMEOUT'))).toBe('absolute');
    expect(await sessionEndReasonOf(new Response('<html>', { status: 401 }))).toBeNull();
    expect(await sessionEndReasonOf(problem('SESSION_IDLE_TIMEOUT', 403))).toBeNull();
  });
});

describe('sign-out messages', () => {
  it('exist in all five locales, and the driver is told the unsent updates stay on the phone', () => {
    for (const key of ['dr.session.idle', 'dr.session.absolute', 'dr.session.warnTitle', 'dr.session.warnBody', 'dr.session.stay', 'dr.session.signOut'] as const) {
      expect(EN_MESSAGES[key]).toBeTruthy();
      for (const loc of ['fr', 'es', 'ar', 'yo'] as const) expect(CATALOG_FILES[loc][key], `${loc} ${key}`).toBeTruthy();
    }
    expect(EN_MESSAGES['dr.session.idle']).toMatch(/inactivity/);
    expect(EN_MESSAGES['dr.session.idle']).toMatch(/kept on this phone/);
    expect(EN_MESSAGES['dr.session.absolute']).not.toMatch(/inactivity/);
  });
});

describe('ending the session keeps the outbox', () => {
  const scan = (label: string): DriverEventBody => ({ type: 'loadout.scanned', scan: { label, method: 'CAMERA' } });

  function setup(opts: { online: boolean }) {
    const store = createMemoryStore();
    const log: string[] = [];
    const sent: QueuedEvent[] = [];
    const api: EngineApi = {
      async sync(_d, events) {
        if (!opts.online) throw new Error('offline');
        log.push('sync');
        sent.push(...events);
        return { lastSequence: events.length, expectedSequence: events.length + 1, results: events.map((e) => ({ eventId: e.eventId, sequence: e.sequence, outcome: 'APPLIED' })) };
      },
      async sos() { return {}; },
      async uploadPhoto() { return 'asset'; },
      async routes() { return [demoRoute()]; }
    };
    let n = 0;
    const engine = new SyncEngine({ store, api, uuid: () => `id-${(n += 1)}`, now: () => new Date('2026-10-10T19:00:00Z'), position: async () => null });
    const parts = (): EndSessionParts => ({
      engine,
      unregisterPush: async () => void log.push('push'),
      revokeTokens: async () => void log.push('revoke'),
      removeTokens: async () => void log.push('tokens'),
      forgetClock: async () => void log.push('clock')
    });
    return { store, engine, log, sent, parts };
  }

  it('offline: every unsynced event is still in the store and in the engine after sign-out', async () => {
    const { store, engine, parts, log } = setup({ online: false });
    await engine.init();
    engine.setUser('usr_tunde');
    await engine.refreshRoute();
    engine.setOnline(false);
    await engine.record(scan('ATS7K2Q9H4M1'));
    await engine.record(scan('ATS3NP8D2LQ6'));
    const before = await store.loadOutbox();
    expect(before?.events.map((e) => e.sequence)).toEqual([1, 2]);

    await endSession(parts()); // the sign-out policy ended the session mid-route, with no signal

    const after = await store.loadOutbox();
    expect(after?.events).toEqual(before?.events);
    expect(after?.deviceId).toBe(before?.deviceId);
    expect(engine.getState().outbox.events).toHaveLength(2);
    expect(engine.getState().userId).toBeNull();
    expect(log).toEqual(['push', 'revoke', 'tokens', 'clock']);

    // the same driver signs in again later: the queue is still theirs and still goes out in order
    const okApi: EngineApi = {
      sync: async (_d, ev) => ({ lastSequence: ev.length, expectedSequence: ev.length + 1, results: ev.map((e) => ({ eventId: e.eventId, sequence: e.sequence, outcome: 'APPLIED' })) }),
      sos: async () => ({}),
      uploadPhoto: async () => 'asset',
      routes: async () => [demoRoute()]
    };
    const again = new SyncEngine({ store, api: okApi, uuid: () => 'x', now: () => new Date(), position: async () => null });
    await again.init();
    expect(again.getState().outbox.events.map((e) => e.sequence)).toEqual([1, 2]);
  });

  it('online: a last best-effort sync happens BEFORE the credentials are revoked', async () => {
    const { store, engine, parts, log, sent } = setup({ online: true });
    await engine.init();
    engine.setUser('usr_tunde');
    await engine.refreshRoute();
    engine.setOnline(false);
    await engine.record(scan('ATS7K2Q9H4M1'));
    engine.setOnline(true);
    await endSession(parts());
    expect(sent.map((e) => e.sequence)).toEqual([1]);
    expect(log.indexOf('sync')).toBeGreaterThanOrEqual(0);
    expect(log.indexOf('sync')).toBeLessThan(log.indexOf('revoke'));
    expect((await store.loadOutbox())?.deviceId).toBeTruthy(); // the outbox record itself is never deleted
  });

  it('a sync that fails does not stop the sign-out, and nothing is deleted', async () => {
    const { store, engine, parts, log } = setup({ online: false });
    await engine.init();
    engine.setUser('usr_tunde');
    await engine.refreshRoute();
    await engine.record(scan('ATS7K2Q9H4M1'));
    engine.setOnline(true); // thinks it is online, the server refuses
    await endSession(parts());
    expect(log).toContain('tokens');
    expect((await store.loadOutbox())?.events).toHaveLength(1);
  });
});

describe('background work never counts as activity', () => {
  const root = join(__dirname, '..', 'src');
  const walk = (dir: string): string[] =>
    readdirSync(dir).flatMap((f) => {
      const p = join(dir, f);
      return statSync(p).isDirectory() ? walk(p) : /\.tsx?$/.test(f) ? [p] : [];
    });

  it('only touches, the keyboard and the unlock screen call noteActivity / guard.touch', () => {
    const users = walk(root)
      .filter((f) => /\b(noteActivity|guard\.touch|guard\.stay)\b/.test(readFileSync(f, 'utf8')))
      .map((f) => f.slice(root.length + 1).replaceAll('\\', '/'))
      .sort();
    expect(users).toEqual(['app/_layout.tsx', 'session/DriverProvider.tsx', 'session/LockProvider.tsx']);
  });

  it('the sync engine, the location pinger, location and push code do not know about the session clock', () => {
    for (const f of ['sync/engine.ts', 'sync/locationPinger.ts', 'lib/location.ts', 'session/notificationsModule.ts', 'domain/locationPing.ts', 'domain/outbox.ts']) {
      expect(readFileSync(join(root, f), 'utf8'), f).not.toMatch(/sessionGuard|sessionPolicy|noteActivity|SessionGuard/);
    }
  });

  it('the policy read is sent as a background request for the driver app', () => {
    const src = readFileSync(join(root, 'api/client.ts'), 'utf8');
    expect(src).toMatch(/sessionPolicy: \(\) => bff<SessionPolicyBody>\('\/v1\/session\/policy', \{ headers: \{ \.\.\.BACKGROUND_HEADERS, 'X-App': 'driver' \} \}\)/);
    expect(BACKGROUND_HEADERS['X-Background']).toBe('1');
  });
});

describe('the guard with the driver audience', () => {
  it('signs out a shift session at 14 h, once, from the cold-start check', async () => {
    const data = new Map<string, string>();
    const store: SessionStore = { get: async (k) => data.get(k) ?? null, set: async (k, v) => void data.set(k, v), remove: async (k) => void data.delete(k) };
    const t = { now: Date.UTC(2026, 0, 1, 6) };
    const ended: string[] = [];
    const mk = () => new SessionGuard({ audience: 'driver', store, now: () => t.now, fetchPolicy: async () => { throw new Error('offline'); }, onExpire: (r) => void ended.push(r) });
    const a = mk();
    await a.load(false);
    await a.begin();
    t.now += 13 * 3600_000;
    const b = mk(); // the app was killed and restarted
    await b.load(true);
    expect(ended).toEqual([]);
    t.now += 90 * 60_000;
    const c = mk();
    await c.load(true);
    expect(ended).toEqual(['absolute']);
  });
});
