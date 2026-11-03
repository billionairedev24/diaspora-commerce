/* Sign-out after inactivity and after a maximum session length, decided on the device (docs/engineering/session-timeouts.md).
   The gateway-bff cannot see taps in a native app and cannot cap the lifetime of a refresh token, so the app enforces
   both limits itself: IDLE (no real foreground interaction for `idleTimeoutSeconds`) and ABSOLUTE (`absoluteTimeoutSeconds`
   after sign-in, however active the person is). Time in the background counts as idle.

   This file is identical in the customer app and the driver app (the audience is a parameter); keep them in step.
   It is pure: no React Native, no storage, no clock of its own.

   Clock safety. Times are wall-clock milliseconds because they must survive a restart. Wall clocks can be moved, so every
   decision uses the latest time the device has ever seen (`highWaterAt`) as a floor for "now". Moving the clock
   backwards therefore never shortens the elapsed time and never extends a session; it only sets `clockBehind`, which asks
   the app to check with the server. (Moving the clock forwards ends the session early, the safe direction.) Inside one
   run the caller advances `highWaterAt` on every check, so a change made while the app is running is seen at once. */

export type SessionAudience = 'customer-app' | 'driver';

export interface SessionPolicy {
  audience: SessionAudience;
  idleTimeoutSeconds: number;
  absoluteTimeoutSeconds: number;
  /** Show "You'll be signed out in N seconds" this long before the idle timeout; 0 = no warning. */
  warnSeconds: number;
}

const HOUR = 3600;
const DAY = 24 * HOUR;

/** The standard settings, mirrored from SessionPolicyDefaults.java. Used until a policy was fetched, and whenever the
    one on hand is unusable: the fail-safe is a limit, never "no limit". */
export const DEFAULT_POLICY: Record<SessionAudience, SessionPolicy> = {
  'customer-app': { audience: 'customer-app', idleTimeoutSeconds: 30 * DAY, absoluteTimeoutSeconds: 90 * DAY, warnSeconds: 0 },
  driver: { audience: 'driver', idleTimeoutSeconds: 14 * HOUR, absoluteTimeoutSeconds: 14 * HOUR, warnSeconds: 0 }
};

/** Bounds identity enforces (docs/engineering/session-timeouts.md); a value outside them is not trusted. */
const BOUNDS = { idleMin: 60, idleMax: 180 * DAY, absoluteMin: 300, absoluteMax: 365 * DAY, warnMax: 300 };

/** Real interaction is recorded at most this often. */
export const ACTIVITY_THROTTLE_MS = 30_000;
/** The stored activity time is written to the secure store at most this often (and whenever the app goes to the background). */
export const PERSIST_EVERY_MS = 2 * 60_000;
/** The policy is fetched at most this often (a forced fetch, after a clock change, ignores it). */
export const POLICY_REFETCH_MS = 60 * 60_000;
/** A foreground timer never sleeps longer than this, so a clock change is noticed within a minute. */
export const MAX_RECHECK_MS = 60_000;

export type SessionEndReason = 'idle' | 'absolute';
export type SessionStatus = 'active' | 'expired-idle' | 'expired-absolute';

function wholeSeconds(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) && Number.isInteger(v) ? v : null;
}

/** Validates the body of `GET /v1/session/policy`. Null = unusable (wrong shape, out of bounds, or another audience's
    policy), and the caller keeps the policy it had. */
export function parsePolicy(raw: unknown, audience: SessionAudience): SessionPolicy | null {
  if (!raw || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  if (o.audience !== audience) return null;
  const idle = wholeSeconds(o.idleTimeoutSeconds);
  const absolute = wholeSeconds(o.absoluteTimeoutSeconds);
  const warn = wholeSeconds(o.warnSeconds);
  if (idle === null || absolute === null || warn === null) return null;
  if (idle < BOUNDS.idleMin || idle > BOUNDS.idleMax) return null;
  if (absolute < BOUNDS.absoluteMin || absolute > BOUNDS.absoluteMax || idle > absolute) return null;
  if (warn < 0 || warn > BOUNDS.warnMax || warn >= idle) return null;
  return { audience, idleTimeoutSeconds: idle, absoluteTimeoutSeconds: absolute, warnSeconds: warn };
}

export interface CachedPolicy {
  policy: SessionPolicy;
  /** Wall-clock ms of the successful fetch. */
  fetchedAt: number;
}

export function serializePolicy(c: CachedPolicy): string {
  return JSON.stringify({ v: 1, ...c });
}

/** The cached policy from the secure store, or null when absent or damaged (then the defaults apply). */
export function parseStoredPolicy(raw: string | null, audience: SessionAudience): CachedPolicy | null {
  if (!raw) return null;
  try {
    const o = JSON.parse(raw) as { policy?: unknown; fetchedAt?: unknown };
    const policy = parsePolicy(o.policy, audience);
    const fetchedAt = o.fetchedAt;
    if (!policy || typeof fetchedAt !== 'number' || !Number.isFinite(fetchedAt)) return null;
    return { policy, fetchedAt };
  } catch {
    return null;
  }
}

/** The policy in force: the fetched or cached one, else the default for this app. */
export function effectivePolicy(cached: CachedPolicy | null, audience: SessionAudience): SessionPolicy {
  return cached?.policy ?? DEFAULT_POLICY[audience];
}

/** Whether to ask the server for the policy now: never fetched, an hour old, or the clock is behind the last fetch. */
export function shouldFetchPolicy(fetchedAt: number | null, now: number, force = false): boolean {
  if (force || fetchedAt === null) return true;
  return now < fetchedAt || now - fetchedAt >= POLICY_REFETCH_MS;
}

export interface SessionClock {
  /** When this sign-in happened (wall-clock ms). The absolute limit counts from here. */
  signedInAt: number;
  /** The last real foreground interaction. */
  lastActivityAt: number;
  /** The latest wall-clock time ever seen by this session: a floor for "now". */
  highWaterAt: number;
}

export function startClock(now: number): SessionClock {
  return { signedInAt: now, lastActivityAt: now, highWaterAt: now };
}

export function serializeClock(c: SessionClock): string {
  return JSON.stringify({ v: 1, ...c });
}

/** The stored clock, or null when absent or damaged. The three times are made consistent (activity not before sign-in,
    high-water not before either), so a hand-edited value can only shorten a session. */
export function parseStoredClock(raw: string | null): SessionClock | null {
  if (!raw) return null;
  try {
    const o = JSON.parse(raw) as Record<string, unknown>;
    const { signedInAt, lastActivityAt, highWaterAt } = o;
    if (![signedInAt, lastActivityAt, highWaterAt].every((x) => typeof x === 'number' && Number.isFinite(x) && x > 0)) return null;
    const s = signedInAt as number;
    const a = Math.max(lastActivityAt as number, s);
    return { signedInAt: s, lastActivityAt: a, highWaterAt: Math.max(highWaterAt as number, a) };
  } catch {
    return null;
  }
}

/** Advances the high-water mark; the same object when nothing changed. */
export function observe(clock: SessionClock, now: number): SessionClock {
  return now > clock.highWaterAt ? { ...clock, highWaterAt: now } : clock;
}

export interface SessionWarning {
  secondsLeft: number;
}

export interface SessionCheck {
  status: SessionStatus;
  /** Milliseconds until the idle limit / the absolute limit (0 when passed). */
  idleRemainingMs: number;
  absoluteRemainingMs: number;
  /** The wall clock reads earlier than something already seen: ask the server (a forced policy fetch) and do not trust it. */
  clockBehind: boolean;
  /** Only while active, `warnSeconds` > 0, inside the warning window and the idle limit comes first (staying signed in helps). */
  warning: SessionWarning | null;
  /** When to look again, in ms: the next limit or the start of the warning window, one second while warning, never over a minute. */
  recheckInMs: number;
}

/** The one decision: is this session still allowed, and when to look again. */
export function checkSession(policy: SessionPolicy, clock: SessionClock, now: number): SessionCheck {
  const floor = Math.max(clock.highWaterAt, clock.lastActivityAt, clock.signedInAt);
  const clockBehind = now < floor;
  const t = Math.max(now, floor); // never earlier than anything already seen: a clock moved backwards extends nothing
  const idleRemainingMs = Math.max(0, clock.lastActivityAt + policy.idleTimeoutSeconds * 1000 - t);
  const absoluteRemainingMs = Math.max(0, clock.signedInAt + policy.absoluteTimeoutSeconds * 1000 - t);
  const status: SessionStatus = absoluteRemainingMs === 0 ? 'expired-absolute' : idleRemainingMs === 0 ? 'expired-idle' : 'active';
  if (status !== 'active') return { status, idleRemainingMs, absoluteRemainingMs, clockBehind, warning: null, recheckInMs: 0 };

  const warnMs = policy.warnSeconds * 1000;
  const inWarning = warnMs > 0 && idleRemainingMs <= warnMs && idleRemainingMs < absoluteRemainingMs;
  const warning = inWarning ? { secondsLeft: Math.ceil(idleRemainingMs / 1000) } : null;
  let next = Math.min(idleRemainingMs, absoluteRemainingMs);
  if (warnMs > 0 && idleRemainingMs > warnMs) next = Math.min(next, idleRemainingMs - warnMs);
  if (inWarning) next = Math.min(next, 1000);
  return { status, idleRemainingMs, absoluteRemainingMs, clockBehind, warning, recheckInMs: Math.min(Math.max(next, 1000), MAX_RECHECK_MS) };
}

/** Records real interaction. Null = nothing to record: throttled, the clock is not ahead of the last activity (a clock
    moved backwards must not look like activity), or the session has already ended (a tap never revives it). */
export function recordActivity(policy: SessionPolicy, clock: SessionClock, now: number, throttleMs = ACTIVITY_THROTTLE_MS): SessionClock | null {
  if (checkSession(policy, clock, now).status !== 'active') return null;
  if (now <= clock.lastActivityAt || now < clock.highWaterAt) return null;
  if (now - clock.lastActivityAt < throttleMs) return null;
  return { ...clock, lastActivityAt: now, highWaterAt: now };
}

/** Whether the in-memory clock should be written to the secure store now (lazily; always done when going to the background). */
export function shouldPersistClock(persisted: SessionClock | null, current: SessionClock, now: number, every = PERSIST_EVERY_MS): boolean {
  if (!persisted) return true;
  if (persisted.signedInAt !== current.signedInAt) return true;
  return current.lastActivityAt > persisted.lastActivityAt && now - persisted.lastActivityAt >= every;
}

export function reasonOf(status: SessionStatus): SessionEndReason | null {
  return status === 'expired-idle' ? 'idle' : status === 'expired-absolute' ? 'absolute' : null;
}

/** The `code` of an RFC 9457 problem the BFF answers 401 with when it ends a session, mapped to the reason. */
export function reasonFromProblemCode(code: unknown): SessionEndReason | null {
  if (code === 'SESSION_IDLE_TIMEOUT') return 'idle';
  if (code === 'SESSION_ABSOLUTE_TIMEOUT') return 'absolute';
  return null;
}
