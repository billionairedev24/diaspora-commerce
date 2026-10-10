/* Dev fixture: the design's afternoon route (design/AfroTransact Driver.dc.html). */
import { type Route } from '@/domain/types';

export const DEMO_DRIVERS = [
  { email: 'tunde@afrotransact.com', name: 'Tunde Bakare', sub: 'usr_tunde', perms: ['delivery:operate'] },
  { email: 'daniel@afrotransact.com', name: 'Daniel Mensah', sub: 'usr_daniel', perms: ['delivery:operate', 'delivery:dispatch'] },
  { email: 'kofi@afrotransact.com', name: 'Kofi Asante', sub: 'usr_kofi', perms: ['fulfilment:operate'] }
];

export function demoRoute(): Route {
  const stop = (id: string, sequence: number, kind: 'PICKUP' | 'DROPOFF', name: string, addressLine: string, postalCode: string, lat: number, lon: number, window: string, note: string, extra: Partial<Route['stops'][number]> = {}) => ({
    id, sequence, kind, name, addressLine, city: 'Houston, TX', postalCode, lat, lon, window, note, handoverRequired: false, safePlaceAllowed: false, status: 'PENDING' as const, reattempts: 0, contacts: [], ...extra
  });
  return {
    id: 'RT-HOU-PM', name: 'Houston SW · PM', zone: 'Houston SW', window: '1:00–5:00 pm', depotName: 'Houston FC', vehicleId: 'VEH-TX7P10', vehicleLabel: 'Ford Transit · TX-7P10',
    status: 'ASSIGNED', inspection: 'NONE', blocked: false, perishable: true, lastOdometer: 48210, startOdometer: null, loadOutCompleted: false, ended: false,
    stops: [
      stop('s1', 1, 'PICKUP', "Mama Tee's Pantry", '7100 Bellaire Blvd, Unit 14', '77074', 29.7056, -95.5345, '1:35–1:50 pm', 'Counter pickup · ask for Titi'),
      stop('s3', 2, 'DROPOFF', 'Adaeze N.', '9800 Westheimer Rd', '77042', 29.7376, -95.5134, '2:10–2:40 pm', 'Front desk if away', { handoverRequired: true, orderNumber: '112-4401731', brief: { preference: 'hand', instructions: 'Ring 2B · front desk can sign if I’m away', contact: 'text', phoneMasked: '(713) •••-0142' } }),
      stop('s4', 3, 'DROPOFF', 'Kunle B.', '2400 Fountain View Dr, Apt 318', '77057', 29.7329, -95.4834, '2:45–3:15 pm', 'Gate code after arrival', { safePlaceAllowed: true, orderNumber: '112-4401802', brief: { preference: 'door', place: 'Front door', instructions: 'Gate code 4471 · Apt 318 is upstairs on the left', contact: 'text', phoneMasked: '(832) •••-2210' } }),
      stop('s5', 4, 'DROPOFF', 'Grace W.', '6200 Hillcroft Ave', '77081', 29.7144, -95.4988, '3:20–3:50 pm', 'Perishable — hand to customer', { handoverRequired: true, orderNumber: '112-4401907', brief: { preference: 'hand', instructions: 'Call when outside — no doorbell', contact: 'call', phoneMasked: '(281) •••-7781' } })
    ],
    packages: [
      { id: 'p1', label: 'ATS7K2Q9H4M1', orderNumber: '112-4401731', description: 'AfroTransact Brand · jollof seasoning ×2', origin: 'FC', dropoffStopId: 's3', perishable: false, status: 'EXPECTED', loadScanned: false, pickupScanned: false, dropScanned: false, returnScanned: false, manualEntry: false },
      { id: 'p2', label: 'ATS3NP8D2LQ6', orderNumber: '112-4401802', description: 'Aged basmati 10 lb · heavy', origin: 'FC', dropoffStopId: 's4', perishable: false, status: 'EXPECTED', loadScanned: false, pickupScanned: false, dropScanned: false, returnScanned: false, manualEntry: false },
      { id: 'p3', label: 'ATS9WX4T1BC8', orderNumber: '112-4401855', description: 'African black soap ×3', origin: 'FC', dropoffStopId: 's5', perishable: false, status: 'EXPECTED', loadScanned: false, pickupScanned: false, dropScanned: false, returnScanned: false, manualEntry: false },
      { id: 'p4', label: 'ATS5RM2J7VZ3', orderNumber: '112-4401731', description: "Ofada rice ×2 (Mama Tee's)", origin: 'SELLER', pickupStopId: 's1', dropoffStopId: 's3', perishable: false, status: 'EXPECTED', loadScanned: false, pickupScanned: false, dropScanned: false, returnScanned: false, manualEntry: false },
      { id: 'p5', label: 'ATS8HD6K3PF2', orderNumber: '112-4401907', description: 'Party jollof tray · keep upright', origin: 'SELLER', pickupStopId: 's1', dropoffStopId: 's5', perishable: true, status: 'EXPECTED', loadScanned: false, pickupScanned: false, dropScanned: false, returnScanned: false, manualEntry: false }
    ]
  };
}
