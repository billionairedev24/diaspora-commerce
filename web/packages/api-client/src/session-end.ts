import { type SessionEndReason, SessionEndReasonSchema } from './schemas/session';

/* One place that learns "the sign-out policy ended this session". The request layer (http.ts) reports every 401 that
   carries SESSION_IDLE_TIMEOUT or SESSION_ABSOLUTE_TIMEOUT here; the session timer of the app subscribes once and signs
   the person out of the page. Many requests can fail together (a page fires a dozen queries): the first report wins and
   the rest are dropped, so the sign-out flow runs once. */

/** The reason a problem `code` stands for, or undefined for any other code. */
export function sessionEndReasonOf(code: string | undefined): SessionEndReason | undefined {
  const parsed = SessionEndReasonSchema.safeParse(code);
  return parsed.success ? parsed.data : undefined;
}

export type SessionEndListener = (reason: SessionEndReason) => void;

export interface SessionEndBus {
  /** Reports an ended session; returns false when one was already reported (nothing happens then). */
  report(reason: SessionEndReason): boolean;
  /** Listens for the (first) report. A listener added after it hears it at once, so a late mount still signs out. */
  subscribe(listener: SessionEndListener): () => void;
  /** The reason already reported, if any. */
  ended(): SessionEndReason | undefined;
  /** Forgets the report (tests, and a page that stays open after the person signed in again). */
  reset(): void;
}

export function createSessionEndBus(): SessionEndBus {
  let reported: SessionEndReason | undefined;
  const listeners = new Set<SessionEndListener>();
  return {
    report(reason) {
      if (reported) return false;
      reported = reason;
      [...listeners].forEach((l) => l(reason));
      return true;
    },
    subscribe(listener) {
      listeners.add(listener);
      if (reported) listener(reported);
      return () => {
        listeners.delete(listener);
      };
    },
    ended: () => reported,
    reset() {
      reported = undefined;
    }
  };
}

/** The page's bus (browser only: on the server every request would share it, so reports there are ignored). */
export const sessionEndBus: SessionEndBus = createSessionEndBus();

/** Reports to the page's bus, in the browser. */
export function reportSessionEnd(reason: SessionEndReason, bus: SessionEndBus = sessionEndBus): boolean {
  if (bus === sessionEndBus && typeof window === 'undefined') return false;
  return bus.report(reason);
}
