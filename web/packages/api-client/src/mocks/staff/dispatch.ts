/* Mock of the seller pickups of AfroTransact Delivery from the seller's store (gateway-bff-staff.yaml, tag Dispatch; S255):
   fulfilment's dispatch pool (SELLER origin), the nearest-driver suggestions and the assignment log. */
import {
  type DriverSuggestion,
  type PickupAssignment,
  type PickupRow,
  RUN_LIFECYCLE,
  type RunAdvanceStep,
  type RunDispatch,
  type RunDispatchRoute,
  type RunDispatchStop,
  type ShipFromLocation
} from '../../schemas/staff';

export interface MockDispatch {
  pickups: PickupRow[];
  suggestions: Record<string, DriverSuggestion[]>;
  assignments: PickupAssignment[];
  locations: ShipFromLocation[];
  /** Delivery runs of closed pre-order windows (S264). */
  runs: RunDispatch[];
}

export function seedDispatch(now: Date): MockDispatch {
  const at = (minutesAgo: number) => new Date(now.getTime() - minutesAgo * 60_000).toISOString();
  const day = (d: number) => new Date(now.getTime() + d * 86_400_000).toISOString().slice(0, 10);
  const driver = (driverId: string, routeId: string, routeName: string, straight: number, road: number | null, minutes: number | null, onRoute: number, left: number): DriverSuggestion => ({
    driverId, routeId, routeName, zone: 'Houston Metro', routeStatus: 'IN_PROGRESS', straightLineMiles: straight, roadMiles: road, durationMinutes: minutes,
    distanceMethod: road === null ? 'STRAIGHT_LINE' : 'ROAD_OSRM', positionAt: at(3), packagesOnRoute: onRoute, capacityLeft: left
  });
  const drivers = [
    driver('drv-tunde', 'R-HOU-W-14', 'HOU West · Tunde', 1.8, 2.6, 8, 14, 26),
    driver('drv-ngozi', 'R-HOU-SW-07', 'HOU Southwest · Ngozi', 3.1, 4.4, 12, 22, 18),
    driver('drv-kwame', 'R-HOU-C-03', 'HOU Central · Kwame', 4.9, 6.8, 17, 30, 10),
    driver('drv-fatou', 'R-HOU-N-11', 'HOU North · Fatou', 7.2, 9.9, 21, 9, 31),
    driver('drv-yaw', 'R-HOU-E-02', 'HOU East · Yaw', 9.6, null, null, 36, 4)
  ];
  return {
    pickups: [
      { shipmentId: 'shp-pk-1001', orderNumber: 'AT-2026-004812', sellerOrgId: 'org-mamatee', sellerName: "Mama Tee's Pantry", city: 'Houston', state: 'TX', postalCode: '77036',
        status: 'AWAITING_ROUTE', routeId: null, perishable: true, units: 3, promisedBy: day(1), createdAt: at(25) },
      { shipmentId: 'shp-pk-1002', orderNumber: 'AT-2026-004815', sellerOrgId: 'org-accra', sellerName: 'Accra Spice Co.', city: 'Sugar Land', state: 'TX', postalCode: '77478',
        status: 'AWAITING_ROUTE', routeId: null, perishable: false, units: 1, promisedBy: day(1), createdAt: at(40) },
      { shipmentId: 'shp-pk-1003', orderNumber: 'AT-2026-004820', sellerOrgId: 'org-ade', sellerName: "Ade's Kitchen", city: 'Katy', state: 'TX', postalCode: '77494',
        status: 'AWAITING_ROUTE', routeId: null, perishable: false, units: 2, promisedBy: day(2), createdAt: at(55) }
    ],
    suggestions: { 'shp-pk-1001': drivers, 'shp-pk-1002': drivers.slice(1), 'shp-pk-1003': [] },
    assignments: [
      { id: 'pa-1', shipmentId: 'shp-pk-1002', routeId: 'R-HOU-N-11', driverId: 'drv-fatou', mode: 'AUTO_NEAREST', reason: null, previousRouteId: null,
        straightMiles: 7.2, roadMiles: 9.9, distanceMethod: 'ROAD_OSRM', actor: 'system', createdAt: at(38) }
    ],
    runs: seedRuns(now),
    locations: [
      { id: 'loc-mamatee-1', orgId: 'org-mamatee', label: 'Bissonnet store', line1: '9800 Bissonnet St', line2: '', city: 'Houston', state: 'TX', postalCode: '77036', country: 'US',
        position: { lat: 29.6713, lng: -95.5559, precision: 'ADDRESS' }, pickupEnabled: true,
        pickupHours: [{ day: 'MONDAY', opens: '09:00', closes: '17:00' }, { day: 'SATURDAY', opens: '10:00', closes: '14:00' }], zoneName: 'Houston Metro' }
    ]
  };
}

export const RUN_LUBBOCK = 'run-lbk-7';
export const RUN_AMARILLO = 'run-ama-3';
export const RUN_ODESSA = 'run-ods-2';

const stop = (id: string, sequence: number, kind: RunDispatchStop['kind'], name: string, status: RunDispatchStop['status'], order: string | null, address: string | null, code: boolean, note: string | null = null): RunDispatchStop =>
  ({ id, sequence, kind, name, status, orderNumber: order, addressLine: address, handoverRequired: code, note });

function seedRuns(now: Date): RunDispatch[] {
  const day = (d: number, hour: number) => {
    const t = new Date(now.getTime() + d * 86_400_000);
    t.setUTCHours(hour, 0, 0, 0);
    return t.toISOString();
  };
  const lubbockStops: RunDispatchStop[] = [
    stop('st-l1', 1, 'PICKUP', "Ade's Kitchen", 'DONE', null, null, false),
    stop('st-l2', 2, 'PICKUP', 'Bonwire Weavers', 'PENDING', null, null, false, 'Back door, ask for Efua'),
    stop('st-l3', 3, 'DROPOFF', 'Amara Nwosu', 'PENDING', 'LBK-1001', '2401 Broadway, Lubbock, TX 79401', true),
    stop('st-l4', 4, 'DROPOFF', 'Bola Ade', 'PENDING', 'LBK-1002', '88 Quaker Ave, Lubbock, TX 79416', true)
  ];
  const lubbockRoute: RunDispatchRoute = {
    id: 'rte-lbk-1', name: 'LBK-7 route 1', status: 'ASSIGNED', driverId: 'drv-tunde', vehicleId: null, blocked: false, packages: 4, bagsCollected: 2, bagsDelivered: 0,
    pickupStops: 2, pickupsDone: 1, dropStops: 2, dropsDone: 0, dropsFailed: 0, stops: lubbockStops
  };
  return [
    {
      runId: RUN_LUBBOCK, town: 'Lubbock', state: 'TX', timeZone: 'America/Chicago', serviceDate: day(3, 12).slice(0, 10), windowStart: day(3, 15), windowEnd: day(3, 19),
      sellerPickupAt: day(2, 16), dropMode: 'DOORSTEP', pickupPoint: null, status: 'PLANNED', runStatus: 'COLLECTING', planError: null, plannedAt: day(-1, 10),
      progress: { orders: 2, bags: 4, pickupStops: 2, pickupsDone: 1, bagsCollected: 2, dropStops: 2, dropsDone: 0, dropsFailed: 0, bagsDelivered: 0, unrouted: 0 },
      orders: { orders: 2, pending: 1, collected: 1, outForDelivery: 0, failed: 0, delivered: 0, returned: 0, lastDeliveredAt: null },
      routes: [lubbockRoute], canManage: true
    },
    {
      runId: RUN_AMARILLO, town: 'Amarillo', state: 'TX', timeZone: 'America/Chicago', serviceDate: day(5, 12).slice(0, 10), windowStart: day(5, 15), windowEnd: day(5, 19),
      sellerPickupAt: day(4, 16), dropMode: 'PICKUP_POINT',
      pickupPoint: { name: 'Gateway Mall counter', line1: '2200 Gateway Dr', city: 'Amarillo', state: 'TX', postalCode: '79109', hours: '10am to 6pm', notes: 'Ask at the customer desk' },
      status: 'PENDING', runStatus: 'CONFIRMED', planError: 'No depot is configured for this market', plannedAt: null,
      progress: { orders: 6, bags: 9, pickupStops: 0, pickupsDone: 0, bagsCollected: 0, dropStops: 0, dropsDone: 0, dropsFailed: 0, bagsDelivered: 0, unrouted: 6 },
      orders: { orders: 6, pending: 6, collected: 0, outForDelivery: 0, failed: 0, delivered: 0, returned: 0, lastDeliveredAt: null },
      routes: [], canManage: true
    },
    {
      runId: RUN_ODESSA, town: 'Odessa', state: 'TX', timeZone: 'America/Chicago', serviceDate: day(-2, 12).slice(0, 10), windowStart: day(-2, 15), windowEnd: day(-2, 19),
      sellerPickupAt: day(-3, 16), dropMode: 'DOORSTEP', pickupPoint: null, status: 'PLANNED', runStatus: 'COMPLETED', planError: null, plannedAt: day(-6, 10),
      progress: { orders: 3, bags: 3, pickupStops: 1, pickupsDone: 1, bagsCollected: 3, dropStops: 3, dropsDone: 3, dropsFailed: 0, bagsDelivered: 3, unrouted: 0 },
      orders: { orders: 3, pending: 0, collected: 0, outForDelivery: 0, failed: 0, delivered: 3, returned: 0, lastDeliveredAt: day(-2, 17) },
      routes: [], canManage: true
    }
  ];
}

/** The step a run is on, or -1 when it was cancelled (cancelled runs cannot move). */
export function lifecycleIndex(run: RunDispatch): number {
  return run.runStatus === null || run.runStatus === 'CANCELLED' ? -1 : RUN_LIFECYCLE.indexOf(run.runStatus);
}

/** delivery-runs' transitions: collecting after confirmed, out for delivery after collecting, completed after out for delivery. */
export function canAdvance(run: RunDispatch, to: RunAdvanceStep): boolean {
  const at = lifecycleIndex(run);
  return at >= 0 && RUN_LIFECYCLE.indexOf(to) === at + 1;
}

/** Planning a pending run: one route with the sellers' pickups first, then a drop per order (what fulfilment does with the run's dispatch list). */
export function planRun(run: RunDispatch): RunDispatch {
  const drops = run.progress.orders;
  const stops: RunDispatchStop[] = [
    stop(`st-${run.runId}-p1`, 1, 'PICKUP', 'Accra Spice Co.', 'PENDING', null, null, false),
    ...Array.from({ length: drops }, (_, i) =>
      stop(`st-${run.runId}-d${i + 1}`, i + 2, 'DROPOFF', run.pickupPoint ? run.pickupPoint.name : `Customer ${i + 1}`, 'PENDING', `${run.town.slice(0, 3).toUpperCase()}-20${i + 1}`,
        run.pickupPoint ? `${run.pickupPoint.line1}, ${run.pickupPoint.city}, ${run.pickupPoint.state} ${run.pickupPoint.postalCode}` : null, true))
  ];
  return {
    ...run, status: 'PLANNED', planError: null, plannedAt: new Date().toISOString(), progress: { ...run.progress, pickupStops: 1, dropStops: drops, unrouted: 0 },
    routes: [{ id: `rte-${run.runId}-1`, name: `${run.town} route 1`, status: 'PLANNED', driverId: null, vehicleId: null, blocked: false, packages: run.progress.bags, bagsCollected: 0, bagsDelivered: 0,
      pickupStops: 1, pickupsDone: 0, dropStops: drops, dropsDone: 0, dropsFailed: 0, stops }]
  };
}
