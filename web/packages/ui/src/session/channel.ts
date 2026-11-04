/* Tab-to-tab messages for the session timer: activity in one tab keeps the others alive, a sign-out or timeout in one
   tab signs out all. BroadcastChannel where there is one, the `storage` event of localStorage otherwise. Messages carry a
   time and a reason, never a token or any account data. */

import { type SessionOverReason } from './timeout';

export type SessionMessage = { type: 'activity'; at: number } | { type: 'ended'; reason: SessionOverReason };

export interface SessionChannel {
  post: (message: SessionMessage) => void;
  /** Hears the other tabs (never this tab's own posts). */
  subscribe: (listener: (message: SessionMessage) => void) => () => void;
}

const CHANNEL_NAME = 'at-session-timeout';
const STORAGE_KEY = 'at.session-timeout.message';

function parse(raw: unknown): SessionMessage | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const m = raw as Record<string, unknown>;
  if (m.type === 'activity' && typeof m.at === 'number') return { type: 'activity', at: m.at };
  if (m.type === 'ended' && (m.reason === 'SESSION_IDLE_TIMEOUT' || m.reason === 'SESSION_ABSOLUTE_TIMEOUT' || m.reason === 'SIGNED_OUT')) return { type: 'ended', reason: m.reason };
  return null;
}

function broadcastChannel(): SessionChannel | null {
  if (typeof BroadcastChannel === 'undefined') return null;
  const bc = new BroadcastChannel(CHANNEL_NAME);
  const listeners = new Set<(message: SessionMessage) => void>();
  bc.onmessage = (e: MessageEvent<unknown>) => {
    const message = parse(e.data);
    if (message) [...listeners].forEach((l) => l(message));
  };
  return {
    post: (message) => bc.postMessage(message),
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    }
  };
}

/** localStorage fires `storage` in the other tabs when a value is set; the value is removed again at once. */
function storageChannel(): SessionChannel | null {
  if (typeof window === 'undefined') return null;
  try {
    window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
  const listeners = new Set<(message: SessionMessage) => void>();
  window.addEventListener('storage', (e) => {
    if (e.key !== STORAGE_KEY || !e.newValue) return;
    let message: SessionMessage | null = null;
    try {
      message = parse(JSON.parse(e.newValue));
    } catch {
      message = null;
    }
    if (message) [...listeners].forEach((l) => l(message));
  });
  return {
    post(message) {
      try {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...message, nonce: Math.random() }));
        window.localStorage.removeItem(STORAGE_KEY);
      } catch {
        /* storage blocked: the other tabs find out from the server (401) */
      }
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    }
  };
}

let shared: SessionChannel | null | undefined;

/** This page's channel (one per page, so a post is heard by the other tabs and not by this one); null without any. */
export function getSessionChannel(): SessionChannel | null {
  if (shared === undefined) shared = typeof window === 'undefined' ? null : (broadcastChannel() ?? storageChannel());
  return shared;
}

/** Tells the other tabs the person signed out here (their sign-out button), so they leave too. */
export function announceSignOut(channel: SessionChannel | null = getSessionChannel()): void {
  channel?.post({ type: 'ended', reason: 'SIGNED_OUT' });
}

/** For tests: forget the page's channel. */
export function resetSessionChannel(): void {
  shared = undefined;
}

export { broadcastChannel as createBroadcastSessionChannel, storageChannel as createStorageSessionChannel };
