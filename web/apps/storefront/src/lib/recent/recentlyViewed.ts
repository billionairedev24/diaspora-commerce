/** Recently viewed products live in this browser only (nothing is stored server side): the ATINs, most recent first. */
const KEY = 'at.recentlyViewed';
export const RECENTLY_VIEWED_MAX = 12;

/** The remembered ATINs (empty when storage is unavailable or holds something unexpected). */
export function readViewed(): string[] {
  try {
    const raw: unknown = JSON.parse(window.localStorage.getItem(KEY) ?? '[]');
    return Array.isArray(raw) ? raw.filter((x): x is string => typeof x === 'string').slice(0, RECENTLY_VIEWED_MAX) : [];
  } catch {
    return [];
  }
}

/** Puts an ATIN first (moving it up when already remembered); a blocked storage is ignored. */
export function rememberViewed(atin: string): void {
  try {
    const next = [atin, ...readViewed().filter((a) => a !== atin)].slice(0, RECENTLY_VIEWED_MAX);
    window.localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    /* private window or blocked storage: the rail simply stays empty */
  }
}
