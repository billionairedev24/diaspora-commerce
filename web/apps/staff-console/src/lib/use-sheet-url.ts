'use client';

import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useEffect, useRef } from 'react';

/** What a sheet's URL change may set (a string) or remove (null) next to its own parameter. */
export type SheetChange = Record<string, string | null>;

const hrefOf = (pathname: string, next: URLSearchParams): string => {
  const qs = next.toString();
  return qs ? `${pathname}?${qs}` : pathname;
};

/**
 * A detail sheet whose open item lives in the URL (`?variant=AT123`, `?open=<id>`), the one way every staff page does it:
 *
 * - opening a sheet from the list adds a history entry (`push`), so the browser Back button closes the sheet first and a
 *   deep link still opens it; switching to another item while it is open, or changing something inside it (`do=`), only replaces;
 * - closing it (the close button, Escape, a click outside) goes back to the entry it came from when it opened here, and
 *   otherwise drops the parameters (a deep link has no earlier entry on this page);
 * - every write reads the address as it is now, never a copy captured at render, so a close that fires while a click
 *   elsewhere is navigating cannot write the old page's address over the new one, and a close after the route already changed does nothing.
 *
 * `extra` are the parameters that only make sense with the sheet open (`do=`): they go when it closes.
 */
export function useSheetUrl(key: string, extra: readonly string[] = []) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const value = params.get(key);
  // Whether this mount added the history entry the sheet is open on (so closing can step back over it).
  const pushed = useRef(false);
  const extraKey = extra.join(',');
  useEffect(() => {
    if (value === null) pushed.current = false;
  }, [value]);

  const open = useCallback(
    (id: string, change: SheetChange = {}) => {
      const next = new URLSearchParams(window.location.search);
      const wasOpen = next.has(key);
      next.set(key, id);
      for (const [k, v] of Object.entries(change)) {
        if (v === null) next.delete(k);
        else next.set(k, v);
      }
      if (wasOpen) router.replace(hrefOf(pathname, next), { scroll: false });
      else {
        pushed.current = true;
        router.push(hrefOf(pathname, next), { scroll: false });
      }
    },
    [key, pathname, router]
  );

  /** Changes the parameters of the open sheet in place (an action started or finished inside it). */
  const update = useCallback(
    (change: SheetChange) => {
      if (window.location.pathname !== pathname) return;
      const next = new URLSearchParams(window.location.search);
      for (const [k, v] of Object.entries(change)) {
        if (v === null) next.delete(k);
        else next.set(k, v);
      }
      router.replace(hrefOf(pathname, next), { scroll: false });
    },
    [pathname, router]
  );

  const close = useCallback(() => {
    // Already on another page (a menu link won the race): the sheet went with its page; there is nothing to write.
    if (window.location.pathname !== pathname) return;
    if (pushed.current && window.history.length > 1) {
      pushed.current = false;
      router.back();
      return;
    }
    const next = new URLSearchParams(window.location.search);
    next.delete(key);
    extraKey.split(',').filter(Boolean).forEach((k) => next.delete(k));
    router.replace(hrefOf(pathname, next), { scroll: false });
  }, [key, extraKey, pathname, router]);

  return { value, open, update, close };
}
