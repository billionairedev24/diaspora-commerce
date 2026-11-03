import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { z } from 'zod';

import { createSessionApi, createSessionEndBus, isApiError, sessionEndReasonOf } from '../src';
import { createDownloader, createRequester } from '../src/http';
import { endMockSession, MOCK_SESSION_COOKIE, resetMockSession, setMockSessionPolicy } from '../src/mocks';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

const seen: Request[] = [];
const viaMock = async (req: Request): Promise<Response> => {
  seen.push(req.clone());
  return handleStaffMockRequest(req);
};
const BASE = 'http://staff.test/api/bff';

describe('session API (policy, activity, signed-out reason)', () => {
  beforeEach(() => {
    seen.length = 0;
    resetMockSession('staff');
  });

  it('reads the policy in the background and answers the server clock from the Date header', async () => {
    const api = createSessionApi({ baseUrl: BASE, fetch: viaMock, sessionEnd: createSessionEndBus() });
    const { policy, serverTime } = await api.getPolicy();
    expect(seen[0]?.headers.get('X-Background')).toBe('1');
    expect(policy).toMatchObject({ audience: 'staff', idleTimeoutSeconds: 900, absoluteTimeoutSeconds: 28_800, warnSeconds: 60 });
    expect(Date.parse(policy.expiresAt ?? '')).toBeGreaterThan(Date.parse(policy.lastActivityAt ?? ''));
    expect(serverTime).not.toBeNull();
    expect(Math.abs((serverTime ?? 0) - Date.now())).toBeLessThan(5_000);
  });

  it('reports activity as a foreground request (that is what keeps the session alive)', async () => {
    const api = createSessionApi({ baseUrl: BASE, fetch: viaMock, sessionEnd: createSessionEndBus() });
    await api.reportActivity();
    expect(seen[0]?.method).toBe('POST');
    expect(seen[0]?.headers.get('X-Background')).toBeNull();
  });

  it('the mock honours the short-idle cookie and activity moves the expiry, a policy read does not', async () => {
    const withCookie = (req: Request) => viaMock(new Request(req, { headers: { ...Object.fromEntries(req.headers), cookie: `${MOCK_SESSION_COOKIE}=20:10:600` } }));
    const api = createSessionApi({ baseUrl: BASE, fetch: withCookie, sessionEnd: createSessionEndBus() });
    const a = await api.getPolicy();
    expect(a.policy).toMatchObject({ idleTimeoutSeconds: 20, warnSeconds: 10, absoluteTimeoutSeconds: 600 });
    await new Promise((r) => setTimeout(r, 30));
    const b = await api.getPolicy();
    expect(b.policy.expiresAt).toBe(a.policy.expiresAt);
    await api.reportActivity();
    const c = await api.getPolicy();
    expect(Date.parse(c.policy.expiresAt ?? '')).toBeGreaterThan(Date.parse(a.policy.expiresAt ?? ''));
  });

  it('an ended session answers 401 with the reason, /v1/me tells why', async () => {
    const bus = createSessionEndBus();
    const api = createSessionApi({ baseUrl: BASE, fetch: viaMock, sessionEnd: bus });
    endMockSession('SESSION_ABSOLUTE_TIMEOUT');
    const err = await api.getPolicy().catch((e: unknown) => e);
    expect(isApiError(err) && err.status === 401 && err.code === 'SESSION_ABSOLUTE_TIMEOUT').toBe(true);
    expect(bus.ended()).toBe('SESSION_ABSOLUTE_TIMEOUT');
    expect(await api.getSignedOutState()).toEqual({ authenticated: false, reason: 'SESSION_ABSOLUTE_TIMEOUT' });
    expect(seen.at(-1)?.headers.get('X-Background')).toBe('1');
  });

  it('a signed-in session has no reason', async () => {
    const api = createSessionApi({ baseUrl: BASE, fetch: viaMock, sessionEnd: createSessionEndBus() });
    setMockSessionPolicy({ idle: 900 });
    expect(await api.getSignedOutState()).toEqual({ authenticated: true, reason: null });
  });
});

describe('global 401 handling in the request layer', () => {
  const problem = (code: string, status = 401) =>
    new Response(JSON.stringify({ type: 'about:blank', title: 'Signed out', status, code }), { status, headers: { 'content-type': 'application/problem+json' } });

  afterEach(() => vi.restoreAllMocks());

  it('tells the page once when many requests fail with the same session code', async () => {
    const bus = createSessionEndBus();
    const listener = vi.fn();
    bus.subscribe(listener);
    const request = createRequester({ baseUrl: BASE, fetch: async () => problem('SESSION_IDLE_TIMEOUT'), sessionEnd: bus });
    const results = await Promise.allSettled(Array.from({ length: 8 }, () => request('/v1/x', z.object({}))));
    expect(results.every((r) => r.status === 'rejected')).toBe(true);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith('SESSION_IDLE_TIMEOUT');
    // a later failure with the other code does not start a second sign-out
    await request('/v1/y', z.object({})).catch(() => undefined);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('ignores other 401s (wrong password, plain unauthenticated) and other statuses with the code', async () => {
    const bus = createSessionEndBus();
    const request = createRequester({ baseUrl: BASE, fetch: async () => problem('UNAUTHENTICATED'), sessionEnd: bus });
    await request('/v1/x', z.object({})).catch(() => undefined);
    const odd = createRequester({ baseUrl: BASE, fetch: async () => problem('SESSION_IDLE_TIMEOUT', 403), sessionEnd: bus });
    await odd('/v1/x', z.object({})).catch(() => undefined);
    expect(bus.ended()).toBeUndefined();
  });

  it('downloads fail through the same path', async () => {
    const bus = createSessionEndBus();
    const download = createDownloader({ baseUrl: BASE, fetch: async () => problem('SESSION_ABSOLUTE_TIMEOUT'), sessionEnd: bus });
    await expect(download('/v1/file')).rejects.toMatchObject({ status: 401 });
    expect(bus.ended()).toBe('SESSION_ABSOLUTE_TIMEOUT');
  });

  it('a listener that subscribes after the report still hears it (late mount)', () => {
    const bus = createSessionEndBus();
    bus.report('SESSION_IDLE_TIMEOUT');
    const late = vi.fn();
    bus.subscribe(late);
    expect(late).toHaveBeenCalledWith('SESSION_IDLE_TIMEOUT');
    bus.reset();
    expect(bus.ended()).toBeUndefined();
  });

  it('knows exactly the two codes', () => {
    expect(sessionEndReasonOf('SESSION_IDLE_TIMEOUT')).toBe('SESSION_IDLE_TIMEOUT');
    expect(sessionEndReasonOf('SESSION_ABSOLUTE_TIMEOUT')).toBe('SESSION_ABSOLUTE_TIMEOUT');
    expect(sessionEndReasonOf('SESSION_EXPIRED')).toBeUndefined();
    expect(sessionEndReasonOf(undefined)).toBeUndefined();
  });
});
