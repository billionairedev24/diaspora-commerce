/*
 * Rail fill: what takes the empty columns of a short product rail. The gateway is the real implementation
 * (go/services/gateway-bff/internal/domain/rail_fill.go); this is the same rule in TypeScript for the mock BFF and the
 * storefront tests, so both are checked against the same priority order:
 *   1. more relevant eligible products (same rail, relaxed), never repeating a product,
 *   2. a Sponsored tile: a paid placement, then the sponsored-products auction (always names its advertiser),
 *   3. a staff editorial placement,
 *   4. nothing: the rail collapses to its own width (no placeholder, no empty box).
 */
import type { ProductSummary } from './schemas/catalog';
import type { RailFill } from './schemas/home';

/** Cards a rail holds before it counts as full on a wide screen. */
export const RAIL_FILL_TARGET = 7;

export interface RailFillCandidate {
  tile: RailFill;
  priority?: number;
  /** Limits the tile to rails of these departments (case-insensitive); empty = any rail. */
  departments?: readonly string[];
}

export interface RailFillInput {
  items: readonly ProductSummary[];
  target?: number;
  department?: string;
  /** More products for the rail in preference order. Must already be eligible (in stock, real photo). */
  relaxed?: readonly ProductSummary[];
  /** Products the rest of the page shows. */
  used?: ReadonlySet<string>;
  /** Resolved lazily: the sponsored-products auction tile for this context. */
  auction?: () => RailFill | null;
  placements?: readonly RailFillCandidate[];
  /** Picks among tiles of equal priority (a visit seed); 0 keeps the first. */
  rotate?: number;
}

export type RailFillTier = 'none' | 'products' | 'sponsored' | 'placement' | 'collapse';

export interface RailFillResult {
  items: ProductSummary[];
  fill: RailFill | null;
  tier: RailFillTier;
}

const blank = (s: string | undefined) => !s || s.trim() === '';
const sameDept = (list: readonly string[], d: string | undefined) => list.some((x) => x.trim().toLowerCase() === (d ?? '').trim().toLowerCase());

function dropTaken(products: readonly ProductSummary[] | undefined, have: ReadonlySet<string>): ProductSummary[] {
  return (products ?? []).filter((p) => !have.has(p.atin)).slice(0, 3);
}

function pickPlacement(input: RailFillInput, have: ReadonlySet<string>, paid: boolean): RailFill | null {
  const c = (input.placements ?? [])
    .filter((x) => !blank(x.tile.advertiser) === paid && !blank(x.tile.title) && !blank(x.tile.href))
    .filter((x) => !x.departments?.length || sameDept(x.departments, input.department))
    .sort((a, b) => (b.priority ?? 0) - (a.priority ?? 0) || (a.tile.id ?? '').localeCompare(b.tile.id ?? ''));
  const first = c[0];
  if (!first) return null;
  const top = c.filter((x) => (x.priority ?? 0) === (first.priority ?? 0));
  const chosen = top[(input.rotate ?? 0) % top.length] ?? first;
  const products = dropTaken(chosen.tile.products, have);
  return { ...chosen.tile, kind: 'placement', sponsored: paid, products: products.length ? products : undefined };
}

export function pickRailFill(input: RailFillInput): RailFillResult {
  const target = input.target && input.target > 0 ? input.target : RAIL_FILL_TARGET;
  const items = [...input.items];
  if (items.length >= target) return { items, fill: null, tier: 'none' };
  const have = new Set(items.map((p) => p.atin));
  for (const p of input.relaxed ?? []) {
    if (items.length >= target) break;
    if (!p.atin || have.has(p.atin) || input.used?.has(p.atin)) continue;
    have.add(p.atin);
    items.push(p);
  }
  if (items.length >= target) return { items, fill: null, tier: 'products' };

  const paid = pickPlacement(input, have, true);
  if (paid) return { items, fill: paid, tier: 'sponsored' };
  const auction = input.auction?.();
  if (auction && auction.sponsored && !blank(auction.advertiser)) {
    const products = dropTaken(auction.products, have);
    if (products.length) return { items, fill: { ...auction, products }, tier: 'sponsored' };
  }
  const editorial = pickPlacement(input, have, false);
  if (editorial) return { items, fill: editorial, tier: 'placement' };
  return { items, fill: null, tier: 'collapse' };
}

/** The label a tile must carry: "Sponsored" for paid tiles, nothing for staff editorial tiles. */
export function railFillLabelKey(fill: Pick<RailFill, 'sponsored'>): 'home.sponsoredTag' | null {
  return fill.sponsored ? 'home.sponsoredTag' : null;
}
