/* An anonymous id for one visit, so the A/B variant of an advertorial stays the same while the visitor browses and a
   view is counted once per visit. It lives in sessionStorage only (gone when the tab closes), is never sent anywhere but
   delivery-runs' advertorial serving, and carries no account data, so it needs no consent. */

const VISITOR_KEY = 'at.visitor.v1';
const VIEWED_KEY = 'at.adv.viewed.v1';
const COUNTED_KEY = 'at.visit.counted.v1';

let memory: string | null = null;

const random = (): string => {
  const bytes = new Uint8Array(12);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
};

export function visitorId(): string {
  try {
    const stored = sessionStorage.getItem(VISITOR_KEY);
    if (stored) return stored;
    const id = random();
    sessionStorage.setItem(VISITOR_KEY, id);
    return id;
  } catch {
    memory ??= random();
    return memory;
  }
}

const viewed = new Set<string>();

/** True the first time an advertorial is shown in this visit (so VIEW is counted once). */
export function firstView(advertorialId: string): boolean {
  if (viewed.has(advertorialId)) return false;
  viewed.add(advertorialId);
  try {
    const seen: string[] = JSON.parse(sessionStorage.getItem(VIEWED_KEY) ?? '[]');
    if (seen.includes(advertorialId)) return false;
    sessionStorage.setItem(VIEWED_KEY, JSON.stringify([...seen, advertorialId].slice(-50)));
  } catch {
    // Storage is unavailable: the in-memory set still keeps it to one per page load.
  }
  return true;
}

const counted = new Set<string>();

/** True the first time a visit with these tags lands on a location in this browser session (so it is counted once). */
export function firstVisit(key: string): boolean {
  if (counted.has(key)) return false;
  counted.add(key);
  try {
    const seen: string[] = JSON.parse(sessionStorage.getItem(COUNTED_KEY) ?? '[]');
    if (seen.includes(key)) return false;
    sessionStorage.setItem(COUNTED_KEY, JSON.stringify([...seen, key].slice(-20)));
  } catch {
    // Storage is unavailable: the in-memory set still keeps it to one per page load.
  }
  return true;
}

/** Test hook: forgets what this visit has seen. */
export function resetVisit(): void {
  viewed.clear();
  counted.clear();
  memory = null;
  try {
    sessionStorage.removeItem(VISITOR_KEY);
    sessionStorage.removeItem(VIEWED_KEY);
    sessionStorage.removeItem(COUNTED_KEY);
  } catch {
    // nothing to clear
  }
}
