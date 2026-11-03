/* The web session timer (docs/engineering/session-timeouts.md, "What the web apps do"). Framework-free so it can be
   tested with fake timers; SessionTimeoutGuard.tsx is the React skin.

   What it does:
   - learns when the session ends (`expiresAt` of GET /v1/session/policy, corrected for the device clock with the
     server's Date header) and shows a warning `warnSeconds` before;
   - reports real input to the server (POST /v1/session/activity) at most about once a minute, so a person who only
     reads is not signed out; timers, polling and the policy read never count;
   - while the warning shows, only "Stay signed in" extends the session (moving the mouse does not);
   - the absolute limit (session length) cannot be extended: it warns the same way and says so;
   - tells the other tabs (BroadcastChannel, or the storage event) about activity and sign-out;
   - at the end it stops, hands over to `onEnded` and never starts again. */

import { type SessionChannel, type SessionMessage } from './channel';

/** Problem codes the BFF answers with (401) once the policy has ended a session. */
export type SessionEndReason = 'SESSION_IDLE_TIMEOUT' | 'SESSION_ABSOLUTE_TIMEOUT';
/** Why the session is over: the policy, or the person signing out (here or in another tab). */
export type SessionOverReason = SessionEndReason | 'SIGNED_OUT';
/** Who noticed: this tab's timer, a 401 from the server, or another tab's message. */
export type EndSource = 'timer' | 'server' | 'tab';

/** The part of GET /v1/session/policy the timer needs. */
export interface TimerPolicy {
  idleTimeoutSeconds: number;
  absoluteTimeoutSeconds: number;
  warnSeconds: number;
  lastActivityAt: string | null;
  expiresAt: string | null;
}

export interface TimerLoadedPolicy {
  policy: TimerPolicy;
  /** The server's clock (epoch ms) from the response's Date header, whole seconds; null when there was none. */
  serverTime: number | null;
  /** The device clock (epoch ms) when the answer arrived (a cached answer is older than now); default: now. */
  receivedAt?: number;
}

export type WarningKind = 'idle' | 'absolute';

export type TimeoutView =
  | { phase: 'active' }
  | { phase: 'warning'; kind: WarningKind; secondsLeft: number; endsAt: number; busy: boolean; failed: boolean; dismissed: boolean }
  | { phase: 'ended'; reason: SessionOverReason };

export interface SessionTimeoutOptions {
  /** Reads the policy. `fresh` bypasses any cache (before warning, after "Stay signed in"). Must send X-Background. */
  loadPolicy: (fresh: boolean) => Promise<TimerLoadedPolicy>;
  /** POST /v1/session/activity. */
  reportActivity: () => Promise<void>;
  /** The session is over; clear caches and leave. Called once. */
  onEnded: (reason: SessionOverReason, source: EndSource) => void;
  channel?: SessionChannel | null;
  now?: () => number;
}

export interface SessionTimeoutController {
  start: () => void;
  stop: () => void;
  subscribe: (listener: () => void) => () => void;
  getSnapshot: () => TimeoutView;
  /** Real user input (the guest throttles pointer, key, wheel, scroll and touch events into this). */
  activity: () => void;
  /** The tab is visible again, or the network is back: recompute from the clock now. */
  resync: () => void;
  /** "Stay signed in". */
  staySignedIn: () => Promise<void>;
  /** "Got it" on the absolute-limit warning: hide it (it comes back for the last seconds). */
  dismiss: () => void;
  /** The server said the session is over (401 SESSION_*). */
  end: (reason: SessionOverReason, source: EndSource) => void;
  /** The person signed out in this tab: tell the other tabs. */
  announceSignOut: () => void;
}

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const DAY = 24 * 60 * MINUTE;
/** A browser caps setTimeout at about 24.8 days; long waits are cut into pieces anyway so the clock is re-read. */
const MAX_WAIT = 30 * MINUTE;
/** The policy is read again after activity when the last read is older than this ("refetch rarely"). */
const STALE_AFTER = 5 * MINUTE;
const RETRY_AFTER = 30 * SECOND;
/** A dismissed absolute-limit warning comes back for the last seconds. */
export const REOPEN_AT_SECONDS = 10;
/** Date has whole-second resolution: the real server time is up to 999 ms later. Assume the later, so the session can
    end up to a second early on the screen, never late. */
const DATE_RESOLUTION_MS = 999;

/** How often real input is reported: about once a minute, more often for very short timeouts, rarely for day-long ones. */
export function reportIntervalMs(idleTimeoutSeconds: number): number {
  const idle = idleTimeoutSeconds * SECOND;
  return Math.min(idle >= DAY ? 10 * MINUTE : MINUTE, Math.max(SECOND, idle / 4));
}

const ACTIVE: TimeoutView = { phase: 'active' };

export function createSessionTimeout(options: SessionTimeoutOptions): SessionTimeoutController {
  const now = options.now ?? Date.now;
  const channel = options.channel ?? null;
  const listeners = new Set<() => void>();
  let snapshot: TimeoutView = ACTIVE;

  let running = false;
  let over: SessionOverReason | null = null;
  /** Bumped whenever pending async work must be dropped (end, stop). */
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let unsubscribeChannel: (() => void) | undefined;

  /* The policy, in server time. */
  let loaded = false;
  let loadedAt = 0;
  let offset = 0; // server clock minus device clock
  let idleMs = 0;
  let warnMs = 0;
  let lastActivityServer: number | null = null;
  let expiresServer: number | null = null;

  /* The person. */
  let lastInput = 0;
  let lastReport = 0;
  let reporting: Promise<void> | null = null;

  /* The warning. */
  let warning = false;
  let dismissed = false;
  let busy = false;
  let failed = false;
  let opening = false;
  let expiring = false;

  const deadline = (): number => (expiresServer ?? Number.POSITIVE_INFINITY) - offset;
  /** Whether the session ends at the absolute limit rather than the idle timeout. */
  const kind = (): WarningKind => (lastActivityServer !== null && expiresServer !== null && expiresServer < lastActivityServer + idleMs - SECOND ? 'absolute' : 'idle');

  function build(): TimeoutView {
    if (over) return { phase: 'ended', reason: over };
    if (!warning) return ACTIVE;
    return { phase: 'warning', kind: kind(), secondsLeft: Math.max(0, Math.ceil((deadline() - now()) / SECOND)), endsAt: deadline(), busy, failed, dismissed };
  }

  function emit(): void {
    snapshot = build();
    [...listeners].forEach((l) => l());
  }

  const clearTimer = (): void => {
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
  };

  function adopt(result: TimerLoadedPolicy): void {
    const { policy, serverTime } = result;
    const received = result.receivedAt ?? now();
    if (serverTime !== null) offset = serverTime + DATE_RESOLUTION_MS - received;
    idleMs = policy.idleTimeoutSeconds * SECOND;
    warnMs = policy.warnSeconds * SECOND;
    lastActivityServer = policy.lastActivityAt ? Date.parse(policy.lastActivityAt) : null;
    expiresServer = policy.expiresAt ? Date.parse(policy.expiresAt) : null;
    if (Number.isNaN(lastActivityServer ?? 0)) lastActivityServer = null;
    if (Number.isNaN(expiresServer ?? 0)) expiresServer = null;
    loaded = expiresServer !== null;
    loadedAt = received;
  }

  async function refresh(fresh: boolean): Promise<void> {
    const g = generation;
    const result = await options.loadPolicy(fresh);
    if (g === generation && !over) adopt(result);
  }

  /** Input at `atDevice` (device clock) moves the idle deadline; the absolute limit stays where it is. */
  function applyActivity(atDevice: number): void {
    if (!loaded || expiresServer === null || kind() === 'absolute') return;
    const at = atDevice + offset;
    lastActivityServer = Math.max(lastActivityServer ?? 0, at);
    expiresServer = Math.max(expiresServer, at + idleMs);
  }

  async function report(): Promise<void> {
    if (reporting) return reporting;
    reporting = (async () => {
      const sentAt = now();
      await options.reportActivity();
      lastReport = Math.max(lastReport, sentAt);
      applyActivity(sentAt);
      channel?.post({ type: 'activity', at: sentAt });
      if (running && !over && now() - loadedAt > STALE_AFTER) await refresh(true).catch(() => undefined);
    })().finally(() => {
      reporting = null;
    });
    return reporting;
  }

  function schedule(): void {
    clearTimer();
    if (!running || over || !loaded) return;
    const t = now();
    const endAt = deadline();
    if (t >= endAt) {
      void expire();
      return;
    }
    const warnAt = warnMs > 0 ? endAt - warnMs : Number.POSITIVE_INFINITY;
    if (warning) {
      if (dismissed && (endAt - t) / SECOND <= REOPEN_AT_SECONDS) dismissed = false;
      emit();
      timer = setTimeout(schedule, Math.min(SECOND, endAt - t));
      return;
    }
    if (t >= warnAt) {
      void openWarning();
      return;
    }
    timer = setTimeout(schedule, Math.min((Number.isFinite(warnAt) ? warnAt : endAt) - t, MAX_WAIT));
  }

  /** The warning time has come: first make sure the server agrees (other tabs, input not yet reported). */
  async function openWarning(): Promise<void> {
    if (opening) return;
    opening = true;
    const g = generation;
    try {
      if (lastInput > lastReport && kind() === 'idle') await report();
      await refresh(true);
    } catch {
      /* offline or refused: warn with what we know (a 401 has already ended the session through the bus) */
    } finally {
      opening = false;
    }
    if (g !== generation || over) return;
    const t = now();
    const endAt = deadline();
    if (t < endAt - warnMs) {
      schedule(); // the server has more time for us than we thought
      return;
    }
    if (t < endAt) {
      warning = true;
      dismissed = false;
      failed = false;
    }
    schedule();
  }

  /** The deadline has passed: ask the server once more before signing out (it may know of activity we do not). */
  async function expire(): Promise<void> {
    if (expiring) return;
    expiring = true;
    const g = generation;
    try {
      await refresh(true);
    } catch {
      /* offline, or a 401 that has ended the session through the bus */
    } finally {
      expiring = false;
    }
    if (g !== generation || over) return;
    if (deadline() > now()) {
      schedule();
      return;
    }
    end(kind() === 'absolute' ? 'SESSION_ABSOLUTE_TIMEOUT' : 'SESSION_IDLE_TIMEOUT', 'timer');
  }

  function end(reason: SessionOverReason, source: EndSource): void {
    if (over) return;
    over = reason;
    generation += 1;
    warning = false;
    busy = false;
    clearTimer();
    emit();
    if (source !== 'tab') channel?.post({ type: 'ended', reason });
    options.onEnded(reason, source);
  }

  function onMessage(message: SessionMessage): void {
    if (over || !running) return;
    if (message.type === 'ended') {
      end(message.reason, 'tab');
      return;
    }
    // Another tab's person is active (its report reached the server): this tab's session is alive too.
    applyActivity(message.at);
    lastReport = Math.max(lastReport, message.at);
    if (warning && kind() === 'idle' && deadline() - now() > warnMs) {
      warning = false;
      busy = false;
      emit();
    }
    schedule();
  }

  async function begin(): Promise<void> {
    const g = generation;
    try {
      await refresh(false);
    } catch {
      if (g === generation && running && !over) timer = setTimeout(() => void begin(), RETRY_AFTER);
      return;
    }
    if (g === generation) schedule();
  }

  return {
    start() {
      if (running || over) return;
      running = true;
      unsubscribeChannel = channel?.subscribe(onMessage);
      void begin();
    },
    stop() {
      running = false;
      generation += 1;
      clearTimer();
      unsubscribeChannel?.();
      unsubscribeChannel = undefined;
      opening = false;
      expiring = false;
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    getSnapshot: () => snapshot,
    activity() {
      // While the warning shows only its own buttons count; the absolute limit cannot be extended at all.
      if (!running || over || !loaded || warning) return;
      const t = now();
      if (t >= deadline()) {
        schedule();
        return;
      }
      lastInput = t;
      if (kind() === 'idle' && t - lastReport >= reportIntervalMs(idleMs / SECOND)) void report().catch(() => undefined);
    },
    resync() {
      if (!running || over || !loaded) return;
      if (now() < deadline() && now() - loadedAt > MINUTE) {
        // Back after a while (a sleeping laptop, a hidden tab): other tabs may have kept the session alive.
        void refresh(true).then(schedule, () => undefined);
      }
      schedule();
    },
    async staySignedIn() {
      if (!running || over || !warning || busy || kind() === 'absolute') return;
      busy = true;
      failed = false;
      emit();
      try {
        await report();
        await refresh(true);
      } catch {
        busy = false;
        failed = !over;
        emit();
        return;
      }
      busy = false;
      const endAt = deadline();
      const t = now();
      if (warnMs === 0 || t < endAt - warnMs) warning = false; // otherwise the absolute limit is close: keep warning
      emit();
      schedule();
    },
    dismiss() {
      if (!warning || kind() !== 'absolute') return;
      dismissed = true;
      emit();
    },
    end,
    announceSignOut() {
      channel?.post({ type: 'ended', reason: 'SIGNED_OUT' });
    }
  };
}
