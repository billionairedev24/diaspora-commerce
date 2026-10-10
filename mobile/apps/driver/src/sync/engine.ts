/* Offline-first sync engine (S087). Plain TypeScript with injected dependencies so it runs in unit tests.
   record(): enqueue with the next sequence + apply locally + persist, then flush (SOS is also POSTed at once).
   flush(): upload pending photos, send batches in order, apply per-event outcomes, repeat until empty. */
import { applySyncResponse, emptyOutbox, enqueue, markSosSent, nextBatch, type OutboxState, pendingSos, type SyncApplication, type SyncIssue } from '@/domain/outbox';
import { applyLocal } from '@/domain/routeState';
import { type DriverEventBody, type QueuedEvent, type Route } from '@/domain/types';
import { pendingUploads } from '@/domain/uploads';
import { type DriverStore } from '@/storage/store';

export interface EngineApi {
  sync(deviceId: string, events: QueuedEvent[]): Promise<{ lastSequence?: number; expectedSequence?: number; signOut?: boolean; revokedAt?: string; results?: Array<{ eventId?: string; sequence?: number; outcome?: string; code?: string; message?: string }> }>;
  sos(deviceId: string, event: QueuedEvent): Promise<unknown>;
  uploadPhoto(localUri: string, purpose: 'proof-photo'): Promise<string>;
  routes(): Promise<Route[]>;
}

export interface EngineDeps {
  store: DriverStore;
  api: EngineApi;
  uuid: () => string;
  now: () => Date;
  position: () => Promise<{ lat: number; lon: number } | null>;
}

export interface EngineState {
  ready: boolean;
  outbox: OutboxState;
  route: Route | null;
  online: boolean;
  syncing: boolean;
  lastError: string | null;
  userId: string | null;
  /** Set when the server reports that driver access was revoked. */
  revokedAt: string | null;
}

const ROUTE_KEY = 'route.current';

export class SyncEngine {
  private state: EngineState;
  private listeners = new Set<() => void>();
  private flushing: Promise<void> | null = null;
  private again = false;

  constructor(private readonly deps: EngineDeps) {
    this.state = { ready: false, outbox: emptyOutbox(''), route: null, online: true, syncing: false, lastError: null, userId: null, revokedAt: null };
  }

  getState = (): EngineState => this.state;

  subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  };

  private set(patch: Partial<EngineState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((l) => l());
  }

  async init(): Promise<void> {
    const saved = await this.deps.store.loadOutbox();
    const outbox = saved ?? emptyOutbox(`dev-${this.deps.uuid()}`);
    if (!saved) await this.deps.store.saveOutbox(outbox);
    const route = await this.deps.store.getKv<Route>(ROUTE_KEY);
    this.set({ ready: true, outbox, route });
  }

  setUser(userId: string | null): void {
    this.set({ userId, revokedAt: null });
  }

  setOnline(online: boolean): void {
    const was = this.state.online;
    this.set({ online });
    if (online && !was) void this.flush();
  }

  private saving: Promise<void> = Promise.resolve();

  /** Applies a change to the latest state synchronously (no lost updates between concurrent tasks) and queues the
      write: saves run one after another, each writing the snapshot taken at its commit. */
  private commit(fn: (s: EngineState) => { outbox?: OutboxState; route?: Route | null }): Promise<void> {
    this.set(fn(this.state));
    const { outbox, route } = this.state;
    this.saving = this.saving
      .then(async () => {
        await this.deps.store.saveOutbox(outbox);
        if (route) await this.deps.store.setKv(ROUTE_KEY, route);
      })
      .catch(() => undefined);
    return this.saving;
  }

  /** Captures an action: it is applied locally at once and synced in order when online. */
  async record(body: DriverEventBody, opts: { note?: string } = {}): Promise<QueuedEvent> {
    const route = this.state.route;
    if (!route) throw new Error('No route downloaded yet.');
    const pos = await this.deps.position().catch(() => null);
    const capturedAt = this.deps.now().toISOString();
    const box: { event: QueuedEvent | null } = { event: null };
    await this.commit((st) => {
      const r = enqueue(st.outbox, body, { eventId: this.deps.uuid(), routeId: route.id, capturedAt, lat: pos?.lat ?? null, lon: pos?.lon ?? null, ownerId: st.userId ?? 'unknown', ...(opts.note ? { note: opts.note } : {}) });
      box.event = r.event;
      return { outbox: r.state, route: st.route ? applyLocal(st.route, body, capturedAt) : st.route };
    });
    const event = box.event;
    if (!event) throw new Error('Could not queue the action.');
    if (body.type === 'sos.raised') await this.raiseSos();
    void this.flush();
    return event;
  }

  /** SOS goes out immediately (and stays queued so the ordered sync carries it too). */
  async raiseSos(): Promise<void> {
    for (const e of pendingSos(this.state.outbox)) {
      try {
        await this.deps.api.sos(this.state.outbox.deviceId, e);
        await this.commit((st) => ({ outbox: markSosSent(st.outbox, e.eventId) }));
      } catch {
        /* offline: the batch sync sends it first when back online */
      }
    }
  }

  /** Runs until the queue is drained (or offline); calls during a flush make it run once more. */
  flush(): Promise<void> {
    this.again = true;
    if (!this.flushing) {
      this.flushing = (async () => {
        try {
          while (this.again) {
            this.again = false;
            await this.run();
          }
        } finally {
          this.flushing = null;
        }
      })();
    }
    return this.flushing;
  }

  private async run(): Promise<void> {
    const { online, userId } = this.state;
    if (!online || !userId) return;
    const owner = this.state.outbox.ownerId;
    if (owner && owner !== userId && this.state.outbox.events.length) {
      this.set({ lastError: 'Events from the previous driver on this phone are waiting for them to sign in and sync.' });
      return;
    }
    this.set({ syncing: true, lastError: null });
    let from = 0;
    let refetch = false;
    try {
      if (pendingSos(this.state.outbox).length) await this.raiseSos();
      for (let guard = 0; guard < 50; guard += 1) {
        const batch = nextBatch(this.state.outbox, from);
        if (!batch.length) break;
        const ready = await this.uploadMedia(batch);
        const res = await this.deps.api.sync(this.state.outbox.deviceId, ready);
        const box: { v: SyncApplication | null } = { v: null };
        await this.commit((st) => {
          box.v = applySyncResponse(st.outbox, res, this.deps.now().toISOString());
          return { outbox: box.v.state };
        });
        const applied = box.v;
        if (!applied) break;
        refetch ||= applied.needsRefetch;
        if (applied.signOut) {
          this.set({ revokedAt: applied.revokedAt ?? this.deps.now().toISOString() });
          break;
        }
        if (applied.resendFrom !== null) {
          if (from === applied.resendFrom) break; // still missing on the server: try again later
          from = applied.resendFrom;
        } else from = 0;
      }
      if (refetch || this.state.outbox.events.length === 0) await this.refreshRoute();
    } catch (e) {
      this.set({ lastError: e instanceof Error ? e.message : 'Sync failed' });
    } finally {
      this.set({ syncing: false });
    }
  }

  private async uploadMedia(batch: QueuedEvent[]): Promise<QueuedEvent[]> {
    const out: QueuedEvent[] = [];
    for (const ev of batch) {
      let e = ev;
      for (const up of pendingUploads(ev)) e = up.apply(e, await this.deps.api.uploadPhoto(up.localUri, 'proof-photo'));
      if (e !== ev) {
        const done = e;
        await this.commit((st) => ({ outbox: { ...st.outbox, events: st.outbox.events.map((x) => (x.eventId === done.eventId ? done : x)) } }));
      }
      out.push(e);
    }
    return out;
  }

  /** Server truth + the events still queued on this phone (re-applied on top). */
  async refreshRoute(): Promise<void> {
    if (!this.state.online || !this.state.userId) return;
    const routes = await this.deps.api.routes();
    const server = routes.find((r) => r.id === this.state.route?.id) ?? routes[0] ?? null;
    if (!server) return;
    await this.commit((st) => ({ route: st.outbox.events.filter((e) => e.routeId === server.id).reduce<Route>((r, e) => applyLocal(r, e, e.capturedAt), server) }));
  }

  dismissIssue(eventId: string): void {
    void this.commit((st) => ({ outbox: { ...st.outbox, issues: st.outbox.issues.filter((i: SyncIssue) => i.eventId !== eventId) } }));
  }
}
