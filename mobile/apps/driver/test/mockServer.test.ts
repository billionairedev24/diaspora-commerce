import { beforeEach, describe, expect, it } from 'vitest';

import { mockFetch, mockRevokeAccess, resetMockServer } from '../src/api/mock/server';

const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url');
const token = (sub: string) => `${b64({ alg: 'none' })}.${b64({ sub, exp: 9999999999 })}.x`;
const call = async (sub: string, method: string, path: string, body?: unknown) => {
  const res = await mockFetch(new Request(`http://localhost:8108${path}`, { method, headers: { Authorization: `Bearer ${token(sub)}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) }));
  return { status: res.status, body: (await res.json()) as Record<string, unknown> };
};
const ev = (sequence: number, e: object, capturedAt = '2026-10-10T19:00:00Z') => ({ eventId: `e${sequence}`, sequence, routeId: 'RT-HOU-PM', capturedAt, lat: null, lon: null, ...e });

describe('mock fulfilment driver API', () => {
  beforeEach(() => resetMockServer());

  it('answers the access check', async () => {
    expect((await call('usr_tunde', 'GET', '/v1/driver/me')).status).toBe(200);
    const kofi = await call('usr_kofi', 'GET', '/v1/driver/me');
    expect(kofi.status).toBe(403);
    expect(kofi.body.code).toBe('fulfilment.no-driver-access');
  });

  it('applies in sequence, defers gaps and replays known events', async () => {
    const r1 = await call('usr_tunde', 'POST', '/v1/driver/sync', { deviceId: 'd1', events: [ev(2, { type: 'loadout.scanned', scan: { label: 'ATS3NP8D2LQ6', method: 'CAMERA' } })] });
    expect((r1.body.results as Array<{ outcome: string }>)[0]?.outcome).toBe('DEFERRED');
    expect(r1.body.expectedSequence).toBe(1);
    const batch = [ev(1, { type: 'loadout.scanned', scan: { label: 'ATS7K2Q9H4M1', method: 'CAMERA' } }), ev(2, { type: 'loadout.scanned', scan: { label: 'ATS3NP8D2LQ6', method: 'CAMERA' } })];
    const r2 = await call('usr_tunde', 'POST', '/v1/driver/sync', { deviceId: 'd1', events: batch });
    expect((r2.body.results as Array<{ outcome: string }>).map((r) => r.outcome)).toEqual(['APPLIED', 'APPLIED']);
    const r3 = await call('usr_tunde', 'POST', '/v1/driver/sync', { deviceId: 'd1', events: batch });
    expect((r3.body.results as Array<{ replayed: boolean }>).every((r) => r.replayed)).toBe(true);
  });

  it('rejects unknown labels and wrong handover codes', async () => {
    const r = await call('usr_tunde', 'POST', '/v1/driver/sync', { deviceId: 'd2', events: [
      ev(1, { type: 'loadout.scanned', scan: { label: 'ATS000000000', method: 'MANUAL' } }),
      ev(2, { type: 'delivery.completed', stopId: 's3', delivery: { handoverMethod: 'CUSTOMER', handoverCode: '0000', photo: { source: 'LIVE_CAMERA' } } })
    ] });
    const results = r.body.results as Array<{ outcome: string; code: string }>;
    expect(results.map((x) => [x.outcome, x.code])).toEqual([['REJECTED', 'fulfilment.label-unknown'], ['REJECTED', 'orders.handover-code-invalid']]);
  });

  it('signs out on revocation but accepts events captured before it', async () => {
    mockRevokeAccess('usr_tunde');
    const r = await call('usr_tunde', 'POST', '/v1/driver/sync', { deviceId: 'd3', events: [
      ev(1, { type: 'route.started' }, '2000-01-01T00:00:00Z'),
      ev(2, { type: 'route.started' }, '2999-01-01T00:00:00Z')
    ] });
    expect(r.body.signOut).toBe(true);
    expect((r.body.results as Array<{ outcome: string }>).map((x) => x.outcome)).toEqual(['APPLIED', 'REJECTED']);
    expect((await call('usr_tunde', 'GET', '/v1/driver/me')).body.code).toBe('fulfilment.driver-access-revoked');
  });
});
