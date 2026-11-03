import { describe, expect, it } from 'vitest';

import { SessionGuard, type SessionStore, storageKeys } from '../src/domain/sessionGuard';
import { DEFAULT_POLICY, type SessionAudience, type SessionEndReason } from '../src/domain/sessionPolicy';

const AUDIENCE: SessionAudience = 'customer-app';
const S = 1000;
const H = 3600 * S;
const D = 24 * H;
const T0 = Date.UTC(2026, 0, 1);
const keys = storageKeys(AUDIENCE);

interface MemoryStore extends SessionStore {
  data: Map<string, string>;
  failWrites: boolean;
}

function memoryStore(seed: Record<string, string> = {}): MemoryStore {
  const data = new Map(Object.entries(seed));
  const s: MemoryStore = {
    data,
    failWrites: false,
    get: async (k) => data.get(k) ?? null,
    set: async (k, v) => {
      if (s.failWrites) throw new Error('keychain locked');
      data.set(k, v);
    },
    remove: async (k) => void data.delete(k)
  };
  return s;
}

function setup(opts: { seed?: Record<string, string>; policy?: unknown; offline?: boolean } = {}) {
  const store = memoryStore(opts.seed);
  const t = { now: T0 };
  const expired: SessionEndReason[] = [];
  let fetches = 0;
  const guard = new SessionGuard({
    audience: AUDIENCE,
    store,
    now: () => t.now,
    fetchPolicy: async () => {
      fetches++;
      if (opts.offline) throw new Error('offline');
      return opts.policy;
    },
    onExpire: (r) => void expired.push(r)
  });
  return { guard, store, t, expired, fetches: () => fetches };
}

const serverPolicy = { audience: AUDIENCE, idleTimeoutSeconds: 3600, absoluteTimeoutSeconds: 86400, warnSeconds: 0, lastActivityAt: null, expiresAt: null };

describe('SessionGuard', () => {
  it('uses the built-in default until a policy is fetched', async () => {
    const { guard } = setup();
    await guard.load(false);
    expect(guard.policy()).toEqual(DEFAULT_POLICY[AUDIENCE]);
  });

  it('starts the clock at sign-in and signs out once when idle passes', async () => {
    const { guard, t, expired } = setup();
    await guard.load(false);
    await guard.begin();
    t.now = T0 + 29 * D;
    expect(guard.check()?.status).toBe('active');
    t.now = T0 + 31 * D;
    expect(guard.check()?.status).toBe('expired-idle');
    guard.check();
    expect(expired).toEqual(['idle']);
  });

  it('keeps a session alive with interaction but still ends it at the absolute limit', async () => {
    const { guard, t, expired } = setup();
    await guard.begin();
    for (let day = 10; day < 90; day += 10) {
      t.now = T0 + day * D;
      guard.touch();
      expect(guard.check()?.status).toBe('active');
    }
    t.now = T0 + 90 * D;
    expect(guard.check()?.status).toBe('expired-absolute');
    expect(expired).toEqual(['absolute']);
  });

  it('a touch after expiry does not revive the session', async () => {
    const { guard, t } = setup();
    await guard.begin();
    t.now = T0 + 31 * D;
    guard.touch();
    expect(guard.check()?.status).toBe('expired-idle');
  });

  it('cold start: restores the stored clock and ends an expired session at once', async () => {
    const first = setup();
    await first.guard.begin();
    const seed = Object.fromEntries(first.store.data);

    const soon = setup({ seed });
    soon.t.now = T0 + 5 * D;
    await soon.guard.load(true);
    expect(soon.expired).toEqual([]);

    const later = setup({ seed });
    later.t.now = T0 + 40 * D;
    await later.guard.load(true);
    expect(later.expired).toEqual(['idle']);
  });

  it('cold start without a stored clock gives a legacy sign-in one fresh window', async () => {
    const { guard, store, expired } = setup();
    await guard.load(true);
    expect(expired).toEqual([]);
    expect(store.data.has(keys.clock)).toBe(true);
  });

  it('cold start while signed out does not start a clock', async () => {
    const { guard, store } = setup();
    await guard.load(false);
    expect(guard.check()).toBeNull();
    expect(store.data.has(keys.clock)).toBe(false);
  });

  it('survives a restart with the cached policy', async () => {
    const a = setup({ policy: serverPolicy });
    await a.guard.begin();
    await a.guard.refreshPolicy();
    expect(a.guard.policy().idleTimeoutSeconds).toBe(3600);
    const b = setup({ seed: Object.fromEntries(a.store.data), offline: true });
    await b.guard.load(true);
    expect(b.guard.policy().idleTimeoutSeconds).toBe(3600);
  });

  it('a shorter policy applies at once', async () => {
    const { guard, t, expired } = setup({ policy: serverPolicy });
    await guard.begin();
    t.now = T0 + 2 * H;
    await guard.refreshPolicy();
    expect(expired).toEqual(['idle']);
  });

  it('fetches at most once an hour, unless forced, and keeps the policy it had after a failure', async () => {
    const ok = setup({ policy: serverPolicy });
    await ok.guard.begin();
    await ok.guard.refreshPolicy();
    await ok.guard.refreshPolicy();
    ok.t.now += H - 1;
    await ok.guard.refreshPolicy();
    expect(ok.fetches()).toBe(1);
    ok.t.now += 1;
    await ok.guard.refreshPolicy();
    expect(ok.fetches()).toBe(2);
    await ok.guard.refreshPolicy(true);
    expect(ok.fetches()).toBe(3);

    const off = setup({ offline: true });
    await off.guard.begin();
    expect((await off.guard.refreshPolicy()).idleTimeoutSeconds).toBe(DEFAULT_POLICY[AUDIENCE].idleTimeoutSeconds);
    expect(off.expired).toEqual([]);
  });

  it('ignores an unusable policy body and keeps the one it had', async () => {
    const { guard } = setup({ policy: { ...serverPolicy, idleTimeoutSeconds: 0 } });
    await guard.begin();
    expect((await guard.refreshPolicy()).idleTimeoutSeconds).toBe(DEFAULT_POLICY[AUDIENCE].idleTimeoutSeconds);
  });

  it('a clock moved backwards extends nothing and asks the server once', async () => {
    const { guard, t, expired, fetches } = setup({ policy: serverPolicy });
    await guard.begin();
    await guard.refreshPolicy(); // idle limit now 1 h
    t.now = T0 + 50 * 60_000;
    expect(guard.check()?.status).toBe('active'); // high-water mark: 50 min
    const before = fetches();
    t.now = T0; // the clock is set back to the moment of sign-in
    expect(guard.check()?.clockBehind).toBe(true);
    guard.check();
    await Promise.resolve();
    await Promise.resolve();
    expect(fetches() - before).toBe(1);
    expect(expired).toEqual([]);
    // only 10 min of the hour remain, however long the clock then reads
    t.now = T0 + 5 * 60_000;
    expect(guard.check()?.idleRemainingMs).toBe(10 * 60_000);
    t.now = T0 + 61 * 60_000;
    expect(guard.check()?.status).toBe('expired-idle');
  });

  it('writes the clock lazily and on flush', async () => {
    const { guard, store, t } = setup();
    await guard.begin();
    const stored = () => JSON.parse(store.data.get(keys.clock) ?? '{}') as { lastActivityAt: number };
    t.now = T0 + 40 * S;
    guard.touch();
    await Promise.resolve();
    expect(stored().lastActivityAt).toBe(T0);
    await guard.flush();
    expect(stored().lastActivityAt).toBe(T0 + 40 * S);
  });

  it('a failing secure store never breaks the session or extends it', async () => {
    const { guard, store, t, expired } = setup();
    await guard.begin();
    store.failWrites = true;
    t.now = T0 + 5 * D;
    guard.touch();
    await guard.flush();
    t.now = T0 + 40 * D;
    expect(guard.check()?.status).toBe('expired-idle');
    expect(expired).toEqual(['idle']);
  });

  it('end() forgets the clock but keeps the cached policy', async () => {
    const { guard, store } = setup({ policy: serverPolicy });
    await guard.begin();
    await guard.refreshPolicy();
    await guard.end();
    expect(guard.check()).toBeNull();
    expect(store.data.has(keys.clock)).toBe(false);
    expect(store.data.has(keys.policy)).toBe(true);
  });

  it('Stay signed in counts as activity even right after another interaction', async () => {
    const { guard, t } = setup({ policy: { ...serverPolicy, idleTimeoutSeconds: 600, warnSeconds: 60 } });
    await guard.begin();
    await guard.refreshPolicy();
    t.now = T0 + 10 * S;
    guard.touch(); // throttled: 10 s after sign-in
    guard.stay();
    expect(guard.check()?.idleRemainingMs).toBe(600 * S);
  });
});
