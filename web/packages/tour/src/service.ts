import { audienceAllows, toursFor, type TourSubject } from './audience';
import { type EngineCallbacks, type StepView, type TourEngine, type TourLabels } from './engine';
import { type TourEnvironment } from './environment';
import {
  type ChapterStatus,
  fromRemote,
  replaces,
  statusOfLegacy,
  toRemote,
  type TourProgress,
  type TourProgressRemote,
  type TourProgressStore,
  type TourStatus
} from './progress';
import { type Tour, tourSchema, type TourStep } from './schema';

/** Below this width the popover has no room next to a target: tours do not start by themselves and steps open as a bottom sheet. */
export const SHEET_MAX_WIDTH = 640;
/** Narrowest viewport where a tour may start on its own. */
export const AUTO_START_MIN_WIDTH = SHEET_MAX_WIDTH;
/** Wait for a target on the current page (a table still loading). */
export const TARGET_WAIT_MS = 2500;
/** Wait for a target after navigating to another page. */
export const ROUTE_TARGET_WAIT_MS = 6000;
/** A step is saved to the server this long after the person moved on (so a quick walk through costs one call, not one per step). */
export const STEP_SAVE_DELAY_MS = 2000;
/** Waits before the next try to load progress from the server after a failure (the last one repeats). */
export const SYNC_RETRY_MS = [5_000, 15_000, 60_000, 300_000] as const;
/** After the person clicks the control of a "do it" step, the tour moves on once the page has had a moment to react. */
export const DO_IT_ADVANCE_MS = 350;

export type StartSource = 'auto' | 'manual';
export type StartResult = 'started' | 'unknown-tour' | 'not-allowed' | 'no-steps';
export type StopReason = 'completed' | 'skip' | 'close' | 'replaced' | 'destroyed';
/** Whether the server copy of the progress has been loaded: first-time tours wait for `ready`. */
export type SyncState = 'idle' | 'loading' | 'ready' | 'failed';

/** What the UI can observe about the service. A new object is made on every change (useSyncExternalStore). */
export interface TourState {
  readonly running: boolean;
  readonly tourId: string | null;
  readonly stepId: string | null;
  readonly sync: SyncState;
  /** Bumped whenever progress changes, so the hub and the Help menu redraw. */
  readonly revision: number;
}

export interface TourServiceDeps {
  engine: TourEngine;
  /** Local cache of the progress (instant render, offline). */
  store: TourProgressStore;
  /** The server copy, the source of truth. Without it the service is local-only (tests, hosts with no backend). */
  remote?: TourProgressRemote;
  /** Prefix of the tour ids on the server (for example `sc` or `st`), so two apps never share a record. */
  namespace?: string;
  env: TourEnvironment;
  subject: () => TourSubject;
  /** Translates an i18n key of the app's catalogue. */
  translate: (key: string) => string;
  labels: () => TourLabels;
  /** Opens a page of the app (router.push). */
  navigate: (route: string) => void;
  /** The current path (no query string). */
  pathname: () => string;
  /** The current query string including "?" (or ""), for steps that need a page in a particular state. Defaults to none. */
  search?: () => string;
  /** Named guards used by steps' `when`; a missing guard counts as "yes". */
  guards?: Readonly<Record<string, () => boolean>>;
}

/** What the hub shows for one chapter. */
export interface ChapterInfo {
  status: ChapterStatus;
  lastStep?: string;
  /** The tour's content is newer than what the person last dealt with: a small "What's new" mark, never an automatic start. */
  hasUpdate: boolean;
  /** Started and not finished: the hub offers Resume. */
  resumable: boolean;
}

export interface StartOptions {
  /** Begin at this step instead of the first (Resume). */
  fromStep?: string;
}

export interface TourService {
  /** Replaces the registered tours. Each definition is validated; an invalid one throws. */
  register(tours: readonly Tour[]): void;
  tours(): readonly Tour[];
  /** The registered tours this person may see. */
  available(): Tour[];
  /** What the person last did with a tour (any version), from the cache. */
  progressOf(tourId: string): TourProgress | undefined;
  /** The hub's view of one chapter. */
  info(tourId: string): ChapterInfo;
  /**
   * Loads the progress from the server (the source of truth) into the cache, copying what this browser kept the first
   * time. Resolves true when the server copy is in. On failure it stays quiet (no tour starts by itself), resolves false,
   * and tries again later by itself. Without a remote it resolves true at once.
   */
  sync(): Promise<boolean>;
  getSync(): SyncState;
  /** The platform flag: false means first-time tours never start by themselves (loaded with the progress). */
  autoStartEnabled(): boolean;
  start(tourId: string, source?: StartSource, options?: StartOptions): Promise<StartResult>;
  /** Starts the first tour that is meant to begin on this page and has not been seen, if the page is calm enough and progress is loaded. */
  autoStart(): Promise<Tour | undefined>;
  next(): Promise<void>;
  back(): Promise<void>;
  skip(): void;
  close(): void;
  /** Marks a chapter as dismissed without playing it ("Skip" in the hub). */
  dismiss(tourId: string): void;
  /** Forgets progress for one tour, or all tours of the person ("Reset my progress"), here and on the server. Resolves false when the server could not be reached (nothing was changed). */
  reset(tourId?: string): Promise<boolean>;
  getState(): TourState;
  subscribe(listener: () => void): () => void;
  destroy(): void;
}

interface Run {
  tour: Tour;
  source: StartSource;
  index: number;
  skipped: Set<number>;
  succeeded: boolean;
  dontShowAgain: boolean;
  releaseSuccess: (() => void) | null;
  /** Steps that were not optional but could not be shown because their target or page was missing (what the drift guard looks for). */
  drift: string[];
  shown: number;
  /** Bumped by every navigation so a slow step from before cannot draw over a newer one. */
  token: number;
  advanceTimer: ReturnType<typeof setTimeout> | null;
}

/** Whether the page is the route: same path, and every query parameter the route names has that value. */
export function routeMatches(route: string, pathname: string, search = ''): boolean {
  const [path = '', query = ''] = route.split('?');
  if (path !== pathname) return false;
  if (!query) return true;
  const have = new URLSearchParams(search);
  for (const [k, v] of new URLSearchParams(query)) if (have.get(k) !== v) return false;
  return true;
}

const IDLE: TourState = { running: false, tourId: null, stepId: null, sync: 'idle', revision: 0 };

export function createTourService(deps: TourServiceDeps): TourService {
  return new TourServiceImpl(deps);
}

class TourServiceImpl implements TourService {
  private registry: readonly Tour[] = [];
  private state: TourState = IDLE;
  private run: Run | null = null;
  private syncState: SyncState = 'idle';
  private syncing: Promise<boolean> | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private retries = 0;
  private autoStartFlag = true;
  private destroyed = false;
  /** Pending step saves (debounced) and tours whose last write is not yet confirmed by the server. */
  private readonly saveTimers = new Map<string, ReturnType<typeof setTimeout>>();
  /** At most one tour starts by itself per visit (the service lives as long as the page does), so people are never walked through three in a row. */
  private autoStarted = false;
  private readonly listeners = new Set<() => void>();
  private readonly callbacks: EngineCallbacks = {
    next: () => void this.next(),
    back: () => void this.back(),
    skip: () => this.skip(),
    close: () => this.close(),
    setDontShowAgain: (v) => {
      if (this.run) this.run.dontShowAgain = v;
    }
  };

  constructor(private readonly deps: TourServiceDeps) {
    this.state = { ...IDLE, sync: deps.remote ? 'idle' : 'ready' };
    this.syncState = this.state.sync;
  }

  register(tours: readonly Tour[]): void {
    this.registry = tours.map((t) => tourSchema.parse(t));
  }

  tours(): readonly Tour[] {
    return this.registry;
  }

  available(): Tour[] {
    return toursFor(this.registry, this.deps.subject());
  }

  progressOf(tourId: string): TourProgress | undefined {
    return this.deps.store.get(this.userId(), tourId);
  }

  info(tourId: string): ChapterInfo {
    const tour = this.registry.find((t) => t.id === tourId);
    const p = this.progressOf(tourId);
    if (!p) return { status: 'not-started', hasUpdate: false, resumable: false };
    return { status: p.status, lastStep: p.lastStep, hasUpdate: tour ? p.version < tour.version : false, resumable: p.status === 'in-progress' && Boolean(p.lastStep) };
  }

  getSync(): SyncState {
    return this.syncState;
  }

  autoStartEnabled(): boolean {
    return this.autoStartFlag;
  }

  // ---------------------------------------------------------------- server sync

  sync(): Promise<boolean> {
    if (!this.deps.remote) return Promise.resolve(true);
    if (this.syncState === 'ready') return Promise.resolve(true);
    this.syncing ??= this.loadFromServer().finally(() => {
      this.syncing = null;
    });
    return this.syncing;
  }

  private async loadFromServer(): Promise<boolean> {
    const remote = this.deps.remote;
    if (!remote || this.destroyed) return true;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
    this.setSync('loading');
    const userId = this.userId();
    try {
      let snapshot = await remote.load();
      // First sync from this browser: copy up what it kept (the first version's keys, and cache entries the server never confirmed).
      const upload = this.localUploads(userId);
      if (!this.deps.store.migrated(userId) || upload.length > 0) {
        if (upload.length > 0) snapshot = await remote.merge(upload);
        this.deps.store.dropLegacy(userId);
        this.deps.store.markMigrated(userId);
      }
      if (this.destroyed || userId !== this.userId()) return false;
      this.adopt(userId, snapshot.items);
      this.autoStartFlag = snapshot.autoStartEnabled;
      this.retries = 0;
      this.setSync('ready');
      await this.flushDirty(userId);
      return true;
    } catch (error) {
      if (this.destroyed) return false;
      this.deps.env.log(`tour progress could not be loaded (${error instanceof Error ? error.message : 'error'}); no tour starts by itself, trying again later`);
      this.setSync('failed');
      const wait = SYNC_RETRY_MS[Math.min(this.retries, SYNC_RETRY_MS.length - 1)] ?? 300_000;
      this.retries += 1;
      this.retryTimer = setTimeout(() => void this.sync(), wait);
      return false;
    }
  }

  /** What this browser kept that the server may not have: the first version's keys (highest version per tour) and cache entries written while offline. */
  private localUploads(userId: string): ReturnType<typeof toRemote>[] {
    const ns = this.deps.namespace ?? '';
    const known = new Set(this.registry.map((t) => t.id));
    const best = new Map<string, TourProgress>();
    for (const l of this.deps.store.migrated(userId) ? [] : this.deps.store.legacy(userId)) {
      if (known.size > 0 && !known.has(l.tourId)) continue;
      const p: TourProgress = { status: statusOfLegacy(l.outcome), version: l.version, at: l.at };
      const have = best.get(l.tourId);
      if (!have || p.version > have.version) best.set(l.tourId, p);
    }
    for (const [tourId, p] of Object.entries(this.deps.store.all(userId))) if (p.dirty) best.set(tourId, p);
    return [...best].map(([tourId, p]) => toRemote(ns, tourId, p)).slice(0, 100);
  }

  /** Makes the cache equal the server's copy (the source of truth), keeping what has not been confirmed yet. */
  private adopt(userId: string, items: Parameters<typeof fromRemote>[1][]): void {
    const ns = this.deps.namespace ?? '';
    const server = new Map<string, TourProgress>();
    for (const r of items) {
      const mapped = fromRemote(ns, r);
      if (mapped) server.set(mapped.tourId, mapped.progress);
    }
    for (const [tourId, local] of Object.entries(this.deps.store.all(userId))) {
      if (!server.has(tourId) && !local.dirty) this.deps.store.remove(userId, tourId);
    }
    for (const [tourId, p] of server) {
      const local = this.deps.store.get(userId, tourId);
      // An unconfirmed local write that the server's rules would accept is sent right after; otherwise the server's copy stands.
      if (local?.dirty && replaces(p, local.version, local.status)) continue;
      this.deps.store.set(userId, tourId, p);
    }
    this.bump();
  }

  private async flushDirty(userId: string): Promise<void> {
    for (const [tourId, p] of Object.entries(this.deps.store.all(userId))) {
      if (p.dirty) await this.push(tourId, p);
    }
  }

  /** Sends one tour's state to the server; on success the cache takes what the server answered (its rules decide). */
  private async push(tourId: string, p: TourProgress): Promise<void> {
    const remote = this.deps.remote;
    if (!remote) return;
    const ns = this.deps.namespace ?? '';
    const userId = this.userId();
    try {
      const saved = await remote.save(toRemote(ns, tourId, p));
      const mapped = fromRemote(ns, saved);
      const current = this.deps.store.get(userId, tourId);
      // Something newer was written while this was in flight: that write sends itself.
      if (current && current.at !== p.at && current.dirty) return;
      if (mapped) this.deps.store.set(userId, tourId, mapped.progress);
      this.bump();
    } catch {
      // stays "dirty": sent again at the next sync or the next change
      this.scheduleRetry();
    }
  }

  private scheduleRetry(): void {
    if (this.retryTimer || this.destroyed) return;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      void this.flushDirty(this.userId());
    }, SYNC_RETRY_MS[0]);
  }

  private setSync(next: SyncState): void {
    this.syncState = next;
    this.publish({ ...this.state, sync: next });
  }

  // ---------------------------------------------------------------- starting

  async start(tourId: string, source: StartSource = 'manual', options: StartOptions = {}): Promise<StartResult> {
    const tour = this.registry.find((t) => t.id === tourId);
    if (!tour) return 'unknown-tour';
    if (!audienceAllows(tour.audience, this.deps.subject())) return 'not-allowed';
    if (this.run) this.finish('replaced');
    const run: Run = { tour, source, index: -1, skipped: new Set(), succeeded: false, dontShowAgain: false, releaseSuccess: null, drift: [], shown: 0, token: 0, advanceTimer: null };
    this.run = run;
    // A tour that begins by itself is recorded as started right away, so a reload in the middle never repeats it.
    if (source === 'auto') this.record(tour, 'in-progress', tour.steps[0]?.id, true);
    this.publish({ ...this.state, running: true, tourId: tour.id, stepId: null });
    const from = options.fromStep ? Math.max(0, tour.steps.findIndex((s) => s.id === options.fromStep)) : 0;
    const shown = await this.go(run, from, 1);
    if (!shown && this.run === run) {
      this.finish('replaced');
      return 'no-steps';
    }
    return 'started';
  }

  async autoStart(): Promise<Tour | undefined> {
    if (this.run || this.autoStarted) return undefined;
    // Nothing starts by itself until the server has been asked, so a tour never flashes up and then turns out to be finished elsewhere.
    if (this.syncState !== 'ready' || !this.autoStartFlag) return undefined;
    const { env } = this.deps;
    if (env.automated() || env.viewportWidth() < AUTO_START_MIN_WIDTH) return undefined;
    if (env.hasBlockingUi() || env.hasUnsavedInput()) return undefined;
    if (typeof document !== 'undefined' && document.activeElement instanceof HTMLElement && /^(input|textarea|select)$/i.test(document.activeElement.tagName)) return undefined;
    const path = this.deps.pathname();
    const onPage = (route: string) => (route.endsWith('/*') ? path.startsWith(route.slice(0, -1)) : route === path);
    // Any record (even for an older version) means the person has met this tour: a new version is a "What's new" mark in the hub, not a pop-up.
    const candidate = this.available().find((t) => t.autoStart?.routes.some(onPage) && !this.progressOf(t.id) && this.firstStepReady(t));
    if (!candidate) return undefined;
    const result = await this.start(candidate.id, 'auto');
    if (result === 'started') this.autoStarted = true;
    return result === 'started' ? candidate : undefined;
  }

  /** A tour only starts by itself when its first step can be drawn right here, right now (the page has loaded); otherwise it would wander off to another page. */
  private firstStepReady(tour: Tour): boolean {
    const run: Run = { tour, source: 'auto', index: 0, skipped: new Set(), succeeded: false, dontShowAgain: false, releaseSuccess: null, drift: [], shown: 0, token: 0, advanceTimer: null };
    const i = tour.steps.findIndex((s) => this.guardPasses(s));
    const step = tour.steps[i];
    if (!step) return false;
    const route = this.routeOf(run, i);
    if (route && !this.at(route)) return false;
    return !step.target || this.deps.env.findTarget(step.target) !== null;
  }

  async next(): Promise<void> {
    const run = this.run;
    if (!run) return;
    const shown = await this.go(run, run.index + 1, 1);
    if (!shown && this.run === run) this.finish('completed');
  }

  async back(): Promise<void> {
    const run = this.run;
    if (!run) return;
    const shown = await this.go(run, run.index - 1, -1);
    // Nothing earlier to show: stay on the current step.
    if (!shown && this.run === run) await this.go(run, run.index, 1);
  }

  skip(): void {
    this.finish('skip');
  }

  close(): void {
    this.finish('close');
  }

  dismiss(tourId: string): void {
    const tour = this.registry.find((t) => t.id === tourId);
    if (tour) this.record(tour, 'dismissed', undefined, true);
  }

  async reset(tourId?: string): Promise<boolean> {
    const userId = this.userId();
    const remote = this.deps.remote;
    if (remote) {
      try {
        await remote.reset(tourId ? (this.deps.namespace ? `${this.deps.namespace}.${tourId}` : tourId) : undefined);
      } catch {
        return false;
      }
    }
    if (tourId) this.deps.store.remove(userId, tourId);
    else this.deps.store.clear(userId);
    // Resetting everything also clears the person's tours in the other back office on the server: "Reset my progress" is per person, not per app.
    this.bump();
    return true;
  }

  getState(): TourState {
    return this.state;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => void this.listeners.delete(listener);
  }

  destroy(): void {
    this.destroyed = true;
    this.finish('destroyed');
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
    for (const t of this.saveTimers.values()) clearTimeout(t);
    this.saveTimers.clear();
    this.listeners.clear();
  }

  // ---------------------------------------------------------------- internals

  private userId(): string {
    return this.deps.subject().userId;
  }

  private publish(next: TourState): void {
    this.state = next;
    for (const l of [...this.listeners]) l();
  }

  private bump(): void {
    this.publish({ ...this.state, revision: this.state.revision + 1 });
  }

  /**
   * Writes the person's progress for a tour to the cache and queues it for the server, under the rules both sides share (a newer
   * version wins, an older one never does, "in progress" never undoes a final status). `immediate` sends it now; otherwise
   * a step is sent a moment later so a quick walk through is one call.
   */
  private record(tour: Tour, status: TourStatus, lastStep?: string, immediate = status !== 'in-progress'): void {
    const userId = this.userId();
    const existing = this.deps.store.get(userId, tour.id);
    if (!replaces(existing, tour.version, status)) return;
    const keepStep = existing && existing.version === tour.version ? existing.lastStep : undefined;
    const next: TourProgress = { status, version: tour.version, lastStep: lastStep ?? keepStep, at: new Date().toISOString(), dirty: this.deps.remote ? true : undefined };
    this.deps.store.set(userId, tour.id, next);
    this.bump();
    if (!this.deps.remote) return;
    const pending = this.saveTimers.get(tour.id);
    if (pending) clearTimeout(pending);
    this.saveTimers.delete(tour.id);
    // Nothing is sent before the first load: the sync sends every unconfirmed entry once it has the server's copy.
    if (this.syncState !== 'ready') return;
    if (immediate) void this.push(tour.id, next);
    else this.saveTimers.set(tour.id, setTimeout(() => {
      this.saveTimers.delete(tour.id);
      const current = this.deps.store.get(userId, tour.id);
      if (current?.dirty) void this.push(tour.id, current);
    }, STEP_SAVE_DELAY_MS));
  }

  private finish(reason: StopReason): void {
    const run = this.run;
    if (!run) return;
    this.run = null;
    run.token += 1;
    if (run.advanceTimer) clearTimeout(run.advanceTimer);
    run.releaseSuccess?.();
    this.deps.engine.hide();
    if (reason !== 'replaced' && reason !== 'destroyed') {
      this.deps.env.emit({ type: 'ended', tourId: run.tour.id, reason, shown: run.shown, drift: [...run.drift] });
      const lastShown = run.tour.steps[run.index]?.id;
      if (run.dontShowAgain || reason === 'skip') this.record(run.tour, 'dismissed', lastShown);
      else if (reason === 'completed') this.record(run.tour, 'completed', lastShown);
      // closing part way keeps it "in progress" (the hub offers Resume); a replay never turns "completed" into "in progress"
      else this.record(run.tour, 'in-progress', lastShown, true);
      this.deps.engine.announce(this.deps.labels().finished);
    } else {
      // a step save waiting for its delay goes out now
      const pending = this.saveTimers.get(run.tour.id);
      if (pending) {
        clearTimeout(pending);
        this.saveTimers.delete(run.tour.id);
        const current = this.deps.store.get(this.userId(), run.tour.id);
        if (current?.dirty && this.syncState === 'ready' && reason === 'replaced') void this.push(run.tour.id, current);
      }
    }
    this.publish({ ...this.state, running: false, tourId: null, stepId: null });
  }

  private guardPasses(step: TourStep): boolean {
    if (!step.when) return true;
    const guard = this.deps.guards?.[step.when];
    return guard ? guard() : true;
  }

  /** Whether step `j` will probably be shown: its guard passes and its target is on the page (or on another page, which is not known yet). */
  private likelyShown(run: Run, j: number): boolean {
    const s = run.tour.steps[j];
    if (!s || run.skipped.has(j) || !this.guardPasses(s)) return false;
    if (!s.target) return true;
    const route = this.routeOf(run, j);
    if (route && !this.at(route)) return true;
    return this.deps.env.findTarget(s.target) !== null;
  }

  /**
   * Finds the next step in `dir` that can be shown (guard passes, page reached, target present) and draws it. Steps that
   * cannot be shown are skipped quietly (logged in development). Resolves false when no step is left in that direction.
   */
  private async go(run: Run, from: number, dir: 1 | -1): Promise<boolean> {
    const token = ++run.token;
    if (run.advanceTimer) clearTimeout(run.advanceTimer);
    run.advanceTimer = null;
    const { env, engine } = this.deps;
    const steps = run.tour.steps;
    for (let i = from; i >= 0 && i < steps.length; i += dir) {
      const step = steps[i];
      if (!step || run.skipped.has(i)) continue;
      if (!this.guardPasses(step)) {
        run.skipped.add(i);
        env.log(`tour "${run.tour.id}": step "${step.id}" skipped (guard "${step.when}" said no)`);
        continue;
      }
      const route = this.routeOf(run, i);
      const moved = route !== undefined && !this.at(route);
      if (route && moved) {
        engine.hide();
        this.deps.navigate(route);
        const arrived = await this.waitForPath(route, ROUTE_TARGET_WAIT_MS, () => token !== run.token);
        if (token !== run.token) return true;
        if (!arrived) {
          run.skipped.add(i);
          run.drift.push(step.id);
          env.log(`tour "${run.tour.id}": step "${step.id}" skipped (could not open ${route})`);
          continue;
        }
      }
      let target: HTMLElement | null = null;
      if (step.target) {
        target = await env.waitForTarget(step.target, moved ? ROUTE_TARGET_WAIT_MS : TARGET_WAIT_MS);
        if (token !== run.token) return true;
        if (!target) {
          run.skipped.add(i);
          if (!step.optional) run.drift.push(step.id);
          env.log(`tour "${run.tour.id}": step "${step.id}" skipped (no element with data-tour="${step.target}")`);
          continue;
        }
      }
      run.releaseSuccess?.();
      run.releaseSuccess = null;
      run.succeeded = false;
      run.index = i;
      run.shown += 1;
      this.publish({ ...this.state, running: true, tourId: run.tour.id, stepId: step.id });
      // Where the person is, so the hub can offer Resume (a replay of a finished tour keeps its status).
      this.record(run.tour, 'in-progress', step.id);
      await this.draw(run, step, target);
      const shown = this.view(run, step, target);
      env.emit({ type: 'step', tourId: run.tour.id, stepId: step.id, ordinal: shown.ordinal, total: shown.total });
      this.watchSuccess(run, step, token);
      return true;
    }
    return false;
  }

  /** The page a step belongs on: its own route, else the route of the step before it, else the tour's. */
  private routeOf(run: Run, index: number): string | undefined {
    for (let j = index; j >= 0; j--) {
      const s = run.tour.steps[j];
      if (s?.route && this.guardPasses(s)) return s.route;
    }
    return run.tour.route;
  }

  private view(run: Run, step: TourStep, target: HTMLElement | null): StepView {
    const { env, translate } = this.deps;
    const i = run.index;
    const counts = run.tour.steps.map((_, j) => j === i || this.likelyShown(run, j));
    const sheet = env.viewportWidth() < SHEET_MAX_WIDTH;
    return {
      key: `${run.tour.id}:${step.id}`,
      title: translate(step.title),
      body: translate(step.body),
      target,
      placement: step.target ? step.placement : 'center',
      direction: env.direction(),
      sheet,
      reducedMotion: env.reducedMotion(),
      ordinal: counts.slice(0, i + 1).filter(Boolean).length,
      total: counts.filter(Boolean).length,
      isFirst: !counts.slice(0, i).some(Boolean),
      isLast: !counts.slice(i + 1).some(Boolean),
      succeeded: run.succeeded,
      waiting: Boolean(step.doIt) && !run.succeeded,
      dontShowAgain: run.dontShowAgain,
      labels: this.deps.labels()
    };
  }

  private async draw(run: Run, step: TourStep, target: HTMLElement | null): Promise<void> {
    await this.deps.engine.show(this.view(run, step, target), this.callbacks);
  }

  private watchSuccess(run: Run, step: TourStep, token: number): void {
    const cond = step.success;
    if (!cond) return;
    const { env } = this.deps;
    const met = () => {
      if (token !== run.token || run.succeeded) return;
      run.succeeded = true;
      this.deps.engine.announce(this.deps.labels().stepDone);
      void this.draw(run, step, env.findTarget(step.target ?? cond.target));
      // A "do it" step moves on by itself once the person has done the thing; the tour never does the thing for them.
      if (step.doIt) run.advanceTimer = setTimeout(() => {
        if (token === run.token && this.run === run) void this.next();
      }, DO_IT_ADVANCE_MS);
    };
    if (cond.when === 'click') {
      const el = env.findTarget(cond.target);
      if (el) run.releaseSuccess = env.onClick(el, met);
      return;
    }
    const present = () => env.findTarget(cond.target) !== null;
    const check = () => {
      if (cond.when === 'appear' ? present() : !present()) met();
    };
    run.releaseSuccess = env.observe(check);
    check();
  }

  private async waitForPath(route: string, timeoutMs: number, cancelled: () => boolean): Promise<boolean> {
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
      if (this.at(route)) return true;
      if (cancelled()) return false;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    return this.at(route);
  }

  private at(route: string): boolean {
    return routeMatches(route, this.deps.pathname(), this.deps.search?.() ?? '');
  }
}

