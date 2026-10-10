/* Contract route (contracts/openapi/fulfilment.yaml, loosely typed) → the app's domain Route. */
import { type PackageStatus, type Route, type RouteStop, type StopStatus } from '@/domain/types';

import { type components } from './generated/fulfilment';

type ApiRoute = components['schemas']['Route'] & { depotName?: string; vehicleLabel?: string; lastOdometer?: number };
type ApiStop = components['schemas']['RouteStop'] & { brief?: RouteStop['brief'] };
type ApiPackage = components['schemas']['RoutePackage'] & { description?: string; loadScanned?: boolean; returnScanned?: boolean };

const STOP: StopStatus[] = ['PENDING', 'ARRIVED', 'DONE', 'FAILED', 'RETRY', 'SKIPPED', 'REASSIGNED', 'CANCELLED'];
const PKG: PackageStatus[] = ['EXPECTED', 'LOADED', 'PICKED_UP', 'NOT_PICKED_UP', 'DELIVERED', 'FAILED', 'TO_PICKUP_POINT', 'MISSING', 'RETURNED', 'REASSIGNED', 'CANCELLED'];
const oneOf = <T extends string>(v: string | undefined, all: readonly T[], d: T): T => (all as readonly string[]).includes((v ?? '').toUpperCase()) ? ((v ?? '').toUpperCase() as T) : d;

export function mapRoute(r: ApiRoute): Route {
  const stops = (r.stops ?? []) as ApiStop[];
  const pkgs = (r.packages ?? []) as ApiPackage[];
  return {
    id: r.id ?? '',
    name: r.name ?? r.id ?? 'Route',
    zone: r.zone ?? '',
    window: r.window ?? '',
    depotName: r.depotName ?? r.depotNodeId ?? 'FC',
    vehicleId: r.vehicleId ?? '',
    vehicleLabel: r.vehicleLabel ?? r.vehicleId ?? '',
    status: oneOf(r.status, ['PLANNED', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'] as const, 'ASSIGNED'),
    inspection: oneOf(r.inspection, ['NONE', 'PASSED', 'BLOCKED'] as const, 'NONE'),
    blocked: !!r.blocked,
    perishable: !!r.perishable,
    lastOdometer: r.lastOdometer ?? r.startOdometer ?? 0,
    startOdometer: r.startOdometer ?? null,
    loadOutCompleted: !!r.loadOutCompletedAt,
    ended: !!r.endedAt,
    stops: stops
      .slice()
      .sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0))
      .map((s) => ({
        id: s.id ?? '',
        sequence: s.sequence ?? 0,
        kind: (s.kind ?? '').toUpperCase() === 'PICKUP' ? 'PICKUP' : 'DROPOFF',
        name: s.name ?? '',
        addressLine: s.addressLine ?? '',
        city: s.city ?? '',
        postalCode: s.postalCode ?? '',
        lat: s.lat ?? null,
        lon: s.lon ?? null,
        window: s.window ?? '',
        handoverRequired: !!s.handoverRequired,
        safePlaceAllowed: !!s.safePlaceAllowed,
        status: oneOf(s.status, STOP, 'PENDING'),
        reattempts: s.reattempts ?? 0,
        contacts: (s.contacts ?? []).filter((c) => c.kind && c.at).map((c) => ({ kind: c.kind as 'CALL' | 'TEXT' | 'WAIT_STARTED', at: c.at as string })),
        ...(s.orderNumber ? { orderNumber: s.orderNumber } : {}),
        ...(s.note ? { note: s.note } : {}),
        ...(s.brief ? { brief: s.brief } : {})
      })),
    packages: pkgs.map((p) => {
      const status = oneOf(p.status, PKG, 'EXPECTED');
      return {
        id: p.id ?? '',
        label: p.label ?? '',
        orderNumber: p.orderNumber ?? '',
        description: p.description ?? p.orderNumber ?? '',
        origin: (p.origin ?? '').toUpperCase() === 'SELLER' ? 'SELLER' : 'FC',
        ...(p.pickupStopId ? { pickupStopId: p.pickupStopId } : {}),
        ...(p.dropoffStopId ? { dropoffStopId: p.dropoffStopId } : {}),
        perishable: !!p.perishable,
        status,
        loadScanned: p.loadScanned ?? ((p.origin ?? '').toUpperCase() !== 'SELLER' && status !== 'EXPECTED'),
        pickupScanned: !!p.pickupScanned,
        dropScanned: !!p.dropScanned,
        returnScanned: p.returnScanned ?? status === 'RETURNED',
        manualEntry: !!p.manualEntry
      };
    })
  };
}
