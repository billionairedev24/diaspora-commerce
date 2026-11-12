import { audienceAllows, toursFor,type TourSubject } from './audience';
import { type EngineCallbacks, type StepView, type TourEngine, type TourLabels } from './engine';
import { type TourEnvironment } from './environment';
import { type TourOutcome, type TourProgress, type TourProgressStore } from './progress';
import { type Tour, tourSchema, type TourStep } from './schema';

/** Below this width the popover has no room next to a target: tours do not start by themselves and steps open as a bottom sheet. */
export const SHEET_MAX_WIDTH = 640;
/** Narrowest viewport where a tour may start on its own. */
export const AUTO_START_MIN_WIDTH = SHEET_MAX_WIDTH;
/** Wait for a target on the current page (a table still loading). */
export const TARGET_WAIT_MS = 2500;
/** Wait for a target after navigating to another page. */
export const ROUTE_TARGET_WAIT_MS = 6000;

export type StartSource = 'auto' | 'manual';
export type StartResult = 'started' | 'unknown-tour' | 'not-allowed' | 'no-steps';
export type StopReason = 'completed' | 'skip' | 'close' | 'replaced' | 'destroyed';

/** What the UI can observe about the service. A new object is made on every change (useSyncExternalStore). */
export interface TourState {
  readonly running: boolean;
  readonly tourId: string | null;
  readonly stepId: string | null;
}

export interface TourServiceDeps {
  engine: TourEngine;
  store: TourProgressStore;
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

export interface TourService {
  /** Replaces the registered tours. Each definition is validated; an invalid one throws. */
  register(tours: readonly Tour[]): void;
  tours(): readonly Tour[];
  /** The registered tours this person may see. */
  available(): Tour[];
  /** What the person last did with a tour (this version), if anything. */
  progressOf(tourId: string): TourProgress | undefined;
  start(tourId: string, source?: StartSource): Promise<StartResult>;
  /** Starts the first tour that is meant to begin on this page and has not been seen, if the page is calm enough. */
  autoStart(): Promise<Tour | undefined>;
  next(): Promise<void>;
  back(): Promise<void>;
  skip(): void;
  close(): void;
  /** Forgets progress for one tour, or all tours of the person ("Replay tours" with auto-start again). */
  reset(tourId?: string): void;
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

const IDLE: TourState = { running: false, tourId: null, stepId: null };

export function createTourService(deps: TourServiceDeps): TourService {
  return new TourServiceImpl(deps);
}

class TourServiceImpl implements TourService {
  private registry: readonly Tour[] = [];
  private state: TourState = IDLE;
  private run: Run | null = null;
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

  constructor(private readonly deps: TourServiceDeps) {}

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
    const tour = this.registry.find((t) => t.id === tourId);
    return tour ? this.deps.store.get(this.deps.subject().userId, tour.id, tour.version) : undefined;
  }

  async start(tourId: string, source: StartSource = 'manual'): Promise<StartResult> {
    const tour = this.registry.find((t) => t.id === tourId);
    if (!tour) return 'unknown-tour';
    if (!audienceAllows(tour.audience, this.deps.subject())) return 'not-allowed';
    if (this.run) this.finish('replaced');
    const run: Run = { tour, source, index: -1, skipped: new Set(), succeeded: false, dontShowAgain: false, releaseSuccess: null, drift: [], shown: 0, token: 0 };
    this.run = run;
    // A tour that begins by itself is recorded as seen right away, so a reload in the middle never repeats it.
    if (source === 'auto') this.record(tour, 'skipped');
    this.publish({ running: true, tourId: tour.id, stepId: null });
    const shown = await this.go(run, 0, 1);
    if (!shown && this.run === run) {
      this.finish('replaced');
      return 'no-steps';
    }
    return 'started';
  }

  async autoStart(): Promise<Tour | undefined> {
    if (this.run || this.autoStarted) return undefined;
    const { env } = this.deps;
    if (env.automated() || env.viewportWidth() < AUTO_START_MIN_WIDTH) return undefined;
    if (env.hasBlockingUi() || env.hasUnsavedInput()) return undefined;
    if (typeof document !== 'undefined' && document.activeElement instanceof HTMLElement && /^(input|textarea|select)$/i.test(document.activeElement.tagName)) return undefined;
    const path = this.deps.pathname();
    const onPage = (route: string) => (route.endsWith('/*') ? path.startsWith(route.slice(0, -1)) : route === path);
    const candidate = this.available().find((t) => t.autoStart?.routes.some(onPage) && !this.progressOf(t.id) && this.firstStepReady(t));
    if (!candidate) return undefined;
    const result = await this.start(candidate.id, 'auto');
    if (result === 'started') this.autoStarted = true;
    return result === 'started' ? candidate : undefined;
  }

  /** A tour only starts by itself when its first step can be drawn right here, right now (the page has loaded); otherwise it would wander off to another page. */
  private firstStepReady(tour: Tour): boolean {
    const run: Run = { tour, source: 'auto', index: 0, skipped: new Set(), succeeded: false, dontShowAgain: false, releaseSuccess: null, drift: [], shown: 0, token: 0 };
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

  reset(tourId?: string): void {
    this.deps.store.clear(this.deps.subject().userId, tourId);
  }

  getState(): TourState {
    return this.state;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => void this.listeners.delete(listener);
  }

  destroy(): void {
    this.finish('destroyed');
    this.listeners.clear();
  }

  // ---------------------------------------------------------------- internals

  private publish(next: TourState): void {
    this.state = next;
    for (const l of [...this.listeners]) l();
  }

  private record(tour: Tour, outcome: TourOutcome): void {
    this.deps.store.set(this.deps.subject().userId, tour.id, tour.version, { outcome, at: new Date().toISOString() });
  }

  private finish(reason: StopReason): void {
    const run = this.run;
    if (!run) return;
    this.run = null;
    run.token += 1;
    run.releaseSuccess?.();
    this.deps.engine.hide();
    if (reason !== 'replaced' && reason !== 'destroyed') {
      this.deps.env.emit({ type: 'ended', tourId: run.tour.id, reason, shown: run.shown, drift: [...run.drift] });
      const existing = this.deps.store.get(this.deps.subject().userId, run.tour.id, run.tour.version);
      if (run.dontShowAgain) this.record(run.tour, 'never');
      else if (reason === 'completed') this.record(run.tour, 'completed');
      // closing a replay must not turn "completed" into "skipped"
      else if (!existing) this.record(run.tour, 'skipped');
      this.deps.engine.announce(this.deps.labels().finished);
    }
    this.publish(IDLE);
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
      this.publish({ running: true, tourId: run.tour.id, stepId: step.id });
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
