'use client';

import { useSyncExternalStore } from 'react';

/* One shared 1 s clock for every countdown on the page. Hydration-safe: the server snapshot is null, so server HTML and
   the hydrating render agree (countdowns show placeholders), then the client switches to the live time. */

let now = 0;
let timer: ReturnType<typeof setInterval> | undefined;
const listeners = new Set<() => void>();

function tick() {
  now = Date.now();
  listeners.forEach((l) => l());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  if (!timer) {
    now = Date.now();
    timer = setInterval(tick, 1000);
  }
  return () => {
    listeners.delete(listener);
    if (!listeners.size && timer) {
      clearInterval(timer);
      timer = undefined;
    }
  };
}

const getSnapshot = () => (now ||= Date.now());
const getServerSnapshot = (): number | null => null;

/** Current time, updated every second on the client; null during SSR and hydration. */
export function useNow(): number | null {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}
