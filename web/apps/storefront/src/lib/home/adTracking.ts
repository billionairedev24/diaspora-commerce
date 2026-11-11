'use client';

import { type RefObject, useCallback, useEffect, useRef, useState } from 'react';

import { browserApi } from '@/lib/api/browser';

/*
 * Paid home modules count their own impressions and clicks per placement (POST /v1/home/ad-events): nothing but the
 * placement id and the kind is sent, no shopper identity. An impression is counted once per page view, when at least
 * half of the module has been on screen. A per-session cap (the placement's `maxPerSession`) hides a module that this
 * browser session has already seen that many times: the count lives in sessionStorage and only ever lives in the tab.
 * Everything is best effort: a failing counter, blocked storage or a missing IntersectionObserver never reaches the shopper.
 */

const KEY = 'at.adViews';

function readCounts(): Record<string, number> {
  try {
    const raw = window.sessionStorage.getItem(KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : {};
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, number>) : {};
  } catch {
    return {};
  }
}

/** How many page views of this session already showed the placement. */
export function sessionViews(id: string): number {
  return readCounts()[id] ?? 0;
}

function addView(id: string) {
  try {
    const counts = readCounts();
    counts[id] = (counts[id] ?? 0) + 1;
    window.sessionStorage.setItem(KEY, JSON.stringify(counts));
  } catch {
    /* storage blocked: the cap simply does not apply */
  }
}

/** True when the placement has a cap and this session has reached it. */
export function capReached(id: string, cap: number | undefined): boolean {
  return !!cap && cap > 0 && sessionViews(id) >= cap;
}

function send(id: string, event: 'impression' | 'click') {
  void browserApi()
    .recordHomeAdEvent(id, event)
    .catch(() => undefined);
}

/**
 * Tracks one paid module. Attach `ref` to its root element; call `click` from the module's links. `hidden` is true
 * after mount when the session cap is reached (render nothing).
 */
export function useAdTracking(id: string, cap?: number): { ref: RefObject<HTMLElement | null>; hidden: boolean; click: () => void } {
  const ref = useRef<HTMLElement | null>(null);
  const [hidden, setHidden] = useState(false);
  useEffect(() => {
    if (capReached(id, cap)) {
      setHidden(true);
      return;
    }
    const el = ref.current;
    if (!el) return;
    if (typeof IntersectionObserver === 'undefined') {
      addView(id);
      send(id, 'impression');
      return;
    }
    const io = new IntersectionObserver(
      (entries) => {
        if (!entries.some((e) => e.isIntersecting && e.intersectionRatio >= 0.5)) return;
        io.disconnect();
        addView(id);
        send(id, 'impression');
      },
      { threshold: [0.5] }
    );
    io.observe(el);
    return () => io.disconnect();
  }, [id, cap]);
  const click = useCallback(() => send(id, 'click'), [id]);
  return { ref, hidden, click };
}
