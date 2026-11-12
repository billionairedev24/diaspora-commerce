'use client';

import { Button, CaretDown, Menu, MenuGroup, MenuItem, MenuSeparator } from '@afrotransact/ui';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';

import { type TourSubject } from './audience';
import { createDriverEngine } from './driver-engine';
import { type TourEngine, type TourLabels } from './engine';
import { createBrowserEnvironment, type TourEnvironment } from './environment';
import { type ChapterStatus, createTourProgressStore, type TourProgressRemote, type TourProgressStore } from './progress';
import { type Tour, type TourJourney } from './schema';
import { createTourService, type StartResult, type SyncState, type TourService, type TourState } from './service';

/** When (after the page appears) a first-time tour may start: a pause for the page to settle, then two more tries for slow pages. */
export const AUTO_START_ATTEMPTS_MS = [1200, 3500, 7000] as const;


export interface TourProviderProps {
  /** Every tour (chapter) of the app (validated with defineTour). */
  tours: readonly Tour[];
  /** The groups the hub shows the chapters in (each tour names its `journey`). */
  journeys?: readonly TourJourney[];
  /** The signed-in person; null while the session loads (tours stay idle). */
  subject: TourSubject | null;
  /** Translates an i18n key of the app's catalogue. */
  translate: (key: string) => string;
  labels: TourLabels;
  /** Current path without a query string (usePathname()). */
  pathname: string;
  /** Opens a page of the app (router.push). */
  navigate: (route: string) => void;
  /** Named guards for steps' `when`. */
  guards?: Readonly<Record<string, () => boolean>>;
  /** Start first-time tours by themselves. Apps pass false until the shell has finished loading. */
  autoStart?: boolean;
  /** The server copy of the progress (the source of truth). Without it progress lives only in this browser. */
  remote?: TourProgressRemote;
  /** Prefix of this app's tour ids on the server (`sc`, `st`). */
  namespace?: string;
  /** Where the Tour hub page lives; the Help menu links to it. */
  hubHref?: string;
  /** Replaceable pieces (tests, or a different library). */
  engine?: TourEngine;
  store?: TourProgressStore;
  environment?: TourEnvironment;
  children: ReactNode;
}

interface TourContextValue {
  service: TourService | null;
  state: TourState;
  tours: readonly Tour[];
  journeys: readonly TourJourney[];
  hubHref: string | undefined;
  navigate: (route: string) => void;
}

const IDLE_STATE: TourState = { running: false, tourId: null, stepId: null, sync: 'idle', revision: 0 };
const Ctx = createContext<TourContextValue>({ service: null, state: IDLE_STATE, tours: [], journeys: [], hubHref: undefined, navigate: () => undefined });

/** Latest values for the service's callbacks, so the service is made once per person and never captures stale props. */
interface Latest {
  subject: TourSubject | null;
  translate: TourProviderProps['translate'];
  labels: TourLabels;
  pathname: string;
  navigate: TourProviderProps['navigate'];
  guards: TourProviderProps['guards'];
}

/**
 * Provides the tour service to the back office. It creates the service in the browser only (never while rendering on the
 * server), loads the person's progress from the server FIRST, then starts the first-time tour of the current page once
 * the app says it is ready, and tears everything down on sign-out. If the server cannot be reached no tour starts by itself
 * and the load is tried again later.
 */
export function TourProvider({ tours, journeys = [], subject, translate, labels, pathname, navigate, guards, autoStart = false, remote, namespace, hubHref, engine, store, environment, children }: TourProviderProps) {
  const latest = useRef<Latest>({ subject, translate, labels, pathname, navigate, guards });
  latest.current = { subject, translate, labels, pathname, navigate, guards };
  const [service, setService] = useState<TourService | null>(null);
  const userId = subject?.userId ?? null;

  useEffect(() => {
    if (!userId) return;
    const made = createTourService({
      engine: engine ?? createDriverEngine(),
      store: store ?? createTourProgressStore(),
      remote,
      namespace,
      env: environment ?? createBrowserEnvironment(),
      subject: () => latest.current.subject ?? { userId, roles: [], permissions: [], flags: [] },
      translate: (k) => latest.current.translate(k),
      labels: () => latest.current.labels,
      navigate: (r) => latest.current.navigate(r),
      pathname: () => latest.current.pathname,
      search: () => (typeof window === 'undefined' ? '' : window.location.search),
      guards: new Proxy({} as Record<string, () => boolean>, { get: (_t, name) => (typeof name === 'string' ? latest.current.guards?.[name] : undefined) })
    });
    made.register(tours);
    setService(made);
    // Ask the server before anything can start by itself.
    void made.sync();
    const again = () => void made.sync();
    window.addEventListener('online', again);
    return () => {
      window.removeEventListener('online', again);
      made.destroy();
      setService(null);
    };
    // tours are re-registered by the effect below; the service itself lives per person
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId, engine, store, environment, remote, namespace]);

  useEffect(() => {
    service?.register(tours);
  }, [service, tours]);

  const subscribe = useCallback((l: () => void) => service?.subscribe(l) ?? (() => undefined), [service]);
  const state = useSyncExternalStore(
    subscribe,
    () => service?.getState() ?? IDLE_STATE,
    () => IDLE_STATE
  );

  // First-time tour of this page, once the app is ready and the server's progress is in.
  const synced = state.sync === 'ready';
  useEffect(() => {
    if (!service || !autoStart || !synced) return;
    // The page may still be loading: try a few times, then give up until the next page.
    const timers = AUTO_START_ATTEMPTS_MS.map((ms) => window.setTimeout(() => void service.autoStart(), ms));
    return () => timers.forEach((id) => window.clearTimeout(id));
  }, [service, autoStart, synced, pathname]);

  const value = useMemo<TourContextValue>(() => ({ service, state, tours, journeys, hubHref, navigate }), [service, state, tours, journeys, hubHref, navigate]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export interface UseTour {
  /** True once the tours are ready (the browser has them and a person is signed in). */
  ready: boolean;
  running: boolean;
  tourId: string | null;
  stepId: string | null;
  start: (tourId: string) => Promise<StartResult | 'not-ready'>;
  stop: () => void;
}

/** The tour that is running (if any) and a way to start or stop one. */
export function useTour(): UseTour {
  const { service, state } = useContext(Ctx);
  const start = useCallback(async (id: string) => (service ? service.start(id, 'manual') : 'not-ready'), [service]);
  const stop = useCallback(() => service?.close(), [service]);
  return { ready: service !== null, running: state.running, tourId: state.tourId, stepId: state.stepId, start, stop };
}

// ---------------------------------------------------------------- the Tour hub

export interface HubChapter {
  id: string;
  title: string;
  summary: string;
  steps: number;
  status: ChapterStatus;
  /** A newer version of this chapter exists: a small "What's new" mark. */
  hasUpdate: boolean;
  resumable: boolean;
}

export interface HubJourney {
  id: string;
  title: string;
  summary: string;
  chapters: HubChapter[];
  /** Chapters finished, skipped, and in total (for the progress line). */
  done: number;
  skipped: number;
  total: number;
  /** The chapter "Continue" opens: the first one not finished or skipped. */
  nextId: string | null;
}

export interface UseTourHub {
  ready: boolean;
  sync: SyncState;
  autoStartEnabled: boolean;
  journeys: HubJourney[];
  /** Chapters with something new. */
  updates: number;
  /** The chapter someone left part way (first found), for the Help menu's Resume. */
  resumeId: string | null;
  start: (tourId: string) => void;
  resume: (tourId: string) => void;
  skipChapter: (tourId: string) => void;
  skipJourney: (journeyId: string) => void;
  /** Resolves false when the server could not be reached (nothing was reset). */
  resetAll: () => Promise<boolean>;
}

/** What the Tour hub shows: the chapters this person may see, grouped into journeys, with their progress; and what the buttons do. */
export function useTourHub(translate: (key: string) => string): UseTourHub {
  const { service, state, journeys: defs } = useContext(Ctx);
  const view = useMemo(() => {
    if (!service) return { journeys: [] as HubJourney[], updates: 0, resumeId: null as string | null };
    const chapters = service.available();
    const byJourney = new Map<string, Tour[]>();
    for (const t of chapters) byJourney.set(t.journey ?? '', [...(byJourney.get(t.journey ?? '') ?? []), t]);
    const known = new Set(defs.map((j) => j.id));
    const groups = [...defs.map((j) => ({ id: j.id, title: j.title, summary: j.summary })), ...(([...byJourney.keys()].some((k) => !known.has(k))) ? [{ id: '', title: '', summary: '' }] : [])];
    let updates = 0;
    let resumeId: string | null = null;
    const out: HubJourney[] = [];
    for (const g of groups) {
      const list = g.id === '' ? chapters.filter((t) => !t.journey || !known.has(t.journey)) : (byJourney.get(g.id) ?? []);
      if (list.length === 0) continue;
      const cs: HubChapter[] = list.map((t) => {
        const info = service.info(t.id);
        if (info.hasUpdate) updates += 1;
        if (!resumeId && info.resumable) resumeId = t.id;
        return { id: t.id, title: translate(t.title), summary: translate(t.summary), steps: t.steps.length, status: info.status, hasUpdate: info.hasUpdate, resumable: info.resumable };
      });
      const next = cs.find((c) => c.status !== 'completed' && c.status !== 'dismissed');
      out.push({
        id: g.id,
        title: g.title ? translate(g.title) : '',
        summary: g.summary ? translate(g.summary) : '',
        chapters: cs,
        done: cs.filter((c) => c.status === 'completed').length,
        skipped: cs.filter((c) => c.status === 'dismissed').length,
        total: cs.length,
        nextId: next?.id ?? null
      });
    }
    return { journeys: out, updates, resumeId };
    // `state.revision` changes whenever progress does
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [service, state.revision, state.sync, defs, translate]);
  const start = useCallback((id: string) => void service?.start(id, 'manual'), [service]);
  const resume = useCallback((id: string) => void service?.start(id, 'manual', { fromStep: service.info(id).lastStep }), [service]);
  const skipChapter = useCallback((id: string) => service?.dismiss(id), [service]);
  const skipJourney = useCallback(
    (journeyId: string) => {
      if (!service) return;
      for (const t of service.available()) if ((t.journey ?? '') === journeyId && service.info(t.id).status === 'not-started') service.dismiss(t.id);
    },
    [service]
  );
  const resetAll = useCallback(async () => (service ? service.reset() : false), [service]);
  return {
    ready: service !== null,
    sync: state.sync,
    autoStartEnabled: service?.autoStartEnabled() ?? true,
    journeys: view.journeys,
    updates: view.updates,
    resumeId: view.resumeId,
    start,
    resume,
    skipChapter,
    skipJourney,
    resetAll
  };
}

/** Words of the Tour hub (translated by the app). `{done}`, `{total}`, `{n}` and `{skipped}` are filled in. */
export interface HubLabels {
  title: string;
  intro: string;
  /** "{done} of {total} chapters done" */
  progress: string;
  /** "{skipped} skipped" */
  skippedCount: string;
  /** "{n} steps" */
  stepCount: string;
  start: string;
  resume: string;
  replay: string;
  /** Opens the first chapter not done yet. */
  continueJourney: string;
  skipJourney: string;
  skipChapter: string;
  statusNotStarted: string;
  statusInProgress: string;
  statusDone: string;
  statusSkipped: string;
  whatsNew: string;
  resetButton: string;
  resetConfirm: string;
  resetYes: string;
  resetCancel: string;
  resetFailed: string;
  resetDone: string;
  /** Shown when the server copy could not be loaded: the hub still works from this browser. */
  offline: string;
  /** Shown when staff turned automatic tours off for everyone. */
  autoOff: string;
  empty: string;
}

function fill(template: string, vars: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m));
}

export interface TourHubProps {
  translate: (key: string) => string;
  labels: HubLabels;
  className?: string;
}

/**
 * The Tour hub: every chapter the person may take, grouped into journeys, with progress, Resume, Replay and Skip, and a way to
 * reset their progress. Chapters never start by themselves from here: every start is a click. Buttons carry
 * `data-tour-entry="<chapter id>"` so the end-to-end drift guard can start each chapter.
 */
export function TourHub({ translate, labels, className }: TourHubProps) {
  const hub = useTourHub(translate);
  const [confirming, setConfirming] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const statusLabel: Record<ChapterStatus, string> = { 'not-started': labels.statusNotStarted, 'in-progress': labels.statusInProgress, completed: labels.statusDone, dismissed: labels.statusSkipped };
  const reset = async () => {
    setConfirming(false);
    setMessage((await hub.resetAll()) ? labels.resetDone : labels.resetFailed);
  };
  if (!hub.ready) return null;
  return (
    <div className={`at-tour-hub${className ? ` ${className}` : ''}`} data-tour="tour-hub">
      <p className="at-tour-hub-intro">{labels.intro}</p>
      {hub.sync === 'failed' ? <p className="at-tour-hub-note" role="status">{labels.offline}</p> : null}
      {!hub.autoStartEnabled ? <p className="at-tour-hub-note" role="status">{labels.autoOff}</p> : null}
      {hub.journeys.length === 0 ? <p>{labels.empty}</p> : null}
      {hub.journeys.map((j) => (
        <section key={j.id || 'more'} className="at-tour-journey" aria-labelledby={`at-journey-${j.id || 'more'}`} data-journey={j.id}>
          <header className="at-tour-journey-head">
            <div className="at-tour-journey-text">
              <h2 id={`at-journey-${j.id || 'more'}`} className="at-tour-journey-title">{j.title}</h2>
              {j.summary ? <p className="at-tour-journey-summary">{j.summary}</p> : null}
              <p className="at-tour-journey-progress">
                <span>{fill(labels.progress, { done: j.done, total: j.total })}</span>
                {j.skipped > 0 ? <span> · {fill(labels.skippedCount, { skipped: j.skipped })}</span> : null}
              </p>
              <div className="at-tour-bar" role="progressbar" aria-label={j.title} aria-valuemin={0} aria-valuemax={j.total} aria-valuenow={j.done}>
                <span style={{ inlineSize: `${j.total ? Math.round((j.done / j.total) * 100) : 0}%` }} />
              </div>
            </div>
            <div className="at-tour-journey-actions">
              {j.nextId ? (
                <Button size="sm" variant="primary" onClick={() => hub.start(j.nextId as string)}>{labels.continueJourney}</Button>
              ) : null}
              {j.chapters.some((c) => c.status === 'not-started') ? (
                <Button size="sm" variant="neutral" onClick={() => hub.skipJourney(j.id)}>{labels.skipJourney}</Button>
              ) : null}
            </div>
          </header>
          <ol className="at-tour-chapters">
            {j.chapters.map((c) => (
              <li key={c.id} className="at-tour-chapter" data-chapter={c.id} data-status={c.status}>
                <div className="at-tour-chapter-text">
                  <h3 className="at-tour-chapter-title">
                    {c.title}
                    {c.hasUpdate ? <span className="at-tour-new">{labels.whatsNew}</span> : null}
                  </h3>
                  <p className="at-tour-chapter-summary">{c.summary}</p>
                  <p className="at-tour-chapter-meta">
                    <span className={`at-tour-status at-tour-status-${c.status}`}>{statusLabel[c.status]}</span>
                    <span> · {fill(labels.stepCount, { n: c.steps })}</span>
                  </p>
                </div>
                <div className="at-tour-chapter-actions">
                  {c.resumable ? (
                    <Button size="sm" variant="primary" data-tour-resume={c.id} onClick={() => hub.resume(c.id)}>{labels.resume}</Button>
                  ) : null}
                  <Button size="sm" variant={c.resumable ? 'neutral' : 'primary'} data-tour-entry={c.id} onClick={() => hub.start(c.id)}>
                    {c.status === 'completed' || c.status === 'dismissed' || c.resumable ? labels.replay : labels.start}
                  </Button>
                  {c.status === 'not-started' ? <Button size="sm" variant="neutral" onClick={() => hub.skipChapter(c.id)}>{labels.skipChapter}</Button> : null}
                </div>
              </li>
            ))}
          </ol>
        </section>
      ))}
      <footer className="at-tour-hub-foot">
        {confirming ? (
          <div role="alertdialog" aria-label={labels.resetButton} className="at-tour-confirm">
            <p>{labels.resetConfirm}</p>
            <Button size="sm" variant="danger" onClick={() => void reset()}>{labels.resetYes}</Button>
            <Button size="sm" variant="neutral" onClick={() => setConfirming(false)}>{labels.resetCancel}</Button>
          </div>
        ) : (
          <Button size="sm" variant="neutral" data-tour-reset onClick={() => { setMessage(null); setConfirming(true); }}>{labels.resetButton}</Button>
        )}
        {message ? <p role="status" className="at-tour-hub-note">{message}</p> : null}
      </footer>
    </div>
  );
}

// ---------------------------------------------------------------- Help menu

export interface TourHelpMenuProps {
  translate: (key: string) => string;
  /** "Help" */
  label: string;
  /** "Guided tours" */
  heading: string;
  /** "Tour hub" */
  hubLabel: string;
  /** "Resume: {title}" */
  resumeLabel: string;
  /** "What's new" */
  whatsNewLabel: string;
  /** Extra items (a link to the help centre) rendered before the tours. */
  children?: ReactNode;
}

/**
 * The Help menu of the black header: a link to the Tour hub (progress per journey, resume, replay, skip) and a quick Resume of
 * the chapter the person left part way. Renders nothing until the tours are ready.
 */
export function TourHelpMenu({ translate, label, heading, hubLabel, resumeLabel, whatsNewLabel, children }: TourHelpMenuProps) {
  const { navigate, hubHref } = useContext(Ctx);
  const hub = useTourHub(translate);
  if (!hub.ready) return null;
  const resumeTitle = hub.resumeId ? hub.journeys.flatMap((j) => j.chapters).find((c) => c.id === hub.resumeId)?.title : undefined;
  return (
    <Menu
      label={label}
      width={300}
      trigger={
        <button type="button" className="at-tour-help" data-tour="help-menu">
          <span>{label}</span>
          {hub.updates > 0 ? <span className="at-tour-dot" aria-hidden /> : null}
          <CaretDown size={14} aria-hidden />
        </button>
      }
    >
      {children ? (
        <>
          <MenuGroup>{children}</MenuGroup>
          <MenuSeparator />
        </>
      ) : null}
      <MenuGroup label={heading}>
        <div className="at-tour-help-heading">{heading}</div>
        {hubHref ? (
          <MenuItem onSelect={() => navigate(hubHref)}>
            <span className="at-tour-help-entry" data-tour-entry="hub">
              <span>{hubLabel}</span>
              {hub.updates > 0 ? <span className="at-tour-new">{whatsNewLabel}</span> : null}
            </span>
          </MenuItem>
        ) : null}
        {hub.resumeId && resumeTitle ? (
          <MenuItem onSelect={() => hub.resume(hub.resumeId as string)}>
            <span className="at-tour-help-entry" data-tour-resume-entry={hub.resumeId}>
              <span>{resumeLabel.replace('{title}', resumeTitle)}</span>
            </span>
          </MenuItem>
        ) : null}
      </MenuGroup>
    </Menu>
  );
}
