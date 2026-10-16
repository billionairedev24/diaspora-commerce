import { useSyncExternalStore } from 'react';

/*
 * Redirect-loop guard for the back-office shells (Seller Center, staff console). Every redirect the shell makes on its
 * own (router.replace to onboarding or home, window.location to sign-in) goes through allow(destination): the
 * redirects are counted per destination in sessionStorage, so the count survives full page loads, and a destination
 * reached more than `limit` times within `windowMs` is a loop. The shell then stops redirecting and shows a panel
 * naming the destination (Try again, Go to storefront, Sign out) instead of spinning forever.
 *
 * The guard also keeps the "loading since" clock of the shell: a loader that is unmounted and mounted again (another
 * branch of the shell, a soft navigation, a full page load in a loop) keeps counting from the first one, so the
 * "taking longer than usual" fallback still appears while the browser goes round in circles.
 */

/** Round trips to one destination before the shell stops. */
export const NAV_LOOP_LIMIT = 3;
/** The window those round trips are counted in. */
export const NAV_LOOP_WINDOW_MS = 30_000;
/** A loader not seen for this long starts a new loading streak (a fresh visit, not the same loop). */
export const LOADING_STALE_MS = 15_000;

export interface NavLoop {
  /** Where the shell kept being sent (a path, or a URL for full-page redirects). */
  destination: string;
  /** Redirects to it within the window, the blocked one included. */
  count: number;
  windowMs: number;
}

export interface NavGuardOptions {
  /** sessionStorage key prefix, one per app (at.sc.nav, at.st.nav). */
  key: string;
  limit?: number;
  windowMs?: number;
  staleMs?: number;
  /** A repeat of the same redirect within this long in the same page counts once (React re-running an effect). */
  dedupeMs?: number;
  /** Defaults to window.sessionStorage (null when unavailable: the guard then only counts in memory). */
  storage?: () => Storage | null;
  now?: () => number;
}

export interface NavGuard {
  /** Records a redirect the shell is about to make; false when it would be one too many (the loop is reported). */
  allow(destination: string): boolean;
  /** The loop the shell stopped at, or null. */
  loop(): NavLoop | null;
  subscribe(listener: () => void): () => void;
  /** Try again: forgets the counted redirects, the loop and the loading clock. */
  reset(): void;
  /** Marks the shell as loading and returns when the current loading streak started. */
  loadingSince(): number;
  /** The shell showed a page: the next loader starts a new streak. */
  loaded(): void;
}

type Trail = Record<string, number[]>;

interface Loading {
  since: number;
  seen: number;
}

function sessionStore(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

/** Same-origin URLs count by path and query, so /onboarding and http://host/onboarding are one destination. */
export function destinationKey(destination: string): string {
  if (typeof window === 'undefined') return destination;
  try {
    const u = new URL(destination, window.location.href);
    return u.origin === window.location.origin ? `${u.pathname}${u.search}` : u.toString();
  } catch {
    return destination;
  }
}

export function createNavGuard(options: NavGuardOptions): NavGuard {
  const limit = options.limit ?? NAV_LOOP_LIMIT;
  const windowMs = options.windowMs ?? NAV_LOOP_WINDOW_MS;
  const staleMs = options.staleMs ?? LOADING_STALE_MS;
  const storage = options.storage ?? sessionStore;
  const now = options.now ?? Date.now;
  const dedupeMs = options.dedupeMs ?? 1000;
  const trailKey = `${options.key}.redirects`;
  const loopKey = `${options.key}.loop`;
  const loadingKey = `${options.key}.loading`;
  const listeners = new Set<() => void>();
  // In-memory copies: the guard keeps working when storage is blocked, and loop() returns a stable snapshot.
  let memTrail: Trail = {};
  let memLoading: Loading | null = null;
  let current: NavLoop | null | undefined;
  // The last redirect counted in this page (not stored: a full page load always counts).
  let last: { dest: string; at: number } | null = null;

  // Storage when it works (what other page loads of this tab wrote), else the in-memory copy.
  const read = <T>(key: string, memory: T, empty: T): T => {
    try {
      const st = storage();
      if (!st) return memory;
      const raw = st.getItem(key);
      return raw ? (JSON.parse(raw) as T) : empty;
    } catch {
      return memory;
    }
  };
  const write = (key: string, value: unknown) => {
    try {
      const s = storage();
      if (value === null) s?.removeItem(key);
      else s?.setItem(key, JSON.stringify(value));
    } catch {
      /* private mode: in memory only */
    }
  };
  const emit = () => listeners.forEach((l) => l());

  const trail = (): Trail => {
    const t = read<Trail>(trailKey, memTrail, {});
    const cutoff = now() - windowMs;
    const out: Trail = {};
    for (const [dest, times] of Object.entries(t)) {
      const recent = Array.isArray(times) ? times.filter((at) => typeof at === 'number' && at > cutoff) : [];
      if (recent.length) out[dest] = recent;
    }
    return out;
  };

  return {
    allow(destination) {
      const dest = destinationKey(destination);
      if (last && last.dest === dest && now() - last.at < dedupeMs) return false;
      last = { dest, at: now() };
      const t = trail();
      const times = [...(t[dest] ?? []), now()];
      t[dest] = times;
      memTrail = t;
      write(trailKey, t);
      if (times.length <= limit) return true;
      current = { destination: dest, count: times.length, windowMs };
      write(loopKey, current);
      emit();
      return false;
    },
    loop() {
      if (current === undefined) {
        // After a full page load: a loop stopped moments ago is still a loop (until Try again or the window ends).
        const saved = read<NavLoop | null>(loopKey, null, null);
        const recent = saved ? (trail()[saved.destination]?.length ?? 0) > limit : false;
        current = saved && recent ? saved : null;
      }
      return current;
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    reset() {
      last = null;
      memTrail = {};
      memLoading = null;
      current = null;
      write(trailKey, null);
      write(loopKey, null);
      write(loadingKey, null);
      emit();
    },
    loadingSince() {
      const at = now();
      const prev = read<Loading | null>(loadingKey, memLoading, null);
      const since = prev && at - prev.seen <= staleMs && prev.since <= at ? prev.since : at;
      memLoading = { since, seen: at };
      write(loadingKey, memLoading);
      return since;
    },
    loaded() {
      last = null;
      memLoading = null;
      write(loadingKey, null);
    }
  };
}

/** The loop the shell stopped at (re-renders when one is detected or Try again clears it). */
export function useNavLoop(guard: NavGuard): NavLoop | null {
  return useSyncExternalStore(
    guard.subscribe,
    () => guard.loop(),
    () => null
  );
}
