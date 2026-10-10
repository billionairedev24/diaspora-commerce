import { beforeEach, describe, expect, it } from 'vitest';

import { demoRoute } from '../src/api/mock/fixtures';
import { mockDriverPosition, mockFetch, resetMockServer } from '../src/api/mock/server';
import { decidePing, distanceM, type GpsFix, PING_POLICY } from '../src/domain/locationPing';
import { onShift } from '../src/domain/shift';
import { LocationPinger, type PingerDeps } from '../src/sync/locationPinger';

const T0 = Date.parse('2026-10-10T19:00:00Z');
const fix = (lat: number, lon: number, at: number, accuracyM: number | null = 10): GpsFix => ({ lat, lon, at, accuracyM });
const flush = () => new Promise((r) => setTimeout(r, 0));

describe('ping throttle (S255)', () => {
  const home = fix(29.7, -95.5, T0);
  it('sends the first fix of a shift, then every 60 s or after 200 m, never within 15 s', () => {
    expect(decidePing(null, home, T0)).toBe('send');
    expect(decidePing(home, fix(29.7, -95.5, T0 + 30_000), T0 + 30_000)).toBe('wait');
    expect(decidePing(home, fix(29.7, -95.5, T0 + 60_000), T0 + 60_000)).toBe('send');
    const moved = fix(29.7025, -95.5, T0 + 20_000); // ~280 m north
    expect(distanceM(home, moved)).toBeGreaterThan(200);
    expect(decidePing(home, moved, T0 + 20_000)).toBe('send');
    expect(decidePing(home, fix(29.7025, -95.5, T0 + 10_000), T0 + 10_000)).toBe('wait');
    expect(decidePing(home, fix(29.701, -95.5, T0 + 20_000), T0 + 20_000)).toBe('wait'); // ~110 m
  });

  it('skips stale and inaccurate fixes', () => {
    expect(decidePing(null, fix(29.7, -95.5, T0 - PING_POLICY.maxAgeMs - 1), T0)).toBe('skip-stale');
    expect(decidePing(null, fix(29.7, -95.5, T0, 900), T0)).toBe('skip-inaccurate');
    expect(decidePing(null, fix(29.7, -95.5, T0, null), T0)).toBe('send');
  });

  it('is on shift once the route is started (or inspected), not after it ends', () => {
    const r = demoRoute();
    expect(onShift(null)).toBe(false);
    expect(onShift({ ...r, status: 'IN_PROGRESS', ended: false, blocked: false })).toBe(true);
    expect(onShift({ ...r, status: 'ASSIGNED', inspection: 'PASSED', ended: false, blocked: false })).toBe(true);
    expect(onShift({ ...r, status: 'ASSIGNED', inspection: 'NONE', ended: false, blocked: false })).toBe(false);
    expect(onShift({ ...r, status: 'IN_PROGRESS', ended: true, blocked: false })).toBe(false);
  });
});

function harness(opts: { granted?: boolean; fail?: number } = {}) {
  let now = T0;
  let emit: ((f: GpsFix) => void) | null = null;
  const sent: GpsFix[] = [];
  let watching = false;
  const deps: PingerDeps = {
    async watch(onFix) {
      if (opts.granted === false) return null;
      emit = onFix;
      watching = true;
      return () => { watching = false; emit = null; };
    },
    async send(f) {
      if (opts.fail) throw Object.assign(new Error('nope'), { status: opts.fail });
      sent.push(f);
    },
    now: () => now
  };
  const pinger = new LocationPinger(deps);
  return {
    pinger, sent,
    get watching() { return watching; },
    async at(ms: number, f: GpsFix) { now = T0 + ms; emit?.(f); await flush(); }
  };
}

describe('LocationPinger', () => {
  it('pings while watching, throttled, and keeps the newest fix for event stamps', async () => {
    const h = harness();
    await h.pinger.start();
    expect(h.pinger.status).toBe('watching');
    await h.at(0, fix(29.7, -95.5, T0));
    await h.at(20_000, fix(29.7001, -95.5, T0 + 20_000));
    await h.at(61_000, fix(29.7002, -95.5, T0 + 61_000));
    expect(h.sent.map((f) => f.at - T0)).toEqual([0, 61_000]);
    expect(h.pinger.latest()).toMatchObject({ lat: 29.7002 });
  });

  it('skips pings offline (no queue) and sends the newest fresh fix when back online', async () => {
    const h = harness();
    await h.pinger.start();
    h.pinger.setOnline(false);
    await h.at(0, fix(29.7, -95.5, T0));
    await h.at(30_000, fix(29.71, -95.5, T0 + 30_000));
    expect(h.sent).toHaveLength(0);
    h.pinger.setOnline(true);
    await flush();
    expect(h.sent.map((f) => f.lat)).toEqual([29.71]);
  });

  it('respects permissions: no watch and no prompt without foreground permission', async () => {
    const h = harness({ granted: false });
    await h.pinger.start();
    expect(h.pinger.status).toBe('no-permission');
    expect(h.watching).toBe(false);
  });

  it('stops on 403 (no driver access) and when the shift ends or the app goes to the background', async () => {
    const h = harness({ fail: 403 });
    await h.pinger.start();
    await h.at(0, fix(29.7, -95.5, T0));
    expect(h.pinger.status).toBe('forbidden');
    expect(h.watching).toBe(false);

    const ok = harness();
    await ok.pinger.start();
    ok.pinger.stop();
    expect(ok.watching).toBe(false);
    expect(ok.pinger.status).toBe('idle');
  });
});

describe('POST /v1/driver/location (mock)', () => {
  beforeEach(() => resetMockServer());
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const tokenFor = (sub: string) => `${b64({ alg: 'none' })}.${b64({ sub, exp: 9999999999 })}.x`;

  const ping = (sub: string, body: unknown) =>
    mockFetch(new Request('http://localhost:8108/v1/driver/location', { method: 'POST', headers: { Authorization: `Bearer ${tokenFor(sub)}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body) }));

  it('stores the last position for the driver and refuses drivers without access', async () => {
    const res = await ping('usr_tunde', { deviceId: 'dev-1', lat: 29.6713, lon: -95.5559, accuracyM: 12, capturedAt: '2026-10-10T19:00:00.000Z' });
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ source: 'PING', lat: 29.6713 });
    expect(mockDriverPosition('usr_tunde')).toMatchObject({ lat: 29.6713, lon: -95.5559, accuracyM: 12, recordedAt: '2026-10-10T19:00:00.000Z' });
    expect((await ping('usr_tunde', { deviceId: 'dev-1', lat: 'x' })).status).toBe(400);
    expect((await ping('usr_kofi', { deviceId: 'dev-1', lat: 29.7, lon: -95.5 })).status).toBe(403);
  });
});
