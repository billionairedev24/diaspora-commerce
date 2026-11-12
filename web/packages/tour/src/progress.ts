/*
 * Tour progress: what a person has done with each tour (chapter), per user and tour.
 *
 *  - The SERVER is the source of truth (identity's per-user tour progress, behind the BFF), so a tour a person finished or
 *    dismissed does not start again in another browser or on another device. The service loads it before any tour starts by
 *    itself (see TourProgressRemote and service.sync()).
 *  - The browser's localStorage is only a CACHE: it lets the Tour hub and the Help menu render at once, and keeps working
 *    offline. Key `at.tour.v2:<userId>:<tourId>`. Every read and write is wrapped, so private windows, blocked storage and
 *    server rendering behave as "nothing cached".
 *  - Progress holds the tour's content version. A tour whose content is newer than what the person last dealt with is shown in
 *    the hub with a small "What's new" mark; it never starts by itself.
 *  - What is stored is ids, a status and times. No names, no emails, nothing typed by the person.
 */

/** Where the person left a tour: part way through, finished it, or dismissed it ("Don't show again" or "Skip tour"). */
export type TourStatus = 'in-progress' | 'completed' | 'dismissed';

export interface TourProgress {
  status: TourStatus;
  /** The content version of the tour this record is about. */
  version: number;
  /** The last step shown (so the hub can resume), when known. */
  lastStep?: string;
  /** ISO time of the last change. */
  at: string;
  /** Written here but not yet confirmed by the server (it is sent again at the next sync). */
  dirty?: boolean;
}

/** What the person sees in the hub for one chapter. */
export type ChapterStatus = 'not-started' | TourStatus;

/** The final statuses: a replay in progress never undoes them (same rule as the server). */
export function isFinal(status: TourStatus): boolean {
  return status !== 'in-progress';
}

/**
 * Whether a write replaces what is stored. A newer content version always wins and an older one never does; for the same
 * version, a final status is not undone by "in progress". Identity applies the same rule, so both sides agree.
 */
export function replaces(stored: TourProgress | undefined, version: number, status: TourStatus): boolean {
  if (!stored) return true;
  if (version !== stored.version) return version > stored.version;
  return !(isFinal(stored.status) && !isFinal(status));
}

/** A record as the server keeps it (statuses in capitals, tour id namespaced by app). */
export interface RemoteTourProgress {
  tourId: string;
  contentVersion: number;
  status: 'IN_PROGRESS' | 'COMPLETED' | 'DISMISSED';
  lastStep: string | null;
  updatedAt?: string;
}

export interface RemoteTourSnapshot {
  items: RemoteTourProgress[];
  /** Platform flag: false means first-time tours never start by themselves. */
  autoStartEnabled: boolean;
}

/**
 * The server copy of the progress. The app implements it over the BFF (`/v1/account/tour-progress`); the service adds the
 * namespace to tour ids. Every call may fail (offline, server down): the service then keeps working from the cache and
 * retries later, and a first-time tour does not start by itself until a load has succeeded.
 */
export interface TourProgressRemote {
  load(): Promise<RemoteTourSnapshot>;
  /** Idempotent upsert; answers what the server now holds (it may differ: an older version or "in progress" over a final status is ignored). */
  save(item: RemoteTourProgress): Promise<RemoteTourProgress>;
  /** First sync: copies what the browser kept. What the server already holds for a version stands. */
  merge(items: RemoteTourProgress[]): Promise<RemoteTourSnapshot>;
  /** One tour, or every tour of the person without an id. */
  reset(tourId?: string): Promise<void>;
}

const STATUS_TO_REMOTE = { 'in-progress': 'IN_PROGRESS', completed: 'COMPLETED', dismissed: 'DISMISSED' } as const;
const STATUS_FROM_REMOTE = { IN_PROGRESS: 'in-progress', COMPLETED: 'completed', DISMISSED: 'dismissed' } as const;

export function toRemote(namespace: string, tourId: string, p: TourProgress): RemoteTourProgress {
  return { tourId: namespace ? `${namespace}.${tourId}` : tourId, contentVersion: p.version, status: STATUS_TO_REMOTE[p.status], lastStep: p.lastStep ?? null };
}

/** The local id and record of a server item, or undefined when it belongs to another app. */
export function fromRemote(namespace: string, r: RemoteTourProgress): { tourId: string; progress: TourProgress } | undefined {
  const prefix = namespace ? `${namespace}.` : '';
  if (!r.tourId.startsWith(prefix)) return undefined;
  const status = STATUS_FROM_REMOTE[r.status];
  if (!status) return undefined;
  return { tourId: r.tourId.slice(prefix.length), progress: { status, version: r.contentVersion, lastStep: r.lastStep ?? undefined, at: r.updatedAt ?? new Date().toISOString() } };
}

/** An entry written by the first version of the store (one key per tour version, outcome completed / skipped / never). */
export interface LegacyProgress {
  tourId: string;
  version: number;
  outcome: 'completed' | 'skipped' | 'never';
  at: string;
}

/** Local cache of the progress. Synchronous, so the UI can render at once. */
export interface TourProgressStore {
  get(userId: string, tourId: string): TourProgress | undefined;
  set(userId: string, tourId: string, progress: TourProgress): void;
  remove(userId: string, tourId: string): void;
  /** Every cached tour of the person. */
  all(userId: string): Record<string, TourProgress>;
  /** Forgets every tour of the person. */
  clear(userId: string): void;
  /** Progress written by the first version of the store, to be copied up once. */
  legacy(userId: string): LegacyProgress[];
  /** Forgets the first version's keys once they have been copied to the server. */
  dropLegacy(userId: string): void;
  /** Whether this browser's progress has already been copied to the server for the person. */
  migrated(userId: string): boolean;
  markMigrated(userId: string): void;
}

/** The small part of Web Storage the store needs (so tests and non-browser hosts can pass their own). */
export interface TourStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
  readonly length: number;
  key(index: number): string | null;
}

const PREFIX = 'at.tour.v2:';
const LEGACY_PREFIX = 'at.tour.v1:';
const MIGRATED_PREFIX = 'at.tour.migrated:';

export function progressKey(userId: string, tourId: string): string {
  return `${PREFIX}${userId}:${tourId}`;
}

function isProgress(value: unknown): value is TourProgress {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return (v.status === 'in-progress' || v.status === 'completed' || v.status === 'dismissed') && typeof v.version === 'number' && typeof v.at === 'string';
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
}

/** localStorage-backed cache; every storage call is guarded. */
export function createTourProgressStore(options: ProgressStoreOptions = {}): TourProgressStore {
  return new LocalTourProgressStore(options);
}

class LocalTourProgressStore implements TourProgressStore {
  private readonly explicit: TourStorage | undefined;

  constructor(options: ProgressStoreOptions) {
    this.explicit = options.storage;
  }

  private storage(): TourStorage | undefined {
    return this.explicit ?? browserStorage();
  }

  private keys(prefix: string): string[] {
    const storage = this.storage();
    const found: string[] = [];
    if (!storage) return found;
    for (let i = 0; i < storage.length; i++) {
      const k = storage.key(i);
      if (k?.startsWith(prefix)) found.push(k);
    }
    return found;
  }

  get(userId: string, tourId: string): TourProgress | undefined {
    try {
      const raw = this.storage()?.getItem(progressKey(userId, tourId));
      if (!raw) return undefined;
      const parsed: unknown = JSON.parse(raw);
      return isProgress(parsed) ? parsed : undefined;
    } catch {
      return undefined;
    }
  }

  set(userId: string, tourId: string, progress: TourProgress): void {
    try {
      this.storage()?.setItem(progressKey(userId, tourId), JSON.stringify(progress));
    } catch {
      // storage full or blocked: the server copy is the truth, the cache just misses this change
    }
  }

  remove(userId: string, tourId: string): void {
    try {
      this.storage()?.removeItem(progressKey(userId, tourId));
    } catch {
      // nothing to remove
    }
  }

  all(userId: string): Record<string, TourProgress> {
    const out: Record<string, TourProgress> = {};
    try {
      const prefix = `${PREFIX}${userId}:`;
      for (const k of this.keys(prefix)) {
        const tourId = k.slice(prefix.length);
        const p = this.get(userId, tourId);
        if (p) out[tourId] = p;
      }
    } catch {
      // empty cache
    }
    return out;
  }

  clear(userId: string): void {
    try {
      const storage = this.storage();
      for (const k of this.keys(`${PREFIX}${userId}:`)) storage?.removeItem(k);
    } catch {
      // nothing to clear
    }
  }

  legacy(userId: string): LegacyProgress[] {
    const out: LegacyProgress[] = [];
    try {
      const prefix = `${LEGACY_PREFIX}${userId}:`;
      for (const k of this.keys(prefix)) {
        const [tourId, version] = k.slice(prefix.length).split(':');
        const raw = this.storage()?.getItem(k);
        const parsed: unknown = raw ? JSON.parse(raw) : null;
        const v = Number(version);
        if (!tourId || !Number.isInteger(v) || v < 1 || typeof parsed !== 'object' || parsed === null) continue;
        const o = parsed as Record<string, unknown>;
        if ((o.outcome === 'completed' || o.outcome === 'skipped' || o.outcome === 'never') && typeof o.at === 'string') out.push({ tourId, version: v, outcome: o.outcome, at: o.at });
      }
    } catch {
      // no legacy progress
    }
    return out;
  }

  dropLegacy(userId: string): void {
    try {
      const storage = this.storage();
      for (const k of this.keys(`${LEGACY_PREFIX}${userId}:`)) storage?.removeItem(k);
    } catch {
      // nothing to drop
    }
  }

  migrated(userId: string): boolean {
    try {
      return this.storage()?.getItem(`${MIGRATED_PREFIX}${userId}`) === '1';
    } catch {
      return false;
    }
  }

  markMigrated(userId: string): void {
    try {
      this.storage()?.setItem(`${MIGRATED_PREFIX}${userId}`, '1');
    } catch {
      // the merge is idempotent, so doing it again next time is harmless
    }
  }
}

/** In-memory store (tests, server rendering, hosts without storage). */
export function createMemoryProgressStore(options: { legacy?: Record<string, LegacyProgress[]> } = {}): TourProgressStore {
  const map = new Map<string, TourProgress>();
  const legacy = new Map<string, LegacyProgress[]>(Object.entries(options.legacy ?? {}));
  const migrated = new Set<string>();
  return {
    get: (u, t) => map.get(progressKey(u, t)),
    set: (u, t, p) => void map.set(progressKey(u, t), p),
    remove: (u, t) => void map.delete(progressKey(u, t)),
    all: (u) => {
      const out: Record<string, TourProgress> = {};
      const prefix = `${PREFIX}${u}:`;
      for (const [k, v] of map) if (k.startsWith(prefix)) out[k.slice(prefix.length)] = v;
      return out;
    },
    clear: (u) => {
      const prefix = `${PREFIX}${u}:`;
      for (const k of [...map.keys()]) if (k.startsWith(prefix)) map.delete(k);
    },
    legacy: (u) => legacy.get(u) ?? [],
    dropLegacy: (u) => void legacy.delete(u),
    migrated: (u) => migrated.has(u),
    markMigrated: (u) => void migrated.add(u)
  };
}

/** The outcome the first version kept, as the status the server understands. */
export function statusOfLegacy(outcome: LegacyProgress['outcome']): TourStatus {
  return outcome === 'completed' ? 'completed' : 'dismissed';
}
