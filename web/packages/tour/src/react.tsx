'use client';

import { CaretDown, Menu, MenuGroup, MenuItem, MenuSeparator } from '@afrotransact/ui';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';

import { type TourSubject } from './audience';
import { createDriverEngine } from './driver-engine';
import { type TourEngine, type TourLabels } from './engine';
import { createBrowserEnvironment, type TourEnvironment } from './environment';
import { createTourProgressStore, type TourProgressStore } from './progress';
import { type Tour } from './schema';
import { createTourService, type StartResult, type TourService, type TourState } from './service';

/** When (after the page appears) a first-time tour may start: a pause for the page to settle, then two more tries for slow pages. */
export const AUTO_START_ATTEMPTS_MS = [1200, 3500, 7000] as const;

export interface TourProviderProps {
  /** Every tour of the app (validated with defineTour). */
  tours: readonly Tour[];
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
}

const IDLE_STATE: TourState = { running: false, tourId: null, stepId: null };
const Ctx = createContext<TourContextValue>({ service: null, state: IDLE_STATE, tours: [] });

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
 * server), starts the first-time tour of the current page once the app says it is ready, and tears everything down on sign-out.
 */
export function TourProvider({ tours, subject, translate, labels, pathname, navigate, guards, autoStart = false, engine, store, environment, children }: TourProviderProps) {
  const latest = useRef<Latest>({ subject, translate, labels, pathname, navigate, guards });
  latest.current = { subject, translate, labels, pathname, navigate, guards };
  const [service, setService] = useState<TourService | null>(null);
  const userId = subject?.userId ?? null;

  useEffect(() => {
    if (!userId) return;
    const made = createTourService({
      engine: engine ?? createDriverEngine(),
      store: store ?? createTourProgressStore(),
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
    return () => {
      made.destroy();
      setService(null);
    };
    // tours are re-registered by the effect below; the service itself lives per person
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId, engine, store, environment]);

  useEffect(() => {
    service?.register(tours);
  }, [service, tours]);

  // First-time tour of this page, once the app is ready.
  useEffect(() => {
    if (!service || !autoStart) return;
    // The page may still be loading: try a few times, then give up until the next page.
    const timers = AUTO_START_ATTEMPTS_MS.map((ms) => window.setTimeout(() => void service.autoStart(), ms));
    return () => timers.forEach((id) => window.clearTimeout(id));
  }, [service, autoStart, pathname]);

  const subscribe = useCallback((l: () => void) => service?.subscribe(l) ?? (() => undefined), [service]);
  const state = useSyncExternalStore(
    subscribe,
    () => service?.getState() ?? IDLE_STATE,
    () => IDLE_STATE
  );
  const value = useMemo<TourContextValue>(() => ({ service, state, tours }), [service, state, tours]);
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

export interface LauncherEntry {
  id: string;
  title: string;
  summary: string;
  /** The person has finished this tour (this version). */
  completed: boolean;
}

export interface UseTourLauncher {
  ready: boolean;
  /** The tours this person may replay, in order. */
  entries: LauncherEntry[];
  replay: (tourId: string) => void;
  /** Forgets "Don't show again" and finished marks, so first-time tours can start by themselves again. */
  resetAll: () => void;
}

/** What the Help menu needs: the person's tours and how to replay them. */
export function useTourLauncher(translate: (key: string) => string): UseTourLauncher {
  const { service, state } = useContext(Ctx);
  // `state` changes when a tour ends, which is when "completed" can change.
  const entries = useMemo<LauncherEntry[]>(
    () => (service ? service.available().map((t) => ({ id: t.id, title: translate(t.title), summary: translate(t.summary), completed: service.progressOf(t.id)?.outcome === 'completed' })) : []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [service, state, translate]
  );
  const replay = useCallback((id: string) => void service?.start(id, 'manual'), [service]);
  const resetAll = useCallback(() => service?.reset(), [service]);
  return { ready: service !== null, entries, replay, resetAll };
}

export interface TourHelpMenuProps {
  translate: (key: string) => string;
  /** "Help" */
  label: string;
  /** "Replay a tour" */
  heading: string;
  /** "Show tours again automatically" */
  resetLabel: string;
  /** Shown when nobody has a tour (kept short; the menu normally is not rendered then). */
  className?: string;
  /** Extra items (a link to the help centre) rendered before the tours. */
  children?: ReactNode;
}

/**
 * The Help menu of the black header: "Replay a tour" with one entry per tour the person may see, and a way to let first-time
 * tours start again. Renders nothing until the tours are ready.
 */
export function TourHelpMenu({ translate, label, heading, resetLabel, children }: TourHelpMenuProps) {
  const { ready, entries, replay, resetAll } = useTourLauncher(translate);
  if (!ready) return null;
  return (
    <Menu
      label={label}
      width={300}
      trigger={
        <button type="button" className="at-tour-help" data-tour="help-menu">
          <span>{label}</span>
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
        {entries.map((e) => (
          <MenuItem key={e.id} onSelect={() => replay(e.id)}>
            <span className="at-tour-help-entry" data-tour-entry={e.id}>
              <span>{e.title}</span>
              <span className="at-tour-help-summary">{e.summary}</span>
            </span>
          </MenuItem>
        ))}
      </MenuGroup>
      <MenuSeparator />
      <MenuGroup>
        <MenuItem onSelect={resetAll}>{resetLabel}</MenuItem>
      </MenuGroup>
    </Menu>
  );
}
