/* Mock of delivery-runs' pick lists for a seller (gateway-bff-seller.yaml, GET /v1/seller/run-pickups; S264). */
import { type RunOrderBag, type SellerRunPickup } from '../../schemas/seller';

export const MOCK_RUN_LUBBOCK = '5f0a8c1e-6b2d-4d7a-9a3e-1c2b3d4e5f60';
export const MOCK_RUN_AMARILLO = '7a1b9d2f-8c3e-4e8b-8b4f-2d3c4e5f6071';

const bag = (orderNumber: string, collected: boolean, lines: [string, string, number][]): RunOrderBag => ({
  orderNumber, collected, units: lines.reduce((n, l) => n + l[2], 0), lines: lines.map(([atin, name, units]) => ({ atin, name, units }))
});

/** Units per product across the bags, most units first (what delivery-runs sends as the pick list). */
export function pickItems(bags: RunOrderBag[]): SellerRunPickup['items'] {
  const byAtin = new Map<string, { atin: string; name: string; units: number }>();
  bags.flatMap((b) => b.lines).forEach((l) => byAtin.set(l.atin, { ...l, units: (byAtin.get(l.atin)?.units ?? 0) + l.units }));
  return [...byAtin.values()].sort((a, b) => b.units - a.units || a.name.localeCompare(b.name));
}

function run(id: string, town: string, status: SellerRunPickup['runStatus'], dropMode: SellerRunPickup['dropMode'], pickupAt: Date, windowStart: Date, bags: RunOrderBag[]): SellerRunPickup {
  return {
    runId: id, town, state: 'TX', timeZone: 'America/Chicago', runStatus: status, windowStart: windowStart.toISOString(),
    windowEnd: new Date(windowStart.getTime() + 4 * 3_600_000).toISOString(), pickupAt: pickupAt.toISOString(), dropMode,
    orders: bags.length, units: bags.reduce((n, b) => n + b.units, 0), collectedOrders: bags.filter((b) => b.collected).length, items: pickItems(bags), bags
  };
}

export function seedRunPickups(now: Date): SellerRunPickup[] {
  const at = (days: number, hour: number) => {
    const t = new Date(now.getTime() + days * 86_400_000);
    t.setUTCHours(hour, 0, 0, 0);
    return t;
  };
  return [
    run(MOCK_RUN_LUBBOCK, 'Lubbock', 'COLLECTING', 'DOORSTEP', at(1, 16), at(2, 15), [
      bag('LBK-1001', true, [['ATGARI0001', 'Gari 1kg', 2], ['ATPALM0002', 'Palm oil 1L', 1]]),
      bag('LBK-1002', false, [['ATGARI0001', 'Gari 1kg', 1]]),
      bag('LBK-1004', false, [['ATPALM0002', 'Palm oil 1L', 2], ['ATSPICE003', 'Suya spice 200g', 3]])
    ]),
    run(MOCK_RUN_AMARILLO, 'Amarillo', 'CONFIRMED', 'PICKUP_POINT', at(4, 16), at(5, 15), [bag('AMA-2001', false, [['ATSPICE003', 'Suya spice 200g', 2]])])
  ];
}
