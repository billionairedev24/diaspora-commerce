import { describe, expect, it } from 'vitest';

import {
  ACTIVITY_THROTTLE_MS,
  checkSession,
  DEFAULT_POLICY,
  effectivePolicy,
  MAX_RECHECK_MS,
  observe,
  parsePolicy,
  parseStoredClock,
  parseStoredPolicy,
  reasonFromProblemCode,
  reasonOf,
  recordActivity,
  serializeClock,
  serializePolicy,
  type SessionAudience,
  type SessionPolicy,
  shouldFetchPolicy,
  shouldPersistClock,
  startClock
} from '../src/domain/sessionPolicy';

const AUDIENCE: SessionAudience = 'customer-app';
const S = 1000;
const H = 3600 * S;
const D = 24 * H;
const T0 = Date.UTC(2026, 0, 1);
const pol = (over: Partial<SessionPolicy> = {}): SessionPolicy => ({ ...DEFAULT_POLICY[AUDIENCE], ...over });

describe('defaults', () => {
  it('are 30 days idle and 90 days absolute for the customer app, 14 h and 14 h for the driver app, with no warning', () => {
    expect(DEFAULT_POLICY['customer-app']).toMatchObject({ idleTimeoutSeconds: 30 * 86400, absoluteTimeoutSeconds: 90 * 86400, warnSeconds: 0 });
    expect(DEFAULT_POLICY.driver).toMatchObject({ idleTimeoutSeconds: 14 * 3600, absoluteTimeoutSeconds: 14 * 3600, warnSeconds: 0 });
  });

  it('apply when no policy was ever fetched', () => {
    expect(effectivePolicy(null, 'customer-app')).toBe(DEFAULT_POLICY['customer-app']);
    expect(effectivePolicy(null, 'driver')).toBe(DEFAULT_POLICY.driver);
  });
});

describe('checkSession', () => {
  it('is active inside both limits', () => {
    const c = checkSession(pol(), startClock(T0), T0 + 10 * D);
    expect(c.status).toBe('active');
    expect(c.idleRemainingMs).toBe(20 * D);
    expect(c.absoluteRemainingMs).toBe(80 * D);
    expect(c.clockBehind).toBe(false);
  });

  it('expires idle exactly at the idle limit, counted from the last activity', () => {
    const clock = { signedInAt: T0, lastActivityAt: T0 + 40 * D, highWaterAt: T0 + 40 * D };
    expect(checkSession(pol(), clock, T0 + 70 * D - 1).status).toBe('active');
    expect(checkSession(pol(), clock, T0 + 70 * D).status).toBe('expired-idle');
  });

  it('expires at the absolute limit however active the person is', () => {
    const clock = { signedInAt: T0, lastActivityAt: T0 + 89 * D, highWaterAt: T0 + 89 * D };
    expect(checkSession(pol(), clock, T0 + 90 * D - 1).status).toBe('active');
    expect(checkSession(pol(), clock, T0 + 90 * D).status).toBe('expired-absolute');
  });

  it('reports absolute when both limits have passed', () => {
    expect(checkSession(pol(), startClock(T0), T0 + 200 * D).status).toBe('expired-absolute');
    expect(reasonOf('expired-absolute')).toBe('absolute');
    expect(reasonOf('expired-idle')).toBe('idle');
    expect(reasonOf('active')).toBeNull();
  });

  it('counts time in the background as idle (no activity while away)', () => {
    const p = pol({ idleTimeoutSeconds: 14 * 3600, absoluteTimeoutSeconds: 24 * 3600 });
    const clock = startClock(T0);
    expect(checkSession(p, clock, T0 + 13 * H).status).toBe('active');
    expect(checkSession(p, clock, T0 + 15 * H).status).toBe('expired-idle');
  });

  it('never sleeps longer than a minute, and never less than a second', () => {
    expect(checkSession(pol(), startClock(T0), T0).recheckInMs).toBe(MAX_RECHECK_MS);
    const p = pol({ idleTimeoutSeconds: 120, absoluteTimeoutSeconds: 3600 });
    expect(checkSession(p, startClock(T0), T0 + 119_500).recheckInMs).toBe(1000);
    expect(checkSession(p, startClock(T0), T0 + 120 * S).recheckInMs).toBe(0);
  });

  it('warns only inside the warning window, only when the idle limit comes first, and only when a warning is configured', () => {
    const p = pol({ idleTimeoutSeconds: 600, absoluteTimeoutSeconds: 7200, warnSeconds: 60 });
    expect(checkSession(p, startClock(T0), T0 + 500 * S).warning).toBeNull();
    const c = checkSession(p, startClock(T0), T0 + 560 * S + 400);
    expect(c.warning).toEqual({ secondsLeft: 40 });
    expect(c.recheckInMs).toBe(1000);
    expect(checkSession(pol({ ...p, warnSeconds: 0 }), startClock(T0), T0 + 590 * S).warning).toBeNull();
    // the session length ends first: "stay signed in" would not help, so no warning
    const near = { signedInAt: T0, lastActivityAt: T0 + 7000 * S, highWaterAt: T0 + 7000 * S };
    expect(checkSession(p, near, T0 + 7150 * S).warning).toBeNull();
    // the timer wakes when the warning window opens
    expect(checkSession(p, startClock(T0), T0 + 538 * S).recheckInMs).toBe(2000);
  });
});

describe('the wall clock moving', () => {
  const clock = { signedInAt: T0, lastActivityAt: T0 + 10 * D, highWaterAt: T0 + 12 * D };

  it('backwards never extends a session: elapsed time is floored at what was already seen', () => {
    const normal = checkSession(pol(), clock, T0 + 12 * D);
    const back = checkSession(pol(), clock, T0 + 1 * D);
    expect(back.clockBehind).toBe(true);
    expect(back.idleRemainingMs).toBe(normal.idleRemainingMs);
    expect(back.absoluteRemainingMs).toBe(normal.absoluteRemainingMs);
    expect(back.status).toBe('active');
  });

  it('backwards cannot revive a session that already ended', () => {
    const ended = { signedInAt: T0, lastActivityAt: T0, highWaterAt: T0 + 31 * D };
    expect(checkSession(pol(), ended, T0 + 2 * D).status).toBe('expired-idle');
    expect(recordActivity(pol(), ended, T0 + 2 * D)).toBeNull();
  });

  it('a clock before the sign-in time is behind too', () => {
    expect(checkSession(pol(), startClock(T0), T0 - D).clockBehind).toBe(true);
  });

  it('forwards ends the session early (the safe direction)', () => {
    expect(checkSession(pol(), startClock(T0), T0 + 31 * D).status).toBe('expired-idle');
  });

  it('observe only ever moves the high-water mark forward', () => {
    const c = startClock(T0);
    expect(observe(c, T0 - H)).toBe(c);
    expect(observe(c, T0 + H).highWaterAt).toBe(T0 + H);
  });

  it('activity while the clock is behind records nothing', () => {
    expect(recordActivity(pol(), clock, T0 + 1 * D)).toBeNull();
    expect(recordActivity(pol(), clock, clock.lastActivityAt)).toBeNull();
  });
});

describe('recordActivity', () => {
  it('moves the last activity and the high-water mark, throttled to the interval', () => {
    const c = startClock(T0);
    expect(recordActivity(pol(), c, T0 + ACTIVITY_THROTTLE_MS - 1)).toBeNull();
    const next = recordActivity(pol(), c, T0 + ACTIVITY_THROTTLE_MS);
    expect(next).toEqual({ signedInAt: T0, lastActivityAt: T0 + ACTIVITY_THROTTLE_MS, highWaterAt: T0 + ACTIVITY_THROTTLE_MS });
  });

  it('can be forced past the throttle (Stay signed in)', () => {
    expect(recordActivity(pol(), startClock(T0), T0 + 5 * S, 0)?.lastActivityAt).toBe(T0 + 5 * S);
  });

  it('does not extend the absolute limit', () => {
    const c = recordActivity(pol(), { signedInAt: T0, lastActivityAt: T0 + 70 * D, highWaterAt: T0 + 70 * D }, T0 + 89 * D);
    expect(c?.signedInAt).toBe(T0);
    expect(checkSession(pol(), c ?? startClock(0), T0 + 90 * D).status).toBe('expired-absolute');
  });

  it('never revives an expired session', () => {
    expect(recordActivity(pol(), startClock(T0), T0 + 31 * D)).toBeNull();
  });
});

describe('the policy from the server', () => {
  const ok = { audience: 'customer-app', idleTimeoutSeconds: 3600, absoluteTimeoutSeconds: 86400, warnSeconds: 60, lastActivityAt: null, expiresAt: null };

  it('is accepted when valid, ignoring the null cookie-only members', () => {
    expect(parsePolicy(ok, 'customer-app')).toEqual({ audience: 'customer-app', idleTimeoutSeconds: 3600, absoluteTimeoutSeconds: 86400, warnSeconds: 60 });
  });

  it.each([
    ['another audience', { ...ok, audience: 'driver' }],
    ['a staff audience', { ...ok, audience: 'staff' }],
    ['a missing field', { ...ok, warnSeconds: undefined }],
    ['a string number', { ...ok, idleTimeoutSeconds: '3600' }],
    ['zero idle', { ...ok, idleTimeoutSeconds: 0 }],
    ['negative idle', { ...ok, idleTimeoutSeconds: -5 }],
    ['a fraction', { ...ok, idleTimeoutSeconds: 3600.5 }],
    ['NaN', { ...ok, absoluteTimeoutSeconds: Number.NaN }],
    ['idle over the absolute limit', { ...ok, idleTimeoutSeconds: 90000 }],
    ['idle under a minute', { ...ok, idleTimeoutSeconds: 30 }],
    ['absolute over a year', { ...ok, absoluteTimeoutSeconds: 366 * 86400 }],
    ['warning not shorter than idle', { ...ok, warnSeconds: 3600 }],
    ['a negative warning', { ...ok, warnSeconds: -1 }],
    ['an over-long warning', { ...ok, warnSeconds: 301 }]
  ])('is rejected for %s', (_n, body) => {
    expect(parsePolicy(body, 'customer-app')).toBeNull();
  });

  it('is rejected for non-objects', () => {
    expect(parsePolicy(null, 'customer-app')).toBeNull();
    expect(parsePolicy('x', 'customer-app')).toBeNull();
    expect(parsePolicy(undefined, 'customer-app')).toBeNull();
  });

  it('is fetched when never fetched, an hour old, forced, or the clock is behind the last fetch; not before', () => {
    expect(shouldFetchPolicy(null, T0)).toBe(true);
    expect(shouldFetchPolicy(T0, T0 + H - 1)).toBe(false);
    expect(shouldFetchPolicy(T0, T0 + H)).toBe(true);
    expect(shouldFetchPolicy(T0, T0 + 1, true)).toBe(true);
    expect(shouldFetchPolicy(T0, T0 - 1)).toBe(true);
  });
});

describe('what is stored on the device', () => {
  const policy = pol({ idleTimeoutSeconds: 7200, absoluteTimeoutSeconds: 86400 });

  it('round-trips the cached policy, and falls back to the defaults when it is damaged or for another app', () => {
    expect(parseStoredPolicy(serializePolicy({ policy, fetchedAt: T0 }), AUDIENCE)).toEqual({ policy, fetchedAt: T0 });
    expect(parseStoredPolicy(serializePolicy({ policy, fetchedAt: T0 }), 'driver')).toBeNull();
    expect(parseStoredPolicy('{not json', AUDIENCE)).toBeNull();
    expect(parseStoredPolicy(JSON.stringify({ policy: { ...policy, idleTimeoutSeconds: 0 }, fetchedAt: T0 }), AUDIENCE)).toBeNull();
    expect(parseStoredPolicy(JSON.stringify({ policy, fetchedAt: 'x' }), AUDIENCE)).toBeNull();
    expect(parseStoredPolicy(null, AUDIENCE)).toBeNull();
  });

  it('round-trips the clock and repairs inconsistent values only in the shorter direction', () => {
    const clock = { signedInAt: T0, lastActivityAt: T0 + H, highWaterAt: T0 + 2 * H };
    expect(parseStoredClock(serializeClock(clock))).toEqual(clock);
    expect(parseStoredClock(JSON.stringify({ signedInAt: T0, lastActivityAt: T0 - D, highWaterAt: T0 - D }))).toEqual({ signedInAt: T0, lastActivityAt: T0, highWaterAt: T0 });
    expect(parseStoredClock('nope')).toBeNull();
    expect(parseStoredClock(JSON.stringify({ signedInAt: 0, lastActivityAt: 1, highWaterAt: 1 }))).toBeNull();
    expect(parseStoredClock(null)).toBeNull();
  });

  it('writes the clock lazily', () => {
    const a = startClock(T0);
    expect(shouldPersistClock(null, a, T0)).toBe(true);
    const b = { ...a, lastActivityAt: T0 + 30 * S, highWaterAt: T0 + 30 * S };
    expect(shouldPersistClock(a, b, T0 + 30 * S)).toBe(false);
    const c = { ...a, lastActivityAt: T0 + 130 * S, highWaterAt: T0 + 130 * S };
    expect(shouldPersistClock(a, c, T0 + 130 * S)).toBe(true);
    expect(shouldPersistClock(a, { ...a, signedInAt: T0 + 1 }, T0)).toBe(true);
  });
});

describe('401 problem codes', () => {
  it('map to the same reasons as the local decision', () => {
    expect(reasonFromProblemCode('SESSION_IDLE_TIMEOUT')).toBe('idle');
    expect(reasonFromProblemCode('SESSION_ABSOLUTE_TIMEOUT')).toBe('absolute');
    expect(reasonFromProblemCode('TOKEN_EXPIRED')).toBeNull();
    expect(reasonFromProblemCode(undefined)).toBeNull();
    expect(reasonFromProblemCode(401)).toBeNull();
  });
});
