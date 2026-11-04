'use client';

import { type ReactNode, useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';

import { getSessionChannel, type SessionChannel } from './channel';
import { type SessionTimeoutLabels } from './labels';
import { SessionTimeoutDialog } from './SessionTimeoutDialog';
import {
  createSessionTimeout,
  type EndSource,
  type SessionEndReason,
  type SessionOverReason,
  type SessionTimeoutController,
  type TimeoutView,
  type TimerLoadedPolicy
} from './timeout';

/** Real input only: pointer presses, keys, wheel, touch and scroll. Mouse movement, timers and requests do not count. */
const INPUT_EVENTS = ['pointerdown', 'keydown', 'wheel', 'touchstart', 'scroll'] as const;
/** Input is handed to the timer at most this often. */
const INPUT_THROTTLE_MS = 1000;

export interface SessionTimeoutHookOptions {
  /** Reads GET /v1/session/policy with X-Background (never counts as activity). */
  loadPolicy: (fresh: boolean) => Promise<TimerLoadedPolicy>;
  /** POST /v1/session/activity. */
  reportActivity: () => Promise<void>;
  /** Hears the request layer's report of a 401 SESSION_IDLE_TIMEOUT / SESSION_ABSOLUTE_TIMEOUT; returns the unsubscribe. */
  subscribeEnd: (listener: (reason: SessionEndReason) => void) => () => void;
  /** The session is over: clear caches and in-memory data here, finish the sign-out. Called once. */
  onEnded: (reason: SessionOverReason, source: EndSource) => void;
  /** Tab-to-tab messages (default: the page's BroadcastChannel / storage channel; null for none). */
  channel?: SessionChannel | null;
  /** Nothing runs when false (a guest has no session). */
  enabled?: boolean;
}

export interface SessionTimeoutState {
  view: TimeoutView;
  controller: SessionTimeoutController;
}

const IDLE_VIEW: TimeoutView = { phase: 'active' };

/**
 * Runs the session timer for a signed-in page: loads the policy, listens to real user input, reports it (about once a
 * minute), warns before the end, signs out at the end, follows the other tabs and the server's 401s.
 */
export function useSessionTimeout(options: SessionTimeoutHookOptions): SessionTimeoutState {
  const latest = useRef(options);
  latest.current = options;
  const enabled = options.enabled ?? true;

  const [controller] = useState<SessionTimeoutController>(() => {
    // Resolved on first use, so a page without a session (a guest) never opens a channel.
    const channel = (): SessionChannel | null => (latest.current.channel === undefined ? getSessionChannel() : latest.current.channel);
    return createSessionTimeout({
      loadPolicy: (fresh) => latest.current.loadPolicy(fresh),
      reportActivity: () => latest.current.reportActivity(),
      onEnded: (reason, source) => latest.current.onEnded(reason, source),
      channel: {
        post: (message) => channel()?.post(message),
        subscribe: (listener) => channel()?.subscribe(listener) ?? (() => undefined)
      }
    });
  });

  const view = useSyncExternalStore(controller.subscribe, controller.getSnapshot, () => IDLE_VIEW);

  useEffect(() => {
    if (!enabled) return;
    controller.start();
    const unsubscribe = latest.current.subscribeEnd((reason) => controller.end(reason, 'server'));
    let last = 0;
    const onInput = (): void => {
      const t = Date.now();
      if (t - last < INPUT_THROTTLE_MS) return;
      last = t;
      controller.activity();
    };
    const onVisible = (): void => {
      if (document.visibilityState !== 'visible') return;
      controller.resync();
      controller.activity();
    };
    const onOnline = (): void => controller.resync();
    INPUT_EVENTS.forEach((name) => window.addEventListener(name, onInput, { capture: true, passive: true }));
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('online', onOnline);
    return () => {
      controller.stop();
      unsubscribe();
      INPUT_EVENTS.forEach((name) => window.removeEventListener(name, onInput, { capture: true }));
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('online', onOnline);
    };
  }, [controller, enabled]);

  return { view, controller };
}

export interface SessionTimeoutGuardProps extends SessionTimeoutHookOptions {
  labels: SessionTimeoutLabels;
  /** For the clock time and the spoken countdown. */
  locale: string;
  /** "Sign out now": sign out for real (the app's sign-out; call announceSignOut() so the other tabs follow). */
  onSignOut: () => void;
  /** What replaces the page once the session is over (the app's SessionEndedScreen, or nothing). */
  renderEnded: (reason: SessionOverReason) => ReactNode;
  /** The signed-in page. Dropped, not just hidden, once the session is over. Omit to mount the guard beside the page. */
  children?: ReactNode;
}

/**
 * The session timer for an app shell: wrap the signed-in page (or mount it beside the page) and it shows the warning
 * dialog before the end and `renderEnded` after it. With `enabled={false}` it renders its children and does nothing.
 */
export function SessionTimeoutGuard({ labels, locale, onSignOut, renderEnded, children, ...options }: SessionTimeoutGuardProps) {
  const { view, controller } = useSessionTimeout(options);
  const stay = useCallback(() => void controller.staySignedIn(), [controller]);
  const dismiss = useCallback(() => controller.dismiss(), [controller]);
  if (view.phase === 'ended') return <>{renderEnded(view.reason)}</>;
  return (
    <>
      {children}
      <SessionTimeoutDialog
        open={view.phase === 'warning' && !view.dismissed}
        kind={view.phase === 'warning' ? view.kind : 'idle'}
        secondsLeft={view.phase === 'warning' ? view.secondsLeft : 0}
        endsAt={view.phase === 'warning' ? view.endsAt : 0}
        busy={view.phase === 'warning' && view.busy}
        failed={view.phase === 'warning' && view.failed}
        labels={labels}
        locale={locale}
        onStay={stay}
        onDismiss={dismiss}
        onSignOut={onSignOut}
      />
    </>
  );
}
