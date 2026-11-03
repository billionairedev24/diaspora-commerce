/* Mock BFF for the sign-out policy (GET /v1/session/policy, POST /v1/session/activity, the `session` and
   `signedOutReason` members of GET /v1/me). By default the mock session never ends (an app running on the mocks must not
   sign a developer out): the policy reports the audience's standard numbers and the session is always fresh. To exercise
   the timer, shorten it:
   - in tests, setMockSessionPolicy() / endMockSession() on the process-wide session;
   - in a browser, the cookie MOCK_SESSION_COOKIE = "idle:warn:absolute[:tag]" in seconds (e.g. "30:20:600"). Each distinct
     cookie value is its own session with real clocks (activity moves `lastActivityAt`, the policy and /v1/me never do), so
     one test cannot end another's. */
import { http, HttpResponse } from 'msw';

import { bffProblemType } from '../problem-types';
import { type SessionAudience, type SessionEndReason, type SessionPolicy } from '../schemas/session';
import { readCookie } from './http-kit';

/** Cookie that gives a browser its own short mock session: "idle:warn:absolute[:tag]" in seconds. */
export const MOCK_SESSION_COOKIE = 'at_mock_session';

/** The standard policy of an audience (the BFF's built-in defaults, docs/engineering/session-timeouts.md). */
export const MOCK_SESSION_DEFAULTS: Record<'staff' | 'seller' | 'customer-web', { idle: number; absolute: number; warn: number }> = {
  staff: { idle: 15 * 60, absolute: 8 * 3600, warn: 60 },
  seller: { idle: 30 * 60, absolute: 12 * 3600, warn: 60 },
  'customer-web': { idle: 30 * 86_400, absolute: 90 * 86_400, warn: 0 }
};

interface MockSessionState {
  audience: SessionAudience;
  idle: number;
  absolute: number;
  warn: number;
  createdAt: number;
  lastActivityAt: number;
  ended: { reason: SessionEndReason; at: number } | null;
  /** False: the session never ends and is always fresh (the default). */
  enforce: boolean;
}

/** The process-wide session (tests) and the sessions of browsers that sent MOCK_SESSION_COOKIE. */
let shared: MockSessionState | null = null;
const byCookie = new Map<string, MockSessionState>();

const ENDED_MEMORY_MS = 10 * 60_000;

function fresh(audience: SessionAudience, enforce = false): MockSessionState {
  const d = MOCK_SESSION_DEFAULTS[audience === 'staff' || audience === 'seller' ? audience : 'customer-web'];
  const now = Date.now();
  return { audience, idle: d.idle, absolute: d.absolute, warn: d.warn, createdAt: now, lastActivityAt: now, ended: null, enforce };
}

function current(audience: SessionAudience, request?: Request): MockSessionState {
  const raw = request ? readCookie(request, MOCK_SESSION_COOKIE) : null;
  if (raw) {
    let s = byCookie.get(raw);
    if (!s) {
      const [idle, warn, absolute] = raw.split(':').map((n) => Number(n));
      s = fresh(audience, true);
      if (idle && idle > 0) s.idle = idle;
      if (warn !== undefined && warn >= 0 && !Number.isNaN(warn)) s.warn = warn;
      if (absolute && absolute > 0) s.absolute = absolute;
      byCookie.set(raw, s);
    }
    return s;
  }
  if (!shared || shared.audience !== audience) shared = fresh(audience);
  return shared;
}

/** Starts a fresh mock session that never ends (call between tests). */
export function resetMockSession(audience: SessionAudience = 'staff'): void {
  shared = fresh(audience);
  byCookie.clear();
}

/** Changes the process-wide mock policy (seconds) and makes it end for real: a short idle timeout for tests. */
export function setMockSessionPolicy(patch: { audience?: SessionAudience; idle?: number; warn?: number; absolute?: number; signInAgeSeconds?: number }): void {
  const s = current(patch.audience ?? shared?.audience ?? 'staff');
  s.enforce = true;
  if (patch.idle !== undefined) s.idle = patch.idle;
  if (patch.warn !== undefined) s.warn = patch.warn;
  if (patch.absolute !== undefined) s.absolute = patch.absolute;
  if (patch.signInAgeSeconds !== undefined) s.createdAt = Date.now() - patch.signInAgeSeconds * 1000;
}

/** Ends the process-wide mock session as the policy would: every session call answers 401, /v1/me tells why for ten minutes. */
export function endMockSession(reason: SessionEndReason = 'SESSION_IDLE_TIMEOUT'): void {
  const s = current(shared?.audience ?? 'staff');
  s.enforce = true;
  s.ended = { reason, at: Date.now() };
}

/** Signs in again after an end (a new session). */
export function startMockSession(audience: SessionAudience = 'staff'): void {
  shared = fresh(audience);
}

function endsAt(s: MockSessionState): { expiresAt: number; reason: SessionEndReason } {
  const idleEnd = s.lastActivityAt + s.idle * 1000;
  const absEnd = s.createdAt + s.absolute * 1000;
  return idleEnd <= absEnd ? { expiresAt: idleEnd, reason: 'SESSION_IDLE_TIMEOUT' } : { expiresAt: absEnd, reason: 'SESSION_ABSOLUTE_TIMEOUT' };
}

/** Marks the session ended when its time has passed; returns the reason when it is (or was) ended. */
function check(s: MockSessionState, now = Date.now()): SessionEndReason | null {
  if (!s.enforce && !s.ended) return null;
  if (!s.ended) {
    const e = endsAt(s);
    if (now > e.expiresAt) s.ended = { reason: e.reason, at: now };
  }
  return s.ended?.reason ?? null;
}

function policyOf(s: MockSessionState): SessionPolicy {
  // Never ends: always fresh.
  if (!s.enforce) s = { ...s, createdAt: Date.now(), lastActivityAt: Date.now() };
  return {
    audience: s.audience,
    idleTimeoutSeconds: s.idle,
    absoluteTimeoutSeconds: s.absolute,
    warnSeconds: s.warn,
    lastActivityAt: new Date(s.lastActivityAt).toISOString(),
    expiresAt: new Date(endsAt(s).expiresAt).toISOString()
  };
}

/** The `session` / `signedOutReason` members GET /v1/me adds for a signed-in (or just signed-out) mock session. */
export function mockSessionMembers(audience: SessionAudience, signedIn: boolean, request?: Request): { session?: SessionPolicy; signedOutReason?: SessionEndReason } {
  const s = current(audience, request);
  const reason = check(s);
  if (reason) return s.ended && Date.now() - s.ended.at <= ENDED_MEMORY_MS ? { signedOutReason: reason } : {};
  return signedIn ? { session: policyOf(s) } : {};
}

/** Whether the mock session has ended (so /v1/me answers as a guest). */
export function mockSessionEnded(audience: SessionAudience, request?: Request): boolean {
  return check(current(audience, request)) !== null;
}

function ended(reason: SessionEndReason): Response {
  return HttpResponse.json(
    { type: bffProblemType(reason), title: 'Signed out', status: 401, code: reason, detail: reason === 'SESSION_IDLE_TIMEOUT' ? 'You were signed out because you were inactive.' : 'Your session reached its maximum length.' },
    { status: 401, headers: { 'Content-Type': 'application/problem+json', Date: new Date().toUTCString() } }
  );
}

/**
 * Handlers for the session timer. `withMe` adds GET /v1/me for the back offices (the storefront's own /v1/me carries the
 * same members through {@link mockSessionMembers}); there the answer is a guest once the session has ended.
 */
export function sessionHandlers(audience: SessionAudience, opts: { withMe?: boolean } = {}) {
  const handlers = [
    http.get('*/v1/session/policy', ({ request }) => {
      const s = current(audience, request);
      const reason = check(s);
      if (reason) return ended(reason);
      return HttpResponse.json(policyOf(s), { headers: { Date: new Date().toUTCString() } });
    }),
    http.post('*/v1/session/activity', ({ request }) => {
      const s = current(audience, request);
      const reason = check(s);
      if (reason) return ended(reason);
      s.lastActivityAt = Date.now();
      return new HttpResponse(null, { status: 204, headers: { Date: new Date().toUTCString() } });
    })
  ];
  if (opts.withMe) {
    handlers.push(
      http.get('*/v1/me', ({ request }) => {
        const signedIn = !mockSessionEnded(audience, request);
        return HttpResponse.json({ authenticated: signedIn, user: null, counts: null, ...mockSessionMembers(audience, signedIn, request) });
      })
    );
  }
  return handlers;
}
