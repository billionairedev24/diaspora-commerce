/* Shift flow: inspection → load-out → route → end of shift, each step unlocking the next (pure; unit-tested). */
import { type Route, type RoutePackage, type RouteStop } from './types';

export const fcPackages = (r: Route) => r.packages.filter((p) => p.origin === 'FC');
export const loadOutDone = (r: Route) => fcPackages(r).every((p) => p.loadScanned);
export const stopPackages = (r: Route, s: RouteStop) => r.packages.filter((p) => (s.kind === 'PICKUP' ? p.pickupStopId === s.id : p.dropoffStopId === s.id));
export const isStopOpen = (s: RouteStop) => s.status === 'PENDING' || s.status === 'ARRIVED' || s.status === 'RETRY';
export const doneStops = (r: Route) => r.stops.filter((s) => !isStopOpen(s)).length;
export const perishableAt = (r: Route, s: RouteStop) => stopPackages(r, s).some((p) => p.perishable);

/** Packages to scan back into the FC at the end of the shift. */
export function returnsExpected(r: Route): RoutePackage[] {
  return r.packages.filter((p) => p.status === 'FAILED' || p.status === 'TO_PICKUP_POINT' || (p.origin === 'SELLER' && p.status === 'NOT_PICKED_UP' && p.pickupScanned));
}

export function onBoard(r: Route): number {
  return r.packages.filter((p) => (p.loadScanned || p.status === 'PICKED_UP') && !['DELIVERED', 'RETURNED', 'MISSING'].includes(p.status)).length;
}

export type Phase = 'inspect' | 'loadout' | 'route' | 'end';

export interface PhaseView {
  key: Phase;
  label: string;
  note: string;
  done: boolean;
  locked: boolean;
}

export function phases(r: Route): PhaseView[] {
  const inspected = r.inspection === 'PASSED';
  const loaded = loadOutDone(r);
  const started = r.status === 'IN_PROGRESS' || r.status === 'COMPLETED';
  const done = doneStops(r);
  return [
    { key: 'inspect', label: 'Vehicle inspection', note: r.inspection === 'BLOCKED' ? 'Defect reported · route blocked' : inspected ? `Passed · odometer ${r.startOdometer ?? ''}` : 'Required before load-out', done: inspected, locked: false },
    { key: 'loadout', label: `Load-out at ${r.depotName}`, note: loaded ? `All ${fcPackages(r).length} FC packages scanned` : `${fcPackages(r).filter((p) => p.loadScanned).length} of ${fcPackages(r).length} scanned`, done: loaded, locked: !inspected || r.blocked },
    { key: 'route', label: 'Route', note: `${done} of ${r.stops.length} stops`, done: done === r.stops.length && r.stops.length > 0, locked: !started },
    { key: 'end', label: 'End shift', note: r.ended ? 'Completed' : 'Return scan + odometer', done: r.ended, locked: !started }
  ];
}

export type PrimaryAction = { label: string; target: Phase | 'start-route' | 'none'; disabled: boolean };

export function primaryAction(r: Route): PrimaryAction {
  if (r.blocked) return { label: 'Route blocked · wait for dispatch', target: 'none', disabled: true };
  if (r.inspection !== 'PASSED') return { label: 'Start inspection', target: 'inspect', disabled: false };
  if (!loadOutDone(r)) return { label: 'Scan load-out', target: 'loadout', disabled: false };
  if (r.ended) return { label: 'Shift complete', target: 'none', disabled: true };
  if (r.status !== 'IN_PROGRESS') return { label: 'Start route', target: 'start-route', disabled: false };
  return doneStops(r) === r.stops.length ? { label: 'End shift', target: 'end', disabled: false } : { label: 'Continue route', target: 'route', disabled: false };
}

export function canStartRoute(r: Route): string | null {
  if (r.blocked) return 'This route is blocked by a vehicle defect.';
  if (r.inspection !== 'PASSED') return 'Pass the vehicle inspection first.';
  if (!loadOutDone(r)) return 'Finish the load-out scan first.';
  return null;
}

/** "I've arrived" rules (design copy). */
export function canArrive(r: Route, s: RouteStop): string | null {
  if (r.status !== 'IN_PROGRESS') return 'Start the route first.';
  if (s.kind === 'DROPOFF') {
    const waiting = stopPackages(r, s).some((p) => p.origin === 'SELLER' && p.status !== 'PICKED_UP');
    if (waiting) return 'Pick up from the seller first — a package for this stop is still at their shop.';
  }
  return null;
}

export function canConfirmPickup(r: Route, s: RouteStop, handedOverBy: string, missingReason: string): string | null {
  const pk = stopPackages(r, s);
  const scanned = pk.filter((p) => p.pickupScanned).length;
  if (scanned < pk.length && missingReason.trim().length < 3) return `Scan every package first (${scanned}/${pk.length}) or give a reason.`;
  if (handedOverBy.trim().length < 2) return 'Enter who handed the packages over.';
  return null;
}

export function allDropScanned(r: Route, s: RouteStop): boolean {
  return stopPackages(r, s).every((p) => p.dropScanned);
}

export function canEndShift(r: Route, odometer: string): string | null {
  const open = r.stops.filter(isStopOpen).length;
  if (open) return `${open} stop(s) not finished.`;
  if (!returnsExpected(r).every((p) => p.returnScanned)) return 'Scan returned packages back into the FC.';
  const start = r.startOdometer ?? r.lastOdometer;
  if (!(Number(odometer) > start)) return `End odometer must be more than ${start}.`;
  return null;
}

/** On shift: the route is started (or ready to start after a passed inspection) and not ended. Location pings
 *  (S255, nearest driver for seller pickups) are sent only then. */
export function onShift(r: Route | null): boolean {
  if (!r || r.ended || r.blocked) return false;
  return r.status === 'IN_PROGRESS' || (r.status === 'ASSIGNED' && r.inspection === 'PASSED');
}
