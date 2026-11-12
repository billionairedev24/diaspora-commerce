/** Recently viewed products live in this browser only (nothing is stored server side, signed in or not): the product
    ATINs, most recent first, one entry per product. */
const KEY = 'at.recentlyViewed';
/** How many products the browser remembers. */
export const RECENTLY_VIEWED_STORED = 20;
/** How many cards the rail shows at most. */
export const RECENTLY_VIEWED_SHOWN = 12;
/** The rail stays hidden below this many eligible products (the staff-console default for the module). */
export const RECENTLY_VIEWED_MIN = 1;

/** Cleans a remembered list: strings only, trimmed and upper-cased, each ATIN once (its most recent position wins), capped. Nothing else is dropped. */
export function normalizeViewed(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  for (const x of raw) {
    if (typeof x !== 'string') continue;
    const atin = x.trim().toUpperCase();
    if (!atin || seen.has(atin)) continue;
    seen.add(atin);
    out.push(atin);
  }
  return out.slice(0, RECENTLY_VIEWED_STORED);
}

/** The remembered ATINs, duplicates collapsed (empty when storage is unavailable or holds something unexpected). Reading never rewrites storage. */
export function readViewed(): string[] {
  try {
    return normalizeViewed(JSON.parse(window.localStorage.getItem(KEY) ?? '[]'));
  } catch {
    return [];
  }
}

/** Puts a product ATIN first (moving it up when already remembered, never adding a second entry); a blocked storage is ignored. */
export function rememberViewed(atin: string): void {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(normalizeViewed([atin, ...readViewed()])));
  } catch {
    /* private window or blocked storage: the rail simply stays empty */
  }
}

/** The cards to show: each product once (first, i.e. most recent, wins), at most RECENTLY_VIEWED_SHOWN, or none when fewer than the minimum remain. */
export function railItems<T extends { atin: string }>(items: readonly T[]): T[] {
  const seen = new Set<string>();
  const out = items.filter((p) => !seen.has(p.atin) && !!seen.add(p.atin)).slice(0, RECENTLY_VIEWED_SHOWN);
  return out.length >= RECENTLY_VIEWED_MIN ? out : [];
}
