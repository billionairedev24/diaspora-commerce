/* Mock of the seller pickups of AfroTransact Delivery from the seller's store (gateway-bff-staff.yaml, tag Dispatch; S255):
   fulfilment's dispatch pool (SELLER origin), the nearest-driver suggestions and the assignment log. */
import { type DriverSuggestion, type PickupAssignment, type PickupRow, type ShipFromLocation } from '../../schemas/staff';

export interface MockDispatch {
  pickups: PickupRow[];
  suggestions: Record<string, DriverSuggestion[]>;
  assignments: PickupAssignment[];
  locations: ShipFromLocation[];
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
    locations: [
      { id: 'loc-mamatee-1', orgId: 'org-mamatee', label: 'Bissonnet store', line1: '9800 Bissonnet St', line2: '', city: 'Houston', state: 'TX', postalCode: '77036', country: 'US',
        position: { lat: 29.6713, lng: -95.5559, precision: 'ADDRESS' }, pickupEnabled: true,
        pickupHours: [{ day: 'MONDAY', opens: '09:00', closes: '17:00' }, { day: 'SATURDAY', opens: '10:00', closes: '14:00' }], zoneName: 'Houston Metro' }
    ]
  };
}
