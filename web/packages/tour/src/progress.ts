/*
 * Tour progress: what a person has done with each tour, kept per user, tour and version.
 *
 *  - The default store is the browser's localStorage, key `at.tour.v1:<userId>:<tourId>:<version>`. Every read and write
 *    is wrapped: private windows, blocked storage and server rendering all behave as "nothing saved".
 *  - A new `version` of a tour is a new key, so people see a tour again only when its content changed meaningfully.
 *  - Nothing here is sent anywhere. A server copy (so progress follows a person across browsers) is a documented port,
 *    TourProgressRemote; no backend implements it yet (see docs/engineering/guided-tours.md).
 */

/** How the person last left the tour: finished it, closed or skipped it, or asked never to see it again. */
export type TourOutcome = 'completed' | 'skipped' | 'never';

export interface TourProgress {
  outcome: TourOutcome;
  /** ISO time of the last change (the only data kept besides the outcome). */
  at: string;
}

export interface TourProgressStore {
  get(userId: string, tourId: string, version: number): TourProgress | undefined;
  set(userId: string, tourId: string, version: number, progress: TourProgress): void;
  /** Forgets one tour (every version) or, without a tour id, every tour of the person ("Reset tours"). */
  clear(userId: string, tourId?: string): void;
}

/**
 * PORT (not implemented, no backend yet): a server-side copy of the progress, so it follows a person between browsers.
 * When a backend appears, implement this against a user-preference endpoint and pass it to createTourProgressStore.
 * The local store stays the source of truth for the current page; the remote is read once at sign-in and written
 * after each change, and a failure never blocks a tour.
 */
export interface TourProgressRemote {
  load(userId: string): Promise<Record<string, TourProgress>>;
  save(userId: string, tourId: string, version: number, progress: TourProgress): Promise<void>;
}

/** The small part of Web Storage the store needs (so tests and non-browser hosts can pass their own). */
export interface TourStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
  readonly length: number;
  key(index: number): string | null;
}

const PREFIX = 'at.tour.v1:';

export function progressKey(userId: string, tourId: string, version: number): string {
  return `${PREFIX}${userId}:${tourId}:${version}`;
}

function isProgress(value: unknown): value is TourProgress {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return (v.outcome === 'completed' || v.outcome === 'skipped' || v.outcome === 'never') && typeof v.at === 'string';
}

function browserStorage(): TourStorage | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.localStorage;
  } catch {
    return undefined;
  }
}

export interface ProgressStoreOptions {
  /** Defaults to window.localStorage when there is one. */
  storage?: TourStorage;
  remote?: TourProgressRemote;
}

/** localStorage-backed progress store; every storage call is guarded. */
export function createTourProgressStore(options: ProgressStoreOptions = {}): TourProgressStore {
  return new LocalTourProgressStore(options);
}

class LocalTourProgressStore implements TourProgressStore {
  private readonly explicit: TourStorage | undefined;
  private readonly remote: TourProgressRemote | undefined;

  constructor(options: ProgressStoreOptions) {
    this.explicit = options.storage;
    this.remote = options.remote;
  }

  private storage(): TourStorage | undefined {
    return this.explicit ?? browserStorage();
  }

  get(userId: string, tourId: string, version: number): TourProgress | undefined {
    try {
      const raw = this.storage()?.getItem(progressKey(userId, tourId, version));
      if (!raw) return undefined;
      const parsed: unknown = JSON.parse(raw);
      return isProgress(parsed) ? parsed : undefined;
    } catch {
      return undefined;
    }
  }

  set(userId: string, tourId: string, version: number, progress: TourProgress): void {
    try {
      this.storage()?.setItem(progressKey(userId, tourId, version), JSON.stringify(progress));
    } catch {
      // storage full or blocked: the tour just may start again next time
    }
    if (this.remote) void this.remote.save(userId, tourId, version, progress).catch(() => undefined);
  }

  clear(userId: string, tourId?: string): void {
    try {
      const storage = this.storage();
      if (!storage) return;
      const prefix = tourId ? `${PREFIX}${userId}:${tourId}:` : `${PREFIX}${userId}:`;
      const doomed: string[] = [];
      for (let i = 0; i < storage.length; i++) {
        const k = storage.key(i);
        if (k?.startsWith(prefix)) doomed.push(k);
      }
      for (const k of doomed) storage.removeItem(k);
    } catch {
      // nothing to clear
    }
  }
}

/** In-memory store (tests, server rendering, hosts without storage). */
export function createMemoryProgressStore(): TourProgressStore {
  const map = new Map<string, TourProgress>();
  return {
    get: (u, t, v) => map.get(progressKey(u, t, v)),
    set: (u, t, v, p) => void map.set(progressKey(u, t, v), p),
    clear: (u, t) => {
      const prefix = t ? `${PREFIX}${u}:${t}:` : `${PREFIX}${u}:`;
      for (const k of [...map.keys()]) if (k.startsWith(prefix)) map.delete(k);
    }
  };
}
