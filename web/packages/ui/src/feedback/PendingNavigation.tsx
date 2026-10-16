'use client';

import { useEffect, useState } from 'react';

import { Spinner } from './Spinner';
import styles from './PendingNavigation.module.css';

export interface PendingNavigationProps {
  /** Status text shown under the spinner (already translated), e.g. "Taking you to sign in…". */
  label: string;
  /** Link paths (pathname prefixes) that start a full-page navigation through redirects, e.g. the sign-in start. */
  prefixes: readonly string[];
}

/** Longest the overlay stays up if the navigation never leaves the page (blocked, cancelled, offline). */
const GIVE_UP_MS = 30_000;

function startsNavigation(e: MouseEvent, prefixes: readonly string[]): boolean {
  if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return false;
  const anchor = e.target instanceof Element ? e.target.closest('a') : null;
  if (!anchor || (anchor.target && anchor.target !== '_self') || anchor.hasAttribute('download')) return false;
  let path: string;
  try {
    path = new URL(anchor.href, window.location.href).pathname;
  } catch {
    return false;
  }
  return prefixes.some((p) => path.startsWith(p));
}

/**
 * Sign-in and "open my back office" are plain links that bounce through several redirects (BFF → identity → BFF), during
 * which the old page just sits there. This puts a spinner over the page from the click until the next page replaces it,
 * so the person can see something is happening (and does not click again).
 */
export function PendingNavigation({ label, prefixes }: PendingNavigationProps) {
  const [pending, setPending] = useState(false);
  useEffect(() => {
    let timer: number | undefined;
    const onClick = (e: MouseEvent) => {
      if (!startsNavigation(e, prefixes)) return;
      setPending(true);
      window.clearTimeout(timer);
      timer = window.setTimeout(() => setPending(false), GIVE_UP_MS);
    };
    // Back/forward restores the page from the browser's cache with the overlay still up.
    const onShow = (e: PageTransitionEvent) => {
      if (e.persisted) setPending(false);
    };
    document.addEventListener('click', onClick, true);
    window.addEventListener('pageshow', onShow);
    return () => {
      window.clearTimeout(timer);
      document.removeEventListener('click', onClick, true);
      window.removeEventListener('pageshow', onShow);
    };
  }, [prefixes]);
  if (!pending) return null;
  return (
    <div className={styles.overlay} aria-busy="true">
      <Spinner size="xl" label={label} />
    </div>
  );
}
