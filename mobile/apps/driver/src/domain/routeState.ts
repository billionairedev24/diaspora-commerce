/* Optimistic local route state: every queued event is applied to the cached route immediately so the driver can work
   offline. Server truth replaces it after a sync (and always after a REJECTED / CONFLICT result). Pure; tested. */
import { criticalFailures } from './inspection';
import { type DriverEventBody, type PackageStatus, type Route, type RoutePackage, type RouteStop, type StopStatus } from './types';

const setStop = (r: Route, id: string, patch: Partial<RouteStop>): Route => ({ ...r, stops: r.stops.map((s) => (s.id === id ? { ...s, ...patch } : s)) });
const setPkgs = (r: Route, pred: (p: RoutePackage) => boolean, patch: (p: RoutePackage) => Partial<RoutePackage>): Route => ({ ...r, packages: r.packages.map((p) => (pred(p) ? { ...p, ...patch(p) } : p)) });

export function applyLocal(route: Route, e: DriverEventBody, at: string): Route {
  switch (e.type) {
    case 'inspection.submitted': {
      const critical = criticalFailures({ checks: e.inspection.checks, odometer: '', fuel: '', coldBox: '', photos: {}, defectNote: '', certified: true, signatureLocalUri: null }).length > 0;
      return { ...route, inspection: critical ? 'BLOCKED' : 'PASSED', blocked: critical, startOdometer: e.inspection.odometerMiles, lastOdometer: e.inspection.odometerMiles };
    }
    case 'loadout.scanned':
      return setPkgs(route, (p) => p.label === e.scan.label, (p) => ({ loadScanned: true, status: 'LOADED', manualEntry: p.manualEntry || e.scan.method === 'MANUAL' }));
    case 'loadout.completed':
      return { ...route, loadOutCompleted: true };
    case 'route.started':
      return { ...route, status: 'IN_PROGRESS' };
    case 'stop.arrived':
      return setStop(route, e.stopId, { status: 'ARRIVED' });
    case 'stop.scanned': {
      const stop = route.stops.find((s) => s.id === e.stopId);
      const pickup = stop?.kind === 'PICKUP';
      return setPkgs(route, (p) => p.label === e.scan.label, (p) => ({ ...(pickup ? { pickupScanned: true } : { dropScanned: true }), manualEntry: p.manualEntry || e.scan.method === 'MANUAL' }));
    }
    case 'pickup.confirmed': {
      const r = setStop(route, e.stopId, { status: 'DONE', closedNote: `Handed over by ${e.pickup.handedOverBy}${e.pickup.missingReason ? ` · missing: ${e.pickup.missingReason}` : ''}` });
      return setPkgs(r, (p) => p.pickupStopId === e.stopId, (p) => ({ status: p.pickupScanned ? 'PICKED_UP' : 'NOT_PICKED_UP' }));
    }
    case 'pickup.problem': {
      const res = e.pickupProblem.resolution;
      if (res === 'WAIT') return setStop(route, e.stopId, { closedNote: 'Waiting for the seller' });
      const status: StopStatus = res === 'SKIP' ? 'SKIPPED' : 'DONE';
      const r = setStop(route, e.stopId, { status, closedNote: e.pickupProblem.note });
      return setPkgs(r, (p) => p.pickupStopId === e.stopId, (p) => ({ status: res === 'PARTIAL' && p.pickupScanned ? 'PICKED_UP' : 'NOT_PICKED_UP' }));
    }
    case 'customer.contacted': {
      const stop = route.stops.find((s) => s.id === e.stopId);
      return stop ? setStop(route, e.stopId, { contacts: [...stop.contacts, { kind: e.contact, at }] }) : route;
    }
    case 'delivery.completed': {
      const r = setStop(route, e.stopId, { status: 'DONE', closedNote: e.delivery.handoverMethod });
      return setPkgs(r, (p) => p.dropoffStopId === e.stopId, () => ({ status: 'DELIVERED' }));
    }
    case 'delivery.problem': {
      const stop = route.stops.find((s) => s.id === e.stopId);
      const res = e.problem.resolution;
      const status: StopStatus = res === 'REATTEMPT' ? 'RETRY' : res === 'ACCEPTED_WITH_DAMAGE' ? 'DONE' : 'FAILED';
      const pkg: PackageStatus | null = res === 'REATTEMPT' ? null : res === 'ACCEPTED_WITH_DAMAGE' ? 'DELIVERED' : res === 'PICKUP_POINT' ? 'TO_PICKUP_POINT' : res === 'REPORT_MISSING' ? 'MISSING' : 'FAILED';
      const r = setStop(route, e.stopId, { status, closedNote: e.problem.note, reattempts: (stop?.reattempts ?? 0) + (res === 'REATTEMPT' ? 1 : 0), contacts: res === 'REATTEMPT' ? [] : stop?.contacts ?? [] });
      return pkg ? setPkgs(r, (p) => p.dropoffStopId === e.stopId, () => ({ status: pkg })) : r;
    }
    case 'return.scanned':
      return setPkgs(route, (p) => p.label === e.scan.label, () => ({ returnScanned: true, status: 'RETURNED' }));
    case 'shift.ended':
      return { ...route, ended: true, status: 'COMPLETED' };
    case 'session.signed_in':
    case 'sos.raised':
      return route;
  }
}
