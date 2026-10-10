import { describe, expect, it } from 'vitest';

import { demoRoute } from '../src/api/mock/fixtures';
import { decideAccess } from '../src/domain/access';
import { applyLocal } from '../src/domain/routeState';
import { canArrive, canConfirmPickup, canEndShift, canStartRoute, loadOutDone, phases, primaryAction, returnsExpected } from '../src/domain/shift';
import { type CheckKey, type CheckResult, type DriverEventBody, type Route } from '../src/domain/types';

const at = '2026-10-10T19:00:00Z';
const run = (r: Route, events: DriverEventBody[]) => events.reduce((acc, e) => applyLocal(acc, e, at), r);
const checks: Record<CheckKey, CheckResult> = { TYRES: 'PASS', BRAKES: 'PASS', LIGHTS: 'PASS', MIRRORS: 'PASS', HORN_WIPERS: 'PASS', NO_LEAKS: 'PASS', CARGO_AREA: 'PASS', COLD_BOX: 'PASS', SAFETY_KIT: 'PASS', PHONE_MOUNT: 'PASS' };
const photo = { source: 'LIVE_CAMERA' as const, capturedAt: at, lat: 29.7, lon: -95.5, accuracyM: 5, stampRouteId: 'RT-HOU-PM', sha256: 'x' };
const inspection = (over: Partial<typeof checks> = {}): DriverEventBody => ({ type: 'inspection.submitted', inspection: { vehicleId: 'V', checks: { ...checks, ...over }, odometerMiles: 48222, fuelPercent: 80, coldBoxF: 37, photos: { FRONT: photo, BACK: photo, LEFT: photo, RIGHT: photo }, certified: true } });
const loadAll = (r: Route): DriverEventBody[] => r.packages.filter((p) => p.origin === 'FC').map((p) => ({ type: 'loadout.scanned', scan: { label: p.label, method: 'CAMERA' } }));

describe('shift flow', () => {
  it('unlocks inspection → load-out → route → end', () => {
    let r = demoRoute();
    expect(primaryAction(r).target).toBe('inspect');
    expect(phases(r)[1]?.locked).toBe(true);
    r = run(r, [inspection()]);
    expect(r.inspection).toBe('PASSED');
    expect(primaryAction(r).target).toBe('loadout');
    expect(canStartRoute(r)).toBe('Finish the load-out scan first.');
    r = run(r, loadAll(r));
    expect(loadOutDone(r)).toBe(true);
    expect(primaryAction(r).target).toBe('start-route');
    r = run(r, [{ type: 'loadout.completed' }, { type: 'route.started' }]);
    expect(primaryAction(r).label).toBe('Continue route');
  });

  it('blocks the route when a critical check fails', () => {
    const r = run(demoRoute(), [inspection({ BRAKES: 'FAIL' })]);
    expect(r.blocked).toBe(true);
    expect(primaryAction(r).disabled).toBe(true);
    expect(canStartRoute(r)).toMatch(/blocked/);
  });

  it('requires seller pickups before the drop-off and all scans or a reason', () => {
    let r = run(demoRoute(), [inspection()]);
    r = run(r, [...loadAll(r), { type: 'loadout.completed' }, { type: 'route.started' }]);
    const s3 = r.stops.find((s) => s.id === 's3');
    const s1 = r.stops.find((s) => s.id === 's1');
    if (!s3 || !s1) throw new Error('fixture');
    expect(canArrive(r, s3)).toMatch(/Pick up from the seller first/);
    r = run(r, [{ type: 'stop.arrived', stopId: 's1' }, { type: 'stop.scanned', stopId: 's1', scan: { label: 'ATS5RM2J7VZ3', method: 'MANUAL' } }]);
    expect(canConfirmPickup(r, s1, 'Titi', '')).toMatch(/Scan every package first \(1\/2\)/);
    expect(canConfirmPickup(r, s1, 'Titi', 'Tray not ready')).toBeNull();
    expect(r.packages.find((p) => p.label === 'ATS5RM2J7VZ3')?.manualEntry).toBe(true);
    r = run(r, [{ type: 'stop.scanned', stopId: 's1', scan: { label: 'ATS8HD6K3PF2', method: 'CAMERA' } }, { type: 'pickup.confirmed', stopId: 's1', pickup: { handedOverBy: 'Titi' } }]);
    expect(canArrive(r, r.stops.find((s) => s.id === 's3') ?? s3)).toBeNull();
  });

  it('needs reattempts resolved, returns scanned and a higher odometer to end the shift', () => {
    let r = run(demoRoute(), [inspection()]);
    r = run(r, [...loadAll(r), { type: 'loadout.completed' }, { type: 'route.started' }]);
    r = run(r, [{ type: 'pickup.confirmed', stopId: 's1', pickup: { handedOverBy: 'Titi', missingReason: 'closed' } }]);
    r = run(r, [{ type: 'delivery.problem', stopId: 's3', problem: { type: 'CUSTOMER_UNAVAILABLE', resolution: 'REATTEMPT', note: '' } }]);
    expect(r.stops.find((s) => s.id === 's3')?.status).toBe('RETRY');
    r = run(r, [{ type: 'delivery.completed', stopId: 's4', delivery: { handoverMethod: 'SAFE_PLACE', safePlace: 'Front door', photo } }, { type: 'delivery.problem', stopId: 's5', problem: { type: 'REFUSED', resolution: 'RETURN_TO_FC', note: 'Refused' } }]);
    expect(canEndShift(r, '48300')).toBe('1 stop(s) not finished.');
    r = run(r, [{ type: 'delivery.problem', stopId: 's3', problem: { type: 'CUSTOMER_UNAVAILABLE', resolution: 'RETURN_TO_FC', note: '' } }]);
    expect(canEndShift(r, '48300')).toBe('Scan returned packages back into the FC.');
    r = run(r, returnsExpected(r).map((p) => ({ type: 'return.scanned' as const, scan: { label: p.label, method: 'CAMERA' as const } })));
    expect(canEndShift(r, '48222')).toBe('End odometer must be more than 48222.');
    expect(canEndShift(r, '48260')).toBeNull();
  });
});

describe('driver access', () => {
  it('distinguishes ok, no access and revoked', () => {
    expect(decideAccess(200, { driverId: 'd1', permissions: ['delivery:operate'], routeIds: ['RT-1'] }, 'Tunde').kind).toBe('ok');
    const denied = decideAccess(403, { code: 'fulfilment.no-driver-access', grantHint: 'Ask ops' }, 'Kofi Asante');
    expect(denied).toEqual({ kind: 'denied', message: 'Kofi Asante is signed in, but doesn’t have the “Use the driver app” permission.', grantHint: 'Ask ops' });
    expect(decideAccess(403, { code: 'fulfilment.driver-access-revoked', signOut: true, revokedAt: at }, 'Tunde').kind).toBe('revoked');
    expect(decideAccess(500, { title: 'Oops' }, 'x').kind).toBe('unknown');
  });
});
