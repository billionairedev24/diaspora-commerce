/* The mock BFF's rail fill: the same priority order as the gateway (rail-fill.ts), with two demo placements that exist only
   here (mock data, never a seed or migration): a paid tile and a staff editorial tile. */
import { pickRailFill, RAIL_FILL_TARGET, type RailFillCandidate } from '../rail-fill';
import type { ProductSummary } from '../schemas/catalog';
import type { HomeBlock, RailFill } from '../schemas/home';
import { eligibleForHome, mockProductImageUrl } from './service';

const FILLABLE = new Set(['rail', 'deals', 'community-spotlight', 'seller-spotlight']);
const MAX_FILLED = 4;

/** Demo placements of the mock BFF. `MOCK_RAIL_FILL=off` (or the at_mock_fill cookie, see handlers) serves none. */
export function mockRailFillPlacements(pool: readonly ProductSummary[]): RailFillCandidate[] {
  const photo = (n: number) => (pool[n] ? mockProductImageUrl(pool[n].atin) : null);
  const paid: RailFill = { kind: 'placement', id: 'plc_fill_paid', sponsored: true, advertiser: 'Kingston Kitchen', title: 'Jerk season is every season', body: 'Blue Mountain jerk paste, wings and sides in one order.', cta: 'Shop Kingston Kitchen', href: '/s?q=jerk', imageUrl: photo(2), maxPerSession: 5 };
  const editorial: RailFill = { kind: 'placement', id: 'plc_fill_editorial', sponsored: false, title: 'Back to school lunchboxes', body: 'Snacks and staples kids actually finish.', cta: 'See the picks', href: '/s?q=snacks', imageUrl: photo(5) };
  return [{ tile: paid, priority: 10 }, { tile: editorial, priority: 5 }];
}

const discountOf = (p: ProductSummary) => (p.listPriceMinor && p.listPriceMinor > p.priceMinor ? Math.floor(((p.listPriceMinor - p.priceMinor) * 100) / p.listPriceMinor) : 0);

const mode = <T>(xs: readonly T[]): T | undefined => {
  const n = new Map<T, number>();
  let best: T | undefined;
  for (const x of xs) {
    n.set(x, (n.get(x) ?? 0) + 1);
    if (best === undefined || (n.get(x) ?? 0) > (n.get(best) ?? 0)) best = x;
  }
  return best;
};

/** Fills the short organic rails of a composed page in place. `used` is everything the page shows (it is extended). */
export function applyMockRailFills(blocks: HomeBlock[], pool: readonly ProductSummary[], opts: { placements?: readonly RailFillCandidate[]; relax?: boolean; used?: Iterable<string> } = {}): void {
  const used = new Set<string>(opts.used ?? []);
  for (const b of blocks) for (const p of [...(b.rail?.items ?? []), ...(b.products ?? [])]) used.add(p.atin);
  const placements = opts.placements ?? mockRailFillPlacements(pool);
  const eligible = pool.filter(eligibleForHome);
  let filled = 0;
  for (const b of blocks) {
    if (!b.rail || b.sponsored || !FILLABLE.has(b.type) || b.rail.items.length >= RAIL_FILL_TARGET || filled >= MAX_FILLED) continue;
    const department = mode(b.rail.items.map((p) => p.department).filter(Boolean));
    const res = pickRailFill({
      items: b.rail.items,
      department,
      used,
      // Only a generic rail is topped up with its department's products; a deals rail only with real deals; spotlights
      // (a seller, a community) and personal rails never, their title would be wrong.
      relaxed: opts.relax === false || !(b.type === 'rail' || b.type === 'deals') ? [] : eligible.filter((p) => (!department || p.department === department) && (b.type !== 'deals' || discountOf(p) >= 10)),
      placements
    });
    filled++;
    b.rail.items = res.items;
    if (res.fill) b.rail = { ...b.rail, fill: res.fill };
    for (const p of res.items) used.add(p.atin);
  }
}
